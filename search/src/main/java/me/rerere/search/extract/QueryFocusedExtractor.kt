package me.rerere.search.extract

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min

/** One passage of the cleaned document, with its BM25 score against the focus query. */
data class FocusChunk(
    /** 0-based position in the cleaned document, i.e. document order. */
    val index: Int,
    /** The passage text, verbatim from the cleaned document. */
    val text: String,
    /** BM25 score against the focus query; 0.0 when no query term occurs in the passage. */
    val score: Double,
)

/**
 * Outcome of a focused extraction. [text] holds the selected passages joined by a blank line,
 * always in **document** order (never score order) so the caller reads the page the way it was
 * written.
 */
data class FocusResult(
    val text: String,
    val chunksTotal: Int,
    val chunksSelected: Int,
    val originalChars: Int,
    val returnedChars: Int,
    val focused: Boolean,
    val fallbackUsed: Boolean,
)

/**
 * Query-focused passage selection: chunk, rank with a compact BM25, return the best passages.
 *
 * Pure JVM: no Android APIs, no WebView, no network, no embeddings, no model, no third-party
 * dependency, so it runs on the host JVM in unit tests and costs almost nothing on a weak
 * phone. Indexing is a single pass over the chunks and scoring is one pass per query term, so
 * nothing here is quadratic in the document size. The implementation is deliberately free of
 * `try`/`catch`: it neither suspends nor swallows, so a coroutine cancellation (or any other
 * failure) propagates to the caller instead of being turned into a result.
 */
object QueryFocusedExtractor {

    /** Passages a focus query may select by default. */
    const val DEFAULT_TOP_K = 8

    /** Ceiling for a focused result when the caller does not ask for less. */
    const val FOCUS_CHAR_BUDGET = 12 * 1024

    /** Passages are packed up to roughly this size; smaller neighbours are merged into one. */
    private const val PREFERRED_CHUNK_CHARS = 900

    /** No passage is allowed to grow past this without being split. */
    private const val MAX_CHUNK_CHARS = 1600

    /** A block below this is a fragment, not a paragraph, and gets merged with its neighbour. */
    private const val MIN_CHUNK_CHARS = 400

    /** Smallest useful tail when the budget clips the last selected passage. */
    private const val MIN_TAIL_CHARS = 200

    /** BM25 term-frequency saturation. */
    private const val BM25_K1 = 1.2

    /** BM25 length normalisation. */
    private const val BM25_B = 0.75

    /** Passages scoring below this share of the best score are dropped as noise. */
    private const val MIN_SCORE_RATIO = 0.05

    /** Leading passages returned when the query matches nothing at all. */
    private const val FALLBACK_CHUNKS = 4

    /** Word-shingle Jaccard at or above which a passage counts as a near-duplicate. */
    private const val NEAR_DUPLICATE_JACCARD = 0.8

    /** Shingle containment at or above which a passage is a duplicate of a longer one. */
    private const val NEAR_DUPLICATE_CONTAINMENT = 0.9

    /** Containment is only trusted once the smaller passage has this many shingles. */
    private const val MIN_SHINGLES_FOR_CONTAINMENT = 6

    /** Shingle width used by the near-duplicate check. */
    private const val SHINGLE_WORDS = 3

    private val BLOCK_BREAK = Regex("\\n\\s*\\n")
    private val SENTENCE_BREAK = Regex("(?<=[.!?…])\\s+")
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Rank [text] against [query] and return the most relevant passages inside [charBudget].
     *
     * [charBudget] is an upper bound on [FocusResult.text]; pass
     * `min(maxChars, FOCUS_CHAR_BUDGET)` when the caller asked for less than the default.
     */
    fun focus(
        text: String,
        query: String,
        charBudget: Int = FOCUS_CHAR_BUDGET,
        topK: Int = DEFAULT_TOP_K,
    ): FocusResult {
        val originalChars = text.length
        val budget = charBudget.coerceAtLeast(1)

        if (text.isBlank()) return FocusResult("", 0, 0, originalChars, 0, true, false)

        val chunks = chunk(text)
        if (chunks.isEmpty()) return FocusResult("", 0, 0, originalChars, 0, true, false)

        val queryTokens = tokenize(query).distinct()

        // A blank query is not a query: hand the text back untouched (inside the budget)
        // instead of pretending a ranking happened.
        if (queryTokens.isEmpty()) {
            val plain = text.trim()
            val out = if (plain.length <= budget) plain else cutAtWordBoundary(plain, budget)
            return FocusResult(
                text = out,
                chunksTotal = chunks.size,
                chunksSelected = if (out.isEmpty()) 0 else 1,
                originalChars = originalChars,
                returnedChars = out.length,
                focused = false,
                fallbackUsed = false,
            )
        }

        val index = Bm25Index(chunks.map(::tokenize))
        val scored = chunks.indices.map { i -> FocusChunk(i, chunks[i], bm25(index, queryTokens, i)) }
        val matched = scored.filter { it.score > 0.0 }

        val candidates: List<FocusChunk>
        val fallbackUsed: Boolean
        if (matched.isEmpty()) {
            // Never answer "no match" with nothing: the caller asked for the page, so hand back
            // the leading passages and say that is what happened.
            fallbackUsed = true
            candidates = chunks.indices
                .take(FALLBACK_CHUNKS)
                .map { i -> FocusChunk(i, chunks[i], 0.0) }
        } else {
            fallbackUsed = false
            val floor = matched.maxOf { it.score } * MIN_SCORE_RATIO
            candidates = matched
                .filter { it.score >= floor }
                .sortedWith(compareByDescending<FocusChunk> { it.score }.thenBy { it.index })
                .take(topK.coerceAtLeast(1))
        }

        val ordered = dropNearDuplicates(candidates).sortedBy { it.index }
        val packed = pack(ordered, budget)

        return FocusResult(
            text = packed.text,
            chunksTotal = chunks.size,
            chunksSelected = packed.count,
            originalChars = originalChars,
            returnedChars = packed.text.length,
            focused = true,
            fallbackUsed = fallbackUsed,
        )
    }

    /**
     * Every passage of [text] with its score, in document order. Exposed so callers and tests
     * can see why a passage was or was not selected.
     */
    fun rank(text: String, query: String): List<FocusChunk> {
        if (text.isBlank()) return emptyList()
        val chunks = chunk(text)
        if (chunks.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).distinct()
        if (queryTokens.isEmpty()) return chunks.indices.map { i -> FocusChunk(i, chunks[i], 0.0) }
        val index = Bm25Index(chunks.map(::tokenize))
        return chunks.indices.map { i -> FocusChunk(i, chunks[i], bm25(index, queryTokens, i)) }
    }

    /** Lowercase word/number tokens of [raw]: NFC normalised, Cyrillic and Latin alike. */
    fun tokenize(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val out = ArrayList<String>(normalized.length / 4 + 1)
        for (match in TOKEN.findAll(normalized)) {
            if (match.value.isNotEmpty()) out.add(match.value)
        }
        return out
    }

    /**
     * Split [text] into logical passages: blank lines are paragraph boundaries, small
     * neighbours are merged, oversize blocks are split on sentence boundaries, exact duplicates
     * are dropped. One pass over the blocks, no quadratic work.
     */
    private fun chunk(text: String): List<String> {
        val blocks = BLOCK_BREAK.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return emptyList()

        val merged = ArrayList<String>(blocks.size)
        val buffer = StringBuilder()
        for (block in blocks) {
            if (buffer.isEmpty()) {
                if (block.length >= MIN_CHUNK_CHARS) merged.add(block) else buffer.append(block)
                continue
            }
            val fitsTarget = buffer.length + 2 + block.length <= PREFERRED_CHUNK_CHARS
            if (buffer.length < MIN_CHUNK_CHARS && fitsTarget) {
                buffer.append("\n\n").append(block)
            } else {
                merged.add(buffer.toString())
                buffer.setLength(0)
                if (block.length >= MIN_CHUNK_CHARS) merged.add(block) else buffer.append(block)
            }
        }
        if (buffer.isNotEmpty()) merged.add(buffer.toString())

        val seen = HashSet<String>(merged.size * 2)
        val out = ArrayList<String>(merged.size)
        for (block in merged) {
            for (piece in splitOversize(block)) {
                if (piece.isBlank()) continue
                if (seen.add(normalizeForKey(piece))) out.add(piece)
            }
        }
        return out
    }

    /** Split a block that is longer than [MAX_CHUNK_CHARS] on sentence boundaries. */
    private fun splitOversize(block: String): List<String> {
        if (block.length <= MAX_CHUNK_CHARS) return listOf(block)

        val sentences = SENTENCE_BREAK.split(block).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size <= 1) return hardSplit(block)

        val out = ArrayList<String>(block.length / MAX_CHUNK_CHARS + 1)
        val buffer = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > MAX_CHUNK_CHARS) {
                if (buffer.isNotEmpty()) {
                    out.add(buffer.toString())
                    buffer.setLength(0)
                }
                out.addAll(hardSplit(sentence))
                continue
            }
            if (buffer.isEmpty()) {
                buffer.append(sentence)
                continue
            }
            if (buffer.length + 1 + sentence.length <= MAX_CHUNK_CHARS) {
                buffer.append(' ').append(sentence)
            } else {
                out.add(buffer.toString())
                buffer.setLength(0)
                buffer.append(sentence)
            }
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString())
        return out
    }

    /** Last resort for a single sentence longer than [MAX_CHUNK_CHARS]. */
    private fun hardSplit(sentence: String): List<String> {
        if (sentence.length <= MAX_CHUNK_CHARS) return listOf(sentence)
        val out = ArrayList<String>(sentence.length / MAX_CHUNK_CHARS + 1)
        var start = 0
        while (start < sentence.length) {
            val end = min(start + MAX_CHUNK_CHARS, sentence.length)
            val piece = sentence.substring(start, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
            start = end
        }
        return out
    }

    /**
     * Drop passages that repeat text already kept. Candidates are few (top-K, at most a handful
     * of fallback passages), so the pairwise check stays tiny.
     */
    private fun dropNearDuplicates(chunks: List<FocusChunk>): List<FocusChunk> {
        if (chunks.size < 2) return chunks
        val kept = ArrayList<FocusChunk>(chunks.size)
        val keptShingles = ArrayList<Set<Int>>(chunks.size)
        for (chunk in chunks) {
            val shingles = shinglesOf(chunk.text)
            var duplicate = false
            for (existing in keptShingles) {
                if (isDuplicate(shingles, existing)) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) {
                kept.add(chunk)
                keptShingles.add(shingles)
            }
        }
        return kept
    }

    /** Hashed word n-grams of [text]; the shingle width is [SHINGLE_WORDS]. */
    private fun shinglesOf(text: String): Set<Int> {
        val tokens = tokenize(text)
        if (tokens.size < SHINGLE_WORDS) return tokens.mapTo(HashSet()) { it.hashCode() }
        val out = HashSet<Int>(tokens.size)
        for (start in 0..(tokens.size - SHINGLE_WORDS)) {
            var hash = 17
            for (i in start until start + SHINGLE_WORDS) hash = hash * 31 + tokens[i].hashCode()
            out.add(hash)
        }
        return out
    }

    /**
     * Two passages are duplicates when the same words dominate both (Jaccard) or when one
     * repeats the other (containment). Prose that repeats a sentence produces few distinct
     * shingles, so containment is what catches a passage already returned in a longer form.
     */
    private fun isDuplicate(a: Set<Int>, b: Set<Int>): Boolean {
        if (a.isEmpty() && b.isEmpty()) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val small = if (a.size <= b.size) a else b
        val large = if (a.size <= b.size) b else a
        var intersection = 0
        for (value in small) if (large.contains(value)) intersection++
        val union = a.size + b.size - intersection
        if (union > 0 && intersection.toDouble() / union >= NEAR_DUPLICATE_JACCARD) return true
        return small.size >= MIN_SHINGLES_FOR_CONTAINMENT &&
            intersection.toDouble() / small.size >= NEAR_DUPLICATE_CONTAINMENT
    }

    /** Case- and whitespace-insensitive key, used only for cheap exact-duplicate detection. */
    private fun normalizeForKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .trim()

    private class Packed(val text: String, val count: Int)

    /**
     * Join the selected passages in document order until [budget] is reached. A passage that
     * does not fit is either clipped to the remaining space (when that space is worth having)
     * or skipped, so the result never exceeds the budget.
     */
    private fun pack(chunks: List<FocusChunk>, budget: Int): Packed {
        val sb = StringBuilder(min(budget, 4096))
        var used = 0
        var count = 0
        for (chunk in chunks) {
            val separator = if (sb.isEmpty()) 0 else 2
            val need = separator + chunk.text.length
            if (used + need <= budget) {
                if (separator == 2) sb.append("\n\n")
                sb.append(chunk.text)
                used += need
                count++
                continue
            }
            if (sb.isEmpty()) {
                sb.append(cutAtWordBoundary(chunk.text, budget))
                count++
                break
            }
            val remaining = budget - used - separator
            if (remaining >= MIN_TAIL_CHARS) {
                sb.append("\n\n").append(cutAtWordBoundary(chunk.text, remaining))
                count++
            }
            break
        }
        val text = sb.toString()
        return Packed(text, if (text.isBlank()) 0 else count)
    }

    /** Clip [text] to [limit] characters, preferring the last word boundary in the final third. */
    private fun cutAtWordBoundary(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val cut = text.substring(0, limit)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace >= limit * 2 / 3) cut.substring(0, lastSpace).trimEnd() else cut.trimEnd()
    }

    /** Inverted-token bookkeeping for BM25: per-document frequencies, document frequencies, lengths. */
    private class Bm25Index(docs: List<List<String>>) {
        val freq: List<Map<String, Int>>
        val docFreq: HashMap<String, Int> = HashMap()
        val lengths: IntArray = IntArray(docs.size)
        val averageLength: Double

        init {
            val perDoc = ArrayList<Map<String, Int>>(docs.size)
            for ((i, tokens) in docs.withIndex()) {
                lengths[i] = tokens.size
                val counts = HashMap<String, Int>(tokens.size * 2)
                for (token in tokens) {
                    val seen = counts[token]
                    if (seen == null) {
                        counts[token] = 1
                        docFreq[token] = (docFreq[token] ?: 0) + 1
                    } else {
                        counts[token] = seen + 1
                    }
                }
                perDoc.add(counts)
            }
            freq = perDoc
            averageLength = if (docs.isEmpty()) 0.0 else lengths.sum().toDouble() / docs.size
        }
    }

    /**
     * BM25 of one passage against the query terms, with the Robertson IDF (always positive).
     * The caller de-duplicates the query terms, so a repeated term never double-counts.
     */
    private fun bm25(index: Bm25Index, queryTokens: List<String>, doc: Int): Double {
        if (index.freq.isEmpty() || index.averageLength <= 0.0) return 0.0
        val counts = index.freq[doc]
        val length = index.lengths[doc].toDouble()
        val docs = index.freq.size.toDouble()
        var score = 0.0
        for (term in queryTokens) {
            val tf = counts[term] ?: continue
            val df = index.docFreq[term] ?: continue
            val idf = ln(1.0 + (docs - df + 0.5) / (df + 0.5))
            val norm = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * (length / index.averageLength))
            score += idf * (tf * (BM25_K1 + 1.0)) / norm
        }
        return score
    }
}
