package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.net.hostIsBlockedLiteral
import me.rerere.rikkahub.data.ai.net.withEgressGuard
import me.rerere.search.extract.ExtractMode
import me.rerere.search.extract.ExtractedPage
import me.rerere.search.extract.QueryFocusedExtractor
import me.rerere.search.extract.WebExtractor
import me.rerere.search.extract.WebSource
import me.rerere.search.extract.WebSourceId
import me.rerere.search.extract.webSourceCache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

private const val WEB_FETCH_TIMEOUT_MS = 30_000L
internal const val WEB_FETCH_BODY_CAP = 8 * 1024  // 8 KB

/** Cap for extracted prose. Higher than the raw cap because prose is all signal. */
internal const val WEB_FETCH_EXTRACT_CAP = 32 * 1024

internal enum class FetchExtract { RAW, ARTICLE, TEXT, LINKS, METADATA }

internal fun parseExtractModeOrNull(raw: String?): FetchExtract? = when (raw?.trim()?.lowercase()) {
    null, "", "raw" -> FetchExtract.RAW
    "article" -> FetchExtract.ARTICLE
    "text" -> FetchExtract.TEXT
    "links" -> FetchExtract.LINKS
    "metadata" -> FetchExtract.METADATA
    else -> null
}

internal fun parseExtractMode(raw: String?): FetchExtract =
    parseExtractModeOrNull(raw) ?: FetchExtract.RAW

private fun FetchExtract.toExtractMode(): ExtractMode = when (this) {
    FetchExtract.ARTICLE -> ExtractMode.ARTICLE
    FetchExtract.TEXT -> ExtractMode.TEXT
    FetchExtract.LINKS -> ExtractMode.LINKS
    FetchExtract.METADATA -> ExtractMode.METADATA
    FetchExtract.RAW -> ExtractMode.TEXT // unreachable; RAW never reaches the extractor
}

/** Prose modes: the only ones that carry a body worth ranking or caching. */
internal fun isProseMode(mode: FetchExtract): Boolean =
    mode == FetchExtract.ARTICLE || mode == FetchExtract.TEXT

/** Focus ranking only means something for prose; every other mode keeps its old behaviour. */
internal fun supportsFocus(mode: FetchExtract): Boolean = isProseMode(mode)

/** The optional `focus` argument, or null when absent / blank. */
internal fun parseFocus(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

/** Same, straight off a tool input object. */
internal fun parseFocus(obj: JsonObject): String? =
    parseFocus(obj["focus"]?.jsonPrimitive?.contentOrNull)

/** The optional `source_id` argument, or null when absent / blank. */
internal fun parseSourceId(obj: JsonObject): String? =
    obj["source_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Remember a page the caller just read, when the request is one this tool is willing to keep:
 * a plain GET (no body, no caller headers) of prose that came back successful with text. Only
 * [WebSource] fields are stored, so no header, cookie, credential or POST body can reach the
 * cache. Returns the identifier to hand back, or null when nothing was stored.
 */
private fun storeSource(
    url: String,
    status: Int,
    mode: FetchExtract,
    page: ExtractedPage,
    bodyTruncated: Boolean,
    cacheable: Boolean,
): String? {
    if (!cacheable || !isProseMode(mode)) return null
    if (status !in 200..299 || page.text.isBlank()) return null

    val source = WebSource(
        sourceId = WebSourceId.newId(),
        url = url,
        status = status,
        mode = mode.toExtractMode(),
        title = page.title,
        siteName = page.siteName,
        description = page.description,
        language = page.language,
        text = page.text,
        bodyTruncated = bodyTruncated,
        storedAtMillis = System.currentTimeMillis(),
    )
    return if (webSourceCache.put(source)) source.sourceId else null
}

/** The single place `focus` plus raw pagination is refused, shared by every entry point. */
private fun focusStartIndexConflictEnvelope(
    status: Int?,
    finalUrl: String,
    sourceId: String? = null,
): String = buildJsonObject {
    put("error", "focus_start_index_conflict")
    status?.let { put("status", it) }
    put("final_url", finalUrl)
    sourceId?.let { put("source_id", it) }
    put(
        "detail",
        "focus ranks and selects the most relevant passages itself, so it cannot be " +
            "combined with start_index (raw character pagination).",
    )
    put(
        "recovery",
        "Drop start_index when using focus, or drop focus and page through the text " +
            "with start_index / next_start_index.",
    )
}.toString()

/** Neither a url nor a source_id: there is nothing to read. */
internal fun missingSourceEnvelope(): String = buildJsonObject {
    put("error", "missing_source")
    put("detail", "Pass either url (to fetch a page) or source_id (to re-read a cached one).")
    put(
        "recovery",
        "Call with url=... to read a new page, or with the source_id returned by an " +
            "earlier article/text call.",
    )
}.toString()

/** Both a url and a source_id: the caller has to choose one. */
internal fun urlSourceConflictEnvelope(): String = buildJsonObject {
    put("error", "url_source_conflict")
    put("detail", "url and source_id are mutually exclusive.")
    put(
        "recovery",
        "Use url=... to fetch a page, or source_id=... to re-read a cached one, not both.",
    )
}.toString()

/** The source_id is unknown, evicted or past its TTL. */
internal fun unknownSourceEnvelope(sourceId: String): String = buildJsonObject {
    put("error", "unknown_source_id")
    put("source_id", sourceId)
    put(
        "detail",
        "No cached page with this source_id: it was never read, or it left the cache " +
            "(24 sources, 45 minutes, 4 MiB in memory).",
    )
    put("recovery", "Fetch the page again with url=... and reuse the new source_id.")
}.toString()

/**
 * Focused ARTICLE/TEXT envelope: rank the *whole* cleaned body (never a pre-windowed slice,
 * which would make anything past the first 32K characters unfindable) and return the most
 * relevant passages in document order.
 *
 * [QueryFocusedExtractor.focus] is handed `min(maxChars, FOCUS_CHAR_BUDGET)` so a caller that
 * asked for fewer characters never gets more, and the default focused answer stays close to
 * 12 KiB instead of the 32 KiB the unfocused path may return.
 *
 * A focused answer is a selection, not a window: `truncated` mirrors the body-level limit only,
 * `selection_truncated` reports that ranking returned less than the page held, and
 * `next_start_index` is never emitted here. Callers page through text with `start_index` only
 * when focus is absent.
 */
private fun buildFocusedEnvelope(
    status: Int,
    ok: Boolean,
    finalUrl: String,
    html: String,
    mode: FetchExtract,
    maxChars: Int,
    bodyTruncated: Boolean,
    headers: Map<String, String>?,
    focus: String,
    cacheable: Boolean = false,
): String {
    val page = WebExtractor.extractFullText(
        html = html,
        baseUrl = finalUrl,
        mode = mode.toExtractMode(),
    )
    return focusedEnvelope(
        status = status,
        ok = ok,
        finalUrl = finalUrl,
        page = page,
        mode = mode,
        maxChars = maxChars,
        bodyTruncated = bodyTruncated,
        headers = headers,
        focus = focus,
        cached = false,
        sourceId = storeSource(finalUrl, status, mode, page, bodyTruncated, cacheable),
    )
}

/**
 * Focused envelope over an already extracted page. The live path (HTML just fetched) and the
 * cached path (text already held) both come through here, so they rank and report identically.
 */
internal fun focusedEnvelope(
    status: Int?,
    ok: Boolean,
    finalUrl: String,
    page: ExtractedPage,
    mode: FetchExtract,
    maxChars: Int,
    bodyTruncated: Boolean,
    headers: Map<String, String>?,
    focus: String,
    cached: Boolean,
    sourceId: String?,
    sourceKind: String? = null,
): String {
    if (page.text.isBlank()) {
        return buildJsonObject {
            put("error", "empty_extraction")
            put("status", status)
            put("final_url", finalUrl)
            put("detail", "The page was fetched but no article text could be extracted from it.")
            put(
                "recovery",
                "Retry with extract_mode='raw' to inspect the markup, or open the page with " +
                    "the browser tools if it renders its content with JavaScript.",
            )
        }.toString()
    }

    val focused = QueryFocusedExtractor.focus(
        text = page.text,
        query = focus,
        charBudget = minOf(maxChars, QueryFocusedExtractor.FOCUS_CHAR_BUDGET),
    )

    return buildJsonObject {
        // A rendered browser snapshot has no HTTP status; omitting it is honest, a fake 200 is not.
        status?.let { put("status", it) }
        put("ok", ok)
        put("final_url", finalUrl)
        put("extract_mode", mode.name.lowercase())
        sourceId?.let { put("source_id", it) }
        sourceKind?.let { put("source_kind", it) }
        if (cached) put("cached", true)
        page.title?.let { put("title", it) }
        page.siteName?.let { put("site_name", it) }
        page.description?.let { put("description", it) }
        page.language?.let { put("language", it) }
        put("text", focused.text)
        put("focused", true)
        put("focus", focus)
        put("chunks_total", focused.chunksTotal)
        put("chunks_selected", focused.chunksSelected)
        put("original_chars", focused.originalChars)
        put("returned_chars", focused.returnedChars)
        if (focused.fallbackUsed) put("focus_fallback", true)
        // A ranked selection is not a character window, and the two reasons there may be "more
        // text" are reported separately so one can never be mistaken for the other: "truncated"
        // keeps its original meaning (the fetch/body itself was cut short), while
        // "selection_truncated" says ranking deliberately returned only part of the extracted
        // text. next_start_index is deliberately absent: ranked passages are not resumed through
        // start_index, and sending both is rejected as focus_start_index_conflict.
        put("truncated", bodyTruncated)
        put("selection_truncated", focused.returnedChars < focused.originalChars)
        put("body_truncated", bodyTruncated)
        headers?.let { h -> put("headers", buildJsonObject { h.forEach { (k, v) -> put(k, v) } }) }
    }.toString()
}

/**
 * Answer a `source_id` call out of the in-memory cache. No request is made and no HTML is
 * re-parsed: without focus the cached text is windowed exactly like a fresh extraction, with
 * focus it goes through the same BM25 selection. `extract_mode` reports what the cached source
 * actually holds, since a stored page is always extracted prose.
 */
internal fun buildCachedEnvelope(
    source: WebSource,
    maxChars: Int,
    startIndex: Int,
    focus: String?,
): String {
    val effectiveFocus = parseFocus(focus)

    if (effectiveFocus != null && startIndex != 0) {
        return focusStartIndexConflictEnvelope(
            status = source.status,
            finalUrl = source.url,
            sourceId = source.sourceId,
        )
    }

    if (effectiveFocus != null) {
        val mode = source.mode.toFetchExtract() ?: FetchExtract.ARTICLE
        return focusedEnvelope(
            status = source.status,
            ok = true,
            finalUrl = source.url,
            page = ExtractedPage(
                title = source.title,
                siteName = source.siteName,
                description = source.description,
                language = source.language,
                text = source.text,
            ),
            mode = mode,
            maxChars = maxChars,
            bodyTruncated = source.bodyTruncated,
            headers = null,
            focus = effectiveFocus,
            cached = true,
            sourceId = source.sourceId,
            sourceKind = source.origin.wireName,
        )
    }

    val window = WebExtractor.sliceWindow(source.text, maxChars, startIndex)
    return buildJsonObject {
        source.status?.let { put("status", it) }
        put("ok", true)
        put("final_url", source.url)
        put("extract_mode", source.mode.name.lowercase())
        put("source_kind", source.origin.wireName)
        put("source_id", source.sourceId)
        put("cached", true)
        source.title?.let { put("title", it) }
        source.siteName?.let { put("site_name", it) }
        source.description?.let { put("description", it) }
        source.language?.let { put("language", it) }
        put("text", window.text)
        put("truncated", window.truncated || source.bodyTruncated)
        put("body_truncated", source.bodyTruncated)
        window.nextStartIndex?.let { put("next_start_index", it) }
    }.toString()
}

/** Map a stored [ExtractMode] onto the tool-level enum; null for modes a source never holds. */
private fun ExtractMode.toFetchExtract(): FetchExtract? = when (this) {
    ExtractMode.ARTICLE -> FetchExtract.ARTICLE
    ExtractMode.TEXT -> FetchExtract.TEXT
    ExtractMode.LINKS -> null
    ExtractMode.METADATA -> null
}

/**
 * Build the response envelope for an extraction-mode fetch. An extraction that yields no
 * text is an error, not a 200 with an empty string: a silent empty body is exactly how a
 * caller ends up believing it read a page it never read.
 *
 * [bodyTruncated] means the raw HTML itself hit the read cap before it was fully read; it
 * forces `truncated` true (and surfaces `body_truncated`) so a partial read is never
 * reported as a complete one, even when the extracted-text window was not exhausted.
 *
 * [focus] is the optional query behind `web_fetch(extract_mode='article'|'text', focus=...)`.
 * When it is present the whole cleaned body is ranked (see [buildFocusedEnvelope]); when it is
 * absent or blank the behaviour is exactly what it was before focus existed.
 */
internal fun buildExtractEnvelope(
    status: Int,
    ok: Boolean,
    finalUrl: String,
    html: String,
    contentType: String?,
    mode: FetchExtract,
    maxChars: Int,
    startIndex: Int,
    bodyTruncated: Boolean,
    headers: Map<String, String>?,
    focus: String? = null,
    cacheable: Boolean = false,
): String {
    val effectiveFocus = if (supportsFocus(mode)) parseFocus(focus) else null

    // focus picks its own passages, so raw character pagination is meaningless next to it:
    // refuse the combination instead of silently ranking a slice.
    if (effectiveFocus != null && startIndex != 0) {
        return focusStartIndexConflictEnvelope(status = status, finalUrl = finalUrl)
    }

    if (effectiveFocus != null) {
        return buildFocusedEnvelope(
            status = status,
            ok = ok,
            finalUrl = finalUrl,
            html = html,
            mode = mode,
            maxChars = maxChars,
            bodyTruncated = bodyTruncated,
            headers = headers,
            focus = effectiveFocus,
            cacheable = cacheable,
        )
    }

    // Extract the whole cleaned body first, for two reasons: the cache has to hold the entire
    // text (a window would make a later source_id read start mid-page), and the window below is
    // then taken from that same text rather than from a separate parse.
    val full = WebExtractor.extractFullText(
        html = html,
        baseUrl = finalUrl,
        mode = mode.toExtractMode(),
    )
    val page = when (mode) {
        // Links and metadata are not prose and were never windowed.
        FetchExtract.LINKS, FetchExtract.METADATA -> full
        else -> {
            val window = WebExtractor.sliceWindow(full.text, maxChars, startIndex)
            full.copy(
                text = window.text,
                truncated = window.truncated,
                nextStartIndex = window.nextStartIndex,
            )
        }
    }
    val sourceId = storeSource(finalUrl, status, mode, full, bodyTruncated, cacheable)

    val nothingUseful = mode != FetchExtract.METADATA &&
        mode != FetchExtract.LINKS &&
        page.text.isBlank() &&
        startIndex == 0

    if (nothingUseful) {
        return buildJsonObject {
            put("error", "empty_extraction")
            put("status", status)
            put("final_url", finalUrl)
            put(
                "detail",
                "The page was fetched but no article text could be extracted from it.",
            )
            put(
                "recovery",
                "Retry with extract_mode='raw' to inspect the markup, or open the page with " +
                    "the browser tools if it renders its content with JavaScript.",
            )
        }.toString()
    }

    return buildJsonObject {
        put("status", status)
        put("ok", ok)
        put("final_url", finalUrl)
        put("extract_mode", mode.name.lowercase())
        sourceId?.let { put("source_id", it) }
        page.title?.let { put("title", it) }
        page.siteName?.let { put("site_name", it) }
        page.description?.let { put("description", it) }
        page.language?.let { put("language", it) }
        if (mode == FetchExtract.LINKS) {
            put("links", buildJsonArray {
                page.links.forEach { link ->
                    add(buildJsonObject {
                        put("href", link.href)
                        put("text", link.text)
                    })
                }
            })
        } else {
            put("text", page.text)
        }
        put("truncated", page.truncated || bodyTruncated)
        put("body_truncated", bodyTruncated)
        page.nextStartIndex?.let { put("next_start_index", it) }
        headers?.let { h ->
            put("headers", buildJsonObject { h.forEach { (k, v) -> put(k, v) } })
        }
    }.toString()
}

/**
 * Lightweight HTTP GET/POST tool so workflows / the LLM can fetch a URL without driving the
 * full in-app browser or shelling out to Termux+curl. Backed by the DI [OkHttpClient]
 * singleton (already NetworkChangeMonitor-registered), rebuilt per call with [withEgressGuard]
 * so private / loopback / link-local targets are refused, whether named by hostname or IP
 * literal, on every redirect hop. 30 s hard timeout via [withTimeoutOrNull]. Optionally
 * extracts readable content instead of raw body.
 */
fun webFetchTool(client: OkHttpClient): Tool = Tool(
    name = "web_fetch",
    description = """
        Fetch a URL over HTTP(S) and optionally extract readable content from it.
        extract_mode: 'raw' (default, unprocessed body), 'article' (main prose, use this to
        read a page), 'text' (all body text), 'links', or 'metadata'. Raw returns markup that
        is mostly not content, so pass 'article' when you want to read a page. max_chars caps
        the returned text (default 32768 when extracting, 8192 for raw); when truncated=true
        pass next_start_index back as start_index to continue. method is GET (default) or
        POST. Response headers are omitted unless include_headers=true. Private, loopback and
        link-local addresses are refused. source_id re-reads a page an earlier article/text call
        already extracted - including a browser_get_text snapshot of a rendered page - entirely
        from memory, without a second request and without touching the browser; prefer it over
        re-reading a page in the browser. It is mutually exclusive with url. focus is an optional
        query that returns only the most
        relevant article/text passages (article/text modes) instead of the whole page. Without
        focus, truncated=true together with next_start_index means ordinary character pagination
        and you continue by passing start_index back; a focused result is never continued that
        way - its ranked passages are not resumable, so read chunks_total, chunks_selected,
        original_chars, returned_chars and selection_truncated instead, and combining focus with
        start_index is rejected. Returns {status, ok, final_url, extract_mode, title, text,
        truncated, next_start_index} or {error, detail, recovery}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The http:// or https:// URL to fetch")
                })
                put("extract_mode", buildJsonObject {
                    put("type", "string")
                    put("description", "raw (default), article (main prose, use to read a page), text, links, or metadata")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum characters of text to return")
                })
                put("start_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Resume offset; pass next_start_index from a truncated result")
                })
                put("include_headers", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Include HTTP response headers (default false)")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put("description", "GET (default) or POST")
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("description", "Optional request headers as a name->value object")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional request body string (POST only)")
                })
                put("focus", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional query used to return only the most relevant article/text " +
                            "passages (article/text modes only)",
                    )
                })
                put("source_id", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Re-read a page an earlier article/text call returned, without another " +
                            "request. Mutually exclusive with url",
                    )
                })
            },
            // Either url or source_id names the page, so neither can be required here; the
            // runtime checks below report missing_source / url_source_conflict.
            required = emptyList(),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
        val sourceId = parseSourceId(obj)

        if (url.isNullOrBlank() && sourceId == null) {
            return@Tool fmTextPart(missingSourceEnvelope())
        }
        if (!url.isNullOrBlank() && sourceId != null) {
            return@Tool fmTextPart(urlSourceConflictEnvelope())
        }
        if (url.isNullOrBlank()) {
            // source_id: served entirely from the in-memory cache - no request, no parsing.
            val source = webSourceCache.get(sourceId!!)
                ?: return@Tool fmTextPart(unknownSourceEnvelope(sourceId))
            val cachedStart = obj["start_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val cachedMax = obj["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?.coerceIn(1, WEB_FETCH_EXTRACT_CAP) ?: WEB_FETCH_EXTRACT_CAP
            return@Tool fmTextPart(
                buildCachedEnvelope(
                    source = source,
                    maxChars = cachedMax,
                    startIndex = cachedStart,
                    focus = parseFocus(obj),
                ),
            )
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_url")
                    put("detail", "url must start with http:// or https://")
                    put("recovery", "Pass an absolute http(s) URL.")
                }.toString()
            )
        }
        // OkHttp routes a literal-IP host straight to the socket without consulting the
        // GuardedDns wrapper, so refuse a private/loopback/link-local literal deterministically
        // here; the interceptor in withEgressGuard still covers literal redirect hops.
        url.toHttpUrlOrNull()?.host?.let { host ->
            if (hostIsBlockedLiteral(host)) {
                return@Tool fmTextPart(
                    buildJsonObject {
                        put("error", "blocked_address")
                        put("detail", "blocked_private_address: $host")
                        put(
                            "recovery",
                            "This tool refuses private, loopback and link-local addresses. Use a public URL.",
                        )
                    }.toString()
                )
            }
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: "GET"
        if (method != "GET" && method != "POST") {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_method")
                    put("detail", "method must be GET or POST, got $method")
                    put("recovery", "Use method=GET or method=POST.")
                }.toString()
            )
        }
        val bodyStr = obj["body"]?.jsonPrimitive?.contentOrNull

        val modeRaw = obj["extract_mode"]?.jsonPrimitive?.contentOrNull
        val mode = parseExtractModeOrNull(modeRaw)
            ?: return@Tool fmTextPart(
                fmErrEnvelope(
                    "bad_extract_mode",
                    "extract_mode must be one of raw, article, text, links, metadata",
                ),
            )
        val includeHeaders = obj["include_headers"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val startIndex = obj["start_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val defaultCap = if (mode == FetchExtract.RAW) WEB_FETCH_BODY_CAP else WEB_FETCH_EXTRACT_CAP
        val maxChars = obj["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?.coerceIn(1, defaultCap) ?: defaultCap
        val focus = parseFocus(obj)

        // Only a plain GET of a page, with nothing caller-supplied that could be secret, is
        // worth remembering; a caller header may be an authorization token.
        val callerHeaders = obj["headers"] as? JsonObject
        val cacheable = method == "GET" && bodyStr == null &&
            (callerHeaders == null || callerHeaders.isEmpty())

        val request = try {
            val builder = Request.Builder().url(url)
            (obj["headers"] as? kotlinx.serialization.json.JsonObject)?.forEach { (name, value) ->
                value.jsonPrimitive.contentOrNull?.let { builder.header(name, it) }
            }
            if (method == "POST") {
                builder.post((bodyStr ?: "").toRequestBody())
            } else {
                builder.get()
            }
            builder.build()
        } catch (e: IllegalArgumentException) {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_request")
                    put("detail", e.message ?: "Could not build request")
                    put("recovery", "Check the URL and header names for invalid characters.")
                }.toString()
            )
        }

        // Guarded client: refuses private / loopback / link-local targets on every hop,
        // whether named by hostname or IP literal.
        val guarded = client.withEgressGuard()

        val result = withTimeoutOrNull(WEB_FETCH_TIMEOUT_MS) {
            try {
                guarded.newCall(request).execute().use { resp ->
                    // Read the body through a bounded buffer instead of resp.body.bytes(),
                    // which would pull the whole (possibly multi-GB) response into memory.
                    // Read at most CAP+1 bytes: the extra byte tells us more remained.
                    // Raw is capped tight; extraction reads much more markup than the prose it
                    // yields (roughly 8x), so it gets a larger byte budget before it truncates.
                    val (raw, bodyTruncated) = readBounded(
                        resp.body.byteStream(),
                        if (mode == FetchExtract.RAW) WEB_FETCH_BODY_CAP else WEB_FETCH_EXTRACT_CAP * 8,
                    )
                    val contentType = resp.header("Content-Type")
                    val decoded = decodeBody(raw, raw.size, contentType)
                    val headerMap = if (includeHeaders) {
                        resp.headers.associate { (n, v) -> n to v }
                    } else {
                        null
                    }

                    if (mode == FetchExtract.RAW) {
                        buildJsonObject {
                            put("status", resp.code)
                            put("ok", resp.isSuccessful)
                            put("final_url", resp.request.url.toString())
                            put("extract_mode", "raw")
                            put("body", decoded)
                            put("body_truncated", bodyTruncated)
                            headerMap?.let { h ->
                                put("headers", buildJsonObject { h.forEach { (k, v) -> put(k, v) } })
                            }
                        }.toString()
                    } else {
                        buildExtractEnvelope(
                            status = resp.code,
                            ok = resp.isSuccessful,
                            finalUrl = resp.request.url.toString(),
                            html = decoded,
                            contentType = contentType,
                            mode = mode,
                            maxChars = maxChars,
                            startIndex = startIndex,
                            bodyTruncated = bodyTruncated,
                            headers = headerMap,
                            focus = focus,
                            cacheable = cacheable,
                        )
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                // OkHttp's callTimeout (set in withEgressGuard) fires this when a call, including
                // a trickling read, runs past the advertised 30s limit; withTimeoutOrNull cannot
                // catch this itself since the blocking execute() call has no suspension point.
                buildJsonObject {
                    put("error", "timeout")
                    put("detail", "Request exceeded the 30s limit.")
                    put("recovery", "The host is slow or unreachable; try a smaller request or a different URL.")
                }.toString()
            } catch (e: IOException) {
                val blocked = e.message?.contains("blocked_private_address") == true
                buildJsonObject {
                    put("error", if (blocked) "blocked_address" else "network_error")
                    put("detail", e.message ?: e::class.java.simpleName)
                    put(
                        "recovery",
                        if (blocked) {
                            "This tool refuses private, loopback and link-local addresses. Use a public URL."
                        } else {
                            "Check connectivity and that the host is reachable, then retry."
                        },
                    )
                }.toString()
            }
        } ?: buildJsonObject {
            put("error", "timeout")
            put("detail", "Request exceeded the 30s limit.")
            put("recovery", "The host is slow or unreachable; try a smaller request or a different URL.")
        }.toString()

        fmTextPart(result)
    },
)

/**
 * Read at most [cap] bytes from [ins], plus one probe byte to detect overflow. Returns the
 * accumulated bytes (up to cap+1) and a truncated flag set when the stream had more than
 * [cap] bytes. Bounds memory regardless of Content-Length or a missing/lying one.
 */
internal fun readBounded(ins: InputStream, cap: Int): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream(minOf(cap, 8 * 1024))
    val buf = ByteArray(8192)
    // Stop once we have cap+1 bytes: the (cap+1)th byte is enough to flag truncation
    // without buffering the rest of the response.
    val limit = cap.toLong() + 1
    var total = 0L
    while (total < limit) {
        val want = minOf(buf.size.toLong(), limit - total).toInt()
        val read = ins.read(buf, 0, want)
        if (read < 0) break
        out.write(buf, 0, read)
        total += read
    }
    val bytes = out.toByteArray()
    return bytes to (bytes.size > cap)
}

private val CHARSET_RE = Regex("""charset\s*=\s*["']?([^"';\s]+)""", RegexOption.IGNORE_CASE)

/**
 * Decode the first [len] bytes of [raw] using the charset declared in [contentType],
 * falling back to UTF-8 when it is absent, malformed, or unsupported on this device.
 * Decoding everything as UTF-8 mangles every non-UTF-8 page.
 */
internal fun decodeBody(raw: ByteArray, len: Int, contentType: String?): String {
    val charset = contentType
        ?.let { CHARSET_RE.find(it)?.groupValues?.getOrNull(1) }
        ?.let { name -> runCatching { Charset.forName(name.trim()) }.getOrNull() }
        ?: Charsets.UTF_8
    return String(raw, 0, minOf(len, raw.size), charset)
}
