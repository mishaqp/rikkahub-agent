package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.search.extract.ExtractMode
import me.rerere.search.extract.QueryFocusedExtractor
import me.rerere.search.extract.WebSource
import me.rerere.search.extract.WebSourceId
import me.rerere.search.extract.WebSourceOrigin
import me.rerere.search.extract.webSourceCache

/**
 * Hard cap on the visible text one browser research snapshot will hold.
 *
 * The browser read tools clamp what they *return* at 64 KB, but a research source is different:
 * ranking has to see the whole rendered page, or a passage the first window cut off can never be
 * found - the same reason `web_fetch` ranks the un-windowed body. 128 KiB doubles that output
 * ceiling and stays finite: the text is bounded before it ever crosses the JS bridge, and 24
 * snapshots of 128 KiB come to 3 MiB, inside the shared cache's own 4 MiB budget.
 */
internal const val BROWSER_RESEARCH_MAX_CHARS = 128 * 1024

/** Wire marker distinguishing a rendered snapshot from an HTTP fetch in a tool envelope. */
internal const val SOURCE_KIND_BROWSER = "browser"

/** The extract_mode value a Readability read reports. */
internal const val MODE_READABILITY = "readability"

/** Whether a read covered the whole rendered page or one explicitly scoped subtree. */
internal enum class RenderedScope { FULL_PAGE, SELECTOR }

/**
 * What a browser read produced. [text] is the rendered text bounded by
 * [BROWSER_RESEARCH_MAX_CHARS] and *not* clipped to the caller's `max_chars`, so one read can
 * answer with a window, feed ranking, and be cached whole.
 */
internal data class RenderedPage(
    val url: String?,
    val title: String?,
    val text: String,
    val extractMode: String,
    val scope: RenderedScope,
    val readTruncated: Boolean = false,
)

/** Outcome of reading the live page. */
internal sealed interface RenderedRead {
    data class Ok(val page: RenderedPage) : RenderedRead

    /** The page was reachable but the read failed inside it (bad selector, no article, JS error). */
    data class Failure(
        val code: String,
        val detail: String? = null,
        val recovery: String? = null,
    ) : RenderedRead

    /** No browser is bound. The caller must report this, never open one on its own. */
    data object NotOpen : RenderedRead
}

/** What the tool asked the page for. */
internal data class ReadRequest(val selector: String?, val mode: String)

/**
 * Reads the currently rendered page. The production implementation drives the live WebView; the
 * seam exists so the ranking and caching behaviour below is testable without a device, and so a
 * test can prove that reuse never reads the page a second time.
 */
internal fun interface RenderedPageReader {
    suspend fun read(request: ReadRequest): RenderedRead
}

/**
 * Last line of defence for what a browser snapshot may contain.
 *
 * The page JS already reads *rendered* text (`innerText`, or Readability's `textContent`), which
 * excludes script, style and hidden nodes by construction. This pass re-checks the markers that
 * must never reach the shared cache even if a page - or a future JS change - leaks them: script
 * and style bodies, hidden containers, form controls and their values (an attribute such as
 * `value="..."` disappears with its tag), comments, and any residual markup.
 *
 * It runs on bounded text and each step is a single linear scan, so a pathological page cannot
 * turn it into a stalling loop.
 */
internal object BrowserTextSanitizer {

    /** Void elements whose attributes (a password or hidden `value`) must never survive. */
    private val DROP_VOID = Regex(
        "(?is)<(input|embed|object|param|source|track|base|link|meta)\\b[^>]*>",
    )

    /** Any remaining tag, so raw markup cannot be stored even if a page hands us some. */
    private val TAG = Regex("<[!a-zA-Z/][^>]{0,1024}>")

    /** Comments, including the conditional-comment shapes older pages still ship. */
    private val COMMENT = Regex("(?s)<!--.*?-->")

    /** Runs of horizontal whitespace (NBSP and the zero-width family included). */
    private val H_SPACE = Regex("[ \t\u00A0\u2000-\u200D\uFEFF]+")

    /** Spaces left dangling before a newline by tag removal. */
    private val SPACE_BEFORE_NEWLINE = Regex(" +\n")

    /** More than one blank line between paragraphs. */
    private val BLANK_RUN = Regex("\n{3,}")

    /** Elements whose *content* is not page text. */
    private val DROP_OPEN = Regex(
        "<(script|style|noscript|template|iframe|svg|canvas)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /** Containers explicitly marked as not displayed. */
    private val HIDDEN_OPEN = Regex(
        "<([a-zA-Z][\\w:-]*)([^>]*(?:\\bhidden\\b|aria-hidden\\s*=\\s*[\"']?true))[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return ""

        var out = raw.replace("\r\n", "\n").replace('\r', '\n')
        out = COMMENT.replace(out, " ")
        out = dropBlocks(out, DROP_OPEN, missingCloseSwallowsRest = true)
        // An unclosed hidden container drops only its opening tag: guessing where it ends would
        // throw away the rest of the page, and innerText already excluded its content.
        out = dropBlocks(out, HIDDEN_OPEN, missingCloseSwallowsRest = false)
        out = DROP_VOID.replace(out, " ")
        out = TAG.replace(out, " ")
        // Nothing tag-shaped survives the pass above; these two characters are the last way raw
        // markup could reach the cache, so they go too.
        out = out.replace('<', ' ').replace('>', ' ')
        out = H_SPACE.replace(out, " ")
        out = SPACE_BEFORE_NEWLINE.replace(out, "\n")
        out = BLANK_RUN.replace(out, "\n\n")
        return out.trim()
    }

    /**
     * Remove every `open ... close` element, walking the string once and counting nesting so a
     * container's real close tag is found rather than the next one of the same name. An opener
     * whose closer never arrives either swallows the remainder ([missingCloseSwallowsRest]) or has
     * just its tag dropped.
     */
    private fun dropBlocks(input: String, open: Regex, missingCloseSwallowsRest: Boolean): String {
        val builder = StringBuilder(input.length)
        var cursor = 0
        while (cursor < input.length) {
            val match = open.find(input, cursor) ?: break
            builder.append(input, cursor, match.range.first)
            val name = match.groupValues[1].lowercase()
            val afterOpen = match.range.last + 1
            val afterClose = findMatchingClose(input, name, afterOpen)
            if (afterClose == null) {
                if (missingCloseSwallowsRest) return builder.toString()
                cursor = afterOpen
                continue
            }
            cursor = afterClose
        }
        builder.append(input, cursor.coerceAtMost(input.length), input.length)
        return builder.toString()
    }

    /** Index just past the close tag matching the opener that ends at [from], or null. */
    private fun findMatchingClose(input: String, name: String, from: Int): Int? {
        val pattern = Regex("(?is)</?$name(?=[\\s/>])")
        var depth = 1
        var index = from
        while (index < input.length) {
            val match = pattern.find(input, index) ?: return null
            val isClose = input[match.range.first + 1] == '/'
            val gt = input.indexOf('>', match.range.last)
            if (gt < 0) return null
            val after = gt + 1
            if (isClose) {
                depth--
                if (depth == 0) return after
            } else if (input[after - 2] != '/') {
                depth++
            }
            index = after
        }
        return null
    }
}

/** Reproduce the whitespace collapse the raw read has always applied to its answer. */
internal fun collapseRenderedWhitespace(text: String): String =
    text.replace(Regex("\\s+"), " ").trim()

/**
 * Store a rendered page in the shared source cache.
 *
 * Only sanitised visible text and non-sensitive metadata go in: a browser may sit on an
 * authenticated page, so cookies, storage, DOM, form values and credentials stay in the WebView.
 * Returns the new handle, or null when there is nothing worth keeping (empty text) or the read was
 * scoped to a selector - a subtree is not the page and must not masquerade as one.
 */
internal fun storeBrowserSource(
    page: RenderedPage,
    text: String,
    readTruncated: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
    store: (WebSource) -> Boolean = { webSourceCache.put(it) },
): String? {
    if (page.scope != RenderedScope.FULL_PAGE) return null
    val body = text.trim()
    if (body.isEmpty()) return null

    val source = WebSource(
        sourceId = WebSourceId.newId(),
        url = page.url.orEmpty(),
        // A rendered page is not an HTTP response: there is no status to report.
        status = null,
        mode = if (page.extractMode == MODE_READABILITY) ExtractMode.ARTICLE else ExtractMode.TEXT,
        title = page.title,
        text = body,
        bodyTruncated = readTruncated,
        storedAtMillis = nowMillis,
        origin = WebSourceOrigin.BROWSER,
    )
    return if (store(source)) source.sourceId else null
}

/**
 * Envelope for a browser text read.
 *
 *  * no `focus`: the legacy envelope (`text` / `truncated` / `extract_mode`), plus `current_url`,
 *    `title` and a `source_id` when the read covered the whole page.
 *  * `focus`: exactly the path `web_extract` uses - the whole rendered text goes through
 *    [QueryFocusedExtractor] and the answer carries the stage 1 diagnostics, so a rendered page
 *    and a fetched page are ranked by one implementation.
 *
 * A selector-scoped read keeps the legacy shape untouched and is never cached.
 */
internal fun browserTextEnvelope(
    page: RenderedPage,
    maxChars: Int,
    focus: String?,
    requestedSelector: String?,
    readTruncated: Boolean = page.readTruncated,
    nowMillis: Long = System.currentTimeMillis(),
    store: (WebSource) -> Boolean = { webSourceCache.put(it) },
): JsonObject {
    if (focus != null && requestedSelector != null) return focusSelectorConflictEnvelope()

    val researchText = BrowserTextSanitizer.sanitize(page.text)
    val sourceId = storeBrowserSource(page, researchText, readTruncated, nowMillis, store)

    if (focus != null) {
        val budget = minOf(maxChars, QueryFocusedExtractor.FOCUS_CHAR_BUDGET)
        val focused = QueryFocusedExtractor.focus(researchText, focus, budget)
        return buildJsonObject {
            put("focused", true)
            put("focus", focus)
            page.url?.let { put("current_url", it) }
            page.title?.let { put("title", it) }
            put("text", focused.text)
            put("chunks_total", focused.chunksTotal)
            put("chunks_selected", focused.chunksSelected)
            put("original_chars", focused.originalChars)
            put("returned_chars", focused.returnedChars)
            put("selection_truncated", focused.returnedChars < focused.originalChars)
            if (focused.fallbackUsed) put("focus_fallback", true)
            // A ranked selection is not a window: "truncated" reports only the bounded read.
            put("truncated", readTruncated)
            put("extract_mode", page.extractMode)
            sourceId?.let {
                put("source_id", it)
                put("source_kind", SOURCE_KIND_BROWSER)
            }
        }
    }

    val responseText = collapseRenderedWhitespace(page.text)
    val clipped =
        if (responseText.length <= maxChars) responseText else responseText.substring(0, maxChars)
    return buildJsonObject {
        put("text", clipped)
        put("truncated", readTruncated || clipped.length < responseText.length)
        put("extract_mode", page.extractMode)
        if (page.scope == RenderedScope.FULL_PAGE) {
            page.url?.let { put("current_url", it) }
            page.title?.let { put("title", it) }
            sourceId?.let {
                put("source_id", it)
                put("source_kind", SOURCE_KIND_BROWSER)
            }
        }
    }
}

/** `focus` ranks the whole page, so it cannot be combined with a selector-scoped read. */
internal fun focusSelectorConflictEnvelope(): JsonObject = buildJsonObject {
    put("error", "focus_selector_conflict")
    put(
        "detail",
        "focus ranks the whole rendered page, so it cannot be combined with a selector-scoped " +
            "read.",
    )
    put(
        "recovery",
        "Drop selector to rank the page, or drop focus and keep the selector for a targeted read.",
    )
}

/**
 * The entire browser text path, minus the WebView: argument handling, reader dispatch, the tool
 * timeout and the envelope. It lives here, free of Android types, so the behaviour can be tested
 * without a device; the tool factory is a thin wrapper that supplies the WebView-backed reader and
 * the not-open envelope.
 *
 * Cancellation is deliberately not caught: `withTimeoutOrNull` reports its own timeout, and any
 * other cancellation (or failure) inside the reader propagates to the caller unchanged.
 */
internal suspend fun runBrowserTextRead(
    input: kotlinx.serialization.json.JsonElement,
    reader: RenderedPageReader,
    timeoutMs: Long,
    defaultMaxChars: Int = BROWSER_GET_TEXT_DEFAULT_MAX_CHARS,
    notOpen: () -> JsonObject,
    nowMillis: Long = System.currentTimeMillis(),
    store: (WebSource) -> Boolean = { webSourceCache.put(it) },
): JsonObject {
    val obj = input as? JsonObject ?: return missingInputEnvelope()
    val explicitSelector = obj["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val maxChars = (obj["max_chars"]?.jsonPrimitive?.intOrNull ?: defaultMaxChars)
        .coerceIn(BROWSER_GET_TEXT_MIN_CHARS, BROWSER_GET_TEXT_MAX_CHARS)
    val mode = obj["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
        ?.takeIf { it in BROWSER_GET_TEXT_MODES } ?: "auto"
    val focus = obj["focus"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    if (focus != null && explicitSelector != null) return focusSelectorConflictEnvelope()

    val read = withTimeoutOrNull(timeoutMs) {
        reader.read(ReadRequest(selector = explicitSelector, mode = mode))
    } ?: return buildJsonObject {
        put("error", "tool_timeout")
        put("tool", "browser_get_text")
        put("recovery", "The browser tool exceeded its ${timeoutMs}-ms budget. Retry, or simplify the selector.")
    }

    return when (read) {
        is RenderedRead.NotOpen -> notOpen()
        is RenderedRead.Failure -> buildJsonObject {
            put("error", read.code)
            read.detail?.let { put("detail", it) }
            read.recovery?.let { put("recovery", it) }
        }
        is RenderedRead.Ok -> browserTextEnvelope(
            page = read.page,
            maxChars = maxChars,
            focus = focus,
            requestedSelector = explicitSelector,
            nowMillis = nowMillis,
            store = store,
        )
    }
}

/** Default / bounds for browser_get_text's answer window, matching the long-standing values. */
internal const val BROWSER_GET_TEXT_DEFAULT_MAX_CHARS = 8000
internal const val BROWSER_GET_TEXT_MIN_CHARS = 100
internal const val BROWSER_GET_TEXT_MAX_CHARS = 64 * 1024

/** The extract_mode values browser_get_text accepts. */
internal val BROWSER_GET_TEXT_MODES = setOf("auto", "readability", "raw")

private fun missingInputEnvelope(): JsonObject = buildJsonObject {
    put("error", "bad_request")
    put("detail", "browser_get_text expects a JSON object of arguments.")
}
