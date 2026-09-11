package me.rerere.search.extract

import java.security.MessageDigest
import java.util.Locale

/**
 * Offline, query-focused reduction of already-extracted page text. No Android, no network,
 * no third-party model: given prose and a free-form question, it returns the few paragraphs
 * that actually answer the question, ranked with a small BM25 scorer.
 *
 * Why this exists: reader-mode extraction hands the model an entire article when it only
 * needs a couple of paragraphs. On a long page that is most of the context window spent on
 * navigation, footers and asides. Ranking paragraphs against the caller's `focus` string
 * locally keeps the answer and drops the noise, at zero token cost and full determinism.
 *
 * The scorer is deliberately plain BM25 (k1 = 1.2, b = 0.75) over whitespace-split tokens:
 *  - ASCII words are case-folded (so Russian and English both work without a stemmer);
 *  - a short stop-word list covers the most common English and Russian particles, which
 *    would otherwise let "what is the ..." match almost every paragraph;
 *  - every returned value is a deterministic function of its inputs, so repeated calls on
 *    the same page and question produce byte-identical output.
 */
object QueryFocusedExtractor {

    /** BM25 term-frequency saturation. */
    private const val K1 = 1.2

    /** BM25 length normalisation. */
    private const val B = 0.75

    /** Blocks larger than this are split further, so one huge paragraph cannot hide a match. */
    private const val MAX_BLOCK_CHARS = 1600

    /** Blocks this short or shorter never carry an answer; they are usually headings or crumbs. */
    private const val MIN_BLOCK_CHARS = 30

    /** How many of the best blocks to keep before the char cap trims further. */
    private const val MAX_SELECTED_BLOCKS = 8

    /** Focus tokens with no real content; matching on them would make every block "relevant". */
    private val STOP_WORDS = hashSetOf(
        // English
        "the", "a", "an", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by",
        "for", "with", "is", "are", "was", "were", "be", "been", "being", "it", "its",
        "this", "that", "these", "those", "as", "from", "what", "which", "who", "whom",
        "how", "why", "when", "where", "do", "does", "did", "than", "then", "there",
        // Russian
        "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все",
        "она", "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по",
        "только", "ее", "мне", "было", "вот", "от", "меня", "еще", "нет", "о", "из",
        "ему", "теперь", "когда", "даже", "ну", "вдруг", "ли", "если", "уже", "или",
        "ни", "быть", "был", "него", "до", "вас", "нибудь", "опять", "уж", "вам", "ведь",
        "там", "потом", "себя", "ничего", "ей", "может", "они", "тут", "где", "есть",
        "надо", "ней", "для", "мы", "тебя", "их", "чем", "была", "сам", "чтоб", "без",
        "будто", "чего", "раз", "тоже", "себе", "под", "будет", "ж", "тогда", "кто",
        "этот", "того", "потому", "этого", "какой", "совсем", "ним", "здесь", "этом",
        "один", "почти", "мой", "тем", "чтобы", "нее", "сейчас", "были", "куда", "зачем",
        "всех", "никогда", "можно", "при", "наконец", "два", "об", "другой", "хоть",
        "после", "над", "больше", "тот", "через", "эти", "нас", "про", "всего", "них",
        "какая", "много", "разве", "три", "эту", "моя", "впрочем", "хорошо", "свою",
        "этой", "перед", "иногда", "лучше", "чуть", "том", "нельзя", "такой", "им",
        "более", "всегда", "конечно", "всю", "между",
    )

    /** One selected block: 0-based position in the original text and its content. */
    data class Block(val index: Int, val text: String)

    /** Result of a focus pass. [applied] is false when the pass was skipped or found nothing. */
    data class Result(
        val text: String,
        val applied: Boolean,
        val selectedBlocks: Int,
        val truncated: Boolean,
    )

    /**
     * Rank [text]'s blocks against [focus] and return the best ones in original order.
     *
     * Returns a non-applied result (and [text] unchanged when it fits) whenever:
     *  - [focus] carries no usable token (empty, punctuation only, all stop-words), or
     *  - no block scores above zero, i.e. the page simply does not mention the question.
     * The caller must treat that as "fall back to the plain reader-mode output", never as an
     * empty page.
     */
    fun focus(text: String, focus: String, maxChars: Int): Result {
        val cap = maxChars.coerceAtLeast(1)
        if (text.isBlank() || focus.isBlank()) return plain(text, cap)

        val queryTokens = tokenize(focus).filter { it !in STOP_WORDS }
        if (queryTokens.isEmpty()) return plain(text, cap)

        val blocks = splitBlocks(text)
        if (blocks.size < 2) return plain(text, cap)

        val termFreqs = blocks.map { tokenFrequency(it.text) }
        val lengths = termFreqs.map { freq -> freq.values.sum().toDouble() }
        val avgLen = lengths.average().takeIf { it > 0.0 } ?: 1.0
        val docFreq = HashMap<String, Int>()
        for (freq in termFreqs) {
            for (term in freq.keys) docFreq[term] = (docFreq[term] ?: 0) + 1
        }

        val scores = blocks.indices.map { i ->
            val freq = termFreqs[i]
            val len = lengths[i]
            var score = 0.0
            for (term in queryTokens.toSet()) {
                val tf = (freq[term] ?: 0).toDouble()
                if (tf == 0.0) continue
                val df = (docFreq[term] ?: 0).toDouble()
                val idf = ln(1.0 + (blocks.size - df + 0.5) / (df + 0.5))
                score += idf * (tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * len / avgLen))
            }
            score
        }

        if (scores.all { it <= 0.0 }) return plain(text, cap)

        // Best blocks first, ties broken by position so the choice is deterministic.
        val ranked = blocks.indices.sortedWith(
            compareByDescending<Int> { scores[it] }.thenBy { it },
        ).take(MAX_SELECTED_BLOCKS)

        // Keep one adjacent block per hit when it also scores, so a sentence split across a
        // heading/paragraph boundary still reads as a sentence.
        val chosen = sortedSetOf<Int>()
        for (i in ranked) {
            if (scores[i] <= 0.0) continue
            chosen.add(i)
            val prev = i - 1
            if (prev in blocks.indices && scores[prev] > 0.0) chosen.add(prev)
        }
        if (chosen.isEmpty()) return plain(text, cap)

        // Original document order, de-duplicated.
        val ordered = chosen.toList()
        val seen = HashSet<String>()
        val kept = ArrayList<Block>(ordered.size)
        for (i in ordered) {
            val block = blocks[i]
            if (seen.add(normalise(block.text))) kept.add(block)
        }
        if (kept.isEmpty()) return plain(text, cap)

        val sb = StringBuilder()
        var truncated = false
        for (block in kept) {
            val piece = if (sb.isEmpty()) block.text else "\n\n" + block.text
            if (sb.length + piece.length > cap) {
                truncated = true
                break
            }
            sb.append(piece)
        }
        if (sb.isEmpty()) return plain(text, cap)

        return Result(
            text = sb.toString(),
            applied = true,
            selectedBlocks = kept.count { sb.contains(it.text) },
            truncated = truncated,
        )
    }

    /** Reader-mode output for the current window, untouched. */
    private fun plain(text: String, cap: Int): Result {
        if (text.length <= cap) return Result(text, applied = false, selectedBlocks = 0, truncated = false)
        return Result(text.substring(0, cap), applied = false, selectedBlocks = 0, truncated = true)
    }

    /**
     * Split prose into paragraph-sized blocks on blank lines, splitting any oversized block on
     * sentence boundaries. Deterministic: depends only on the input string.
     */
    private fun splitBlocks(text: String): List<Block> {
        val raw = text.split(Regex("\\n\\s*\\n"))
        val blocks = ArrayList<Block>()
        for (part in raw) {
            val trimmed = part.trim()
            if (trimmed.length < MIN_BLOCK_CHARS) continue
            if (trimmed.length <= MAX_BLOCK_CHARS) {
                blocks.add(Block(blocks.size, trimmed))
                continue
            }
            for (chunk in chunkBySentence(trimmed)) {
                if (chunk.length >= MIN_BLOCK_CHARS) blocks.add(Block(blocks.size, chunk))
            }
        }
        return blocks
    }

    /** Greedy sentence packing up to [MAX_BLOCK_CHARS]; never drops content. */
    private fun chunkBySentence(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?…])\\s+"))
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in sentences) {
            if (sb.isNotEmpty() && sb.length + 1 + sentence.length > MAX_BLOCK_CHARS) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(sentence)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Lower-cased, stop-word-free token bag for one block. */
    private fun tokenFrequency(block: String): Map<String, Int> {
        val freq = HashMap<String, Int>()
        for (token in tokenize(block)) {
            freq[token] = (freq[token] ?: 0) + 1
        }
        return freq
    }

    /**
     * Whitespace split plus a conservative trim of leading/trailing punctuation. Deliberately
     * not case-folded to ASCII ranges only via [Locale.ROOT]: Cyrillic must fold too.
     */
    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        for (piece in text.split(Regex("\\s+"))) {
            if (piece.isEmpty()) continue
            val token = piece.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
            if (token.isNotEmpty()) out.add(token)
        }
        return out
    }

    /** Collapse whitespace and fold case — the identity used for de-duplication. */
    private fun normalise(text: String): String =
        text.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun ln(x: Double): Double = kotlin.math.ln(x)
}

/**
 * Deterministic, short, stable identifier for a fetched document.
 *
 * Derived from the normalised final URL (scheme and host lower-cased, fragment and trailing
 * slash dropped, query preserved) so the same page yields the same id across runs and across
 * `web_fetch` / `web_extract`. SHA-256, truncated to 16 hex characters — short enough to keep
 * the envelope readable, wide enough that collisions are not a practical concern here.
 *
 * There was no pre-existing source-id generator in the project; this is the first one, so it
 * lives next to the extractor rather than in a tool.
 */
object WebSourceId {

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
    private val MANY_SLASHES = Regex("/+$")

    fun of(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalise(url).toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val v = byte.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return "web-" + hex.substring(0, 16)
    }

    /** Host portion of [url], lower-cased; empty when the URL has no host. */
    fun host(url: String): String = try {
        java.net.URI(url).host?.lowercase(Locale.ROOT).orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun normalise(url: String): String {
        var out = url.trim()
        // Drop the fragment: two anchors into the same page are the same document.
        val hash = out.indexOf('#')
        if (hash >= 0) out = out.substring(0, hash)
        val match = SCHEME.find(out)
        if (match != null) {
            val scheme = match.groupValues[1].lowercase(Locale.ROOT)
            var rest = out.substring(match.value.length)
            val slash = rest.indexOf('/')
            val hostPart = if (slash >= 0) rest.substring(0, slash) else rest
            val pathPart = if (slash >= 0) rest.substring(slash) else ""
            rest = hostPart.lowercase(Locale.ROOT) + pathPart
            out = "$scheme://$rest"
        }
        return MANY_SLASHES.replace(out, "")
    }

    private const val HEX = "0123456789abcdef"
}
