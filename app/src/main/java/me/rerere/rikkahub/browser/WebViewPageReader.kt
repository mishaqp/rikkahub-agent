package me.rerere.rikkahub.browser

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.local.BROWSER_RESEARCH_MAX_CHARS
import me.rerere.rikkahub.data.ai.tools.local.MODE_READABILITY
import me.rerere.rikkahub.data.ai.tools.local.ReadRequest
import me.rerere.rikkahub.data.ai.tools.local.RenderedPage
import me.rerere.rikkahub.data.ai.tools.local.RenderedPageReader
import me.rerere.rikkahub.data.ai.tools.local.RenderedRead
import me.rerere.rikkahub.data.ai.tools.local.RenderedScope

/**
 * Reads the currently rendered page out of the live WebView for the web research engine.
 *
 * It is deliberately passive: the only thing it will touch is a WebView that [BrowserController]
 * already has bound. There is no code path here that opens [BrowserActivity] or allocates a
 * session, so asking for a snapshot can never pop a browser onto the user's screen - the tool
 * reports `browser_not_open` instead.
 *
 * Foreground and headless sessions are served by the same call, because both publish their WebView
 * through [BrowserController.activeWebView]; headless mode is not special-cased here, and the
 * existing headless-vs-foreground decision elsewhere is untouched.
 */
internal object WebViewPageReader : RenderedPageReader {

    /**
     * Below this a Readability extraction is treated as junk and the visible body text is used
     * instead. Same threshold the read tool has always applied.
     */
    private const val READABILITY_MIN_CHARS = 200

    /** Outcome of one element read. */
    private class ElementRead(
        val text: String = "",
        val truncated: Boolean = false,
        val error: String? = null,
        val detail: String? = null,
    )

    override suspend fun read(request: ReadRequest): RenderedRead {
        val webView = BrowserController.activeWebView() ?: return RenderedRead.NotOpen
        return withContext(Dispatchers.Main) { readBound(webView, request) }
    }

    private suspend fun readBound(webView: WebView, request: ReadRequest): RenderedRead {
        // url/title are main-thread reads; read() already hopped to Main.
        val url = runCatching { webView.url }.getOrNull()
        val title = runCatching { webView.title }.getOrNull()

        // An explicit selector is a targeted read: it is never a whole-page research source.
        if (request.selector != null) {
            val element = readElement(webView, request.selector)
            element.error?.let { return RenderedRead.Failure(it, element.detail) }
            return RenderedRead.Ok(
                RenderedPage(
                    url = url,
                    title = title,
                    text = element.text,
                    extractMode = "raw",
                    scope = RenderedScope.SELECTOR,
                    readTruncated = element.truncated,
                ),
            )
        }

        when (request.mode) {
            "readability" -> {
                val article = webView.runReadability()
                if (article.isNullOrEmpty()) {
                    return RenderedRead.Failure(
                        code = "readability_failed",
                        recovery = "Try extract_mode:'auto' or pass a specific selector",
                    )
                }
                return RenderedRead.Ok(pageFrom(url, title, article, MODE_READABILITY))
            }
            "raw" -> Unit // fall through to the visible-text read below
            else -> {
                val article = webView.runReadability()
                if (!article.isNullOrEmpty() && article.length >= READABILITY_MIN_CHARS) {
                    return RenderedRead.Ok(pageFrom(url, title, article, MODE_READABILITY))
                }
            }
        }

        val element = readElement(webView, "body")
        element.error?.let { return RenderedRead.Failure(it, element.detail) }
        return RenderedRead.Ok(
            RenderedPage(
                url = url,
                title = title,
                text = element.text,
                extractMode = if (request.mode == "raw") "raw" else "raw_fallback",
                scope = RenderedScope.FULL_PAGE,
                readTruncated = element.truncated,
            ),
        )
    }

    /**
     * Readability's text is not pre-bounded, so clamp it here: everything above the research cap is
     * reported as a truncated read rather than silently kept.
     */
    private fun pageFrom(url: String?, title: String?, article: String, mode: String): RenderedPage {
        val truncated = article.length > BROWSER_RESEARCH_MAX_CHARS
        return RenderedPage(
            url = url,
            title = title,
            text = if (truncated) article.substring(0, BROWSER_RESEARCH_MAX_CHARS) else article,
            extractMode = mode,
            scope = RenderedScope.FULL_PAGE,
            readTruncated = truncated,
        )
    }

    /**
     * `innerText` is the rendered text: script, style and hidden nodes are excluded by the engine
     * itself, and form values never appear because they are attributes, not text. The result is
     * bounded by [BROWSER_RESEARCH_MAX_CHARS] before it crosses the bridge; the caller applies its
     * own, smaller `max_chars` window afterwards. Paragraph breaks are preserved - ranking needs
     * them to chunk the page.
     */
    private suspend fun readElement(webView: WebView, selector: String): ElementRead {
        val cap = BROWSER_RESEARCH_MAX_CHARS
        val js = """(function(){
            try {
                var sel = ${jsLiteral(selector)};
                var el = (sel === 'body')
                    ? (document.body || document.querySelector('body'))
                    : document.querySelector(sel);
                if (!el) return JSON.stringify({error:'selector_not_found', selector: sel});
                var t = (typeof el.innerText === 'string') ? el.innerText : (el.textContent || '');
                var truncated = false;
                if (t.length > $cap) { t = t.substring(0, $cap); truncated = true; }
                return JSON.stringify({text: t, truncated: truncated});
            } catch(e) { return JSON.stringify({error:'js_failed', detail: String(e)}); }
        })()"""

        val raw = webView.evaluateJavascriptAsync(js) ?: return ElementRead(error = "js_failed")
        return runCatching {
            val outer = Json.parseToJsonElement(raw)
            val inner =
                if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty()
                else outer.toString()
            val obj = Json.parseToJsonElement(inner).jsonObject
            ElementRead(
                text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                truncated = obj["truncated"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                error = obj["error"]?.jsonPrimitive?.contentOrNull,
                detail = obj["detail"]?.jsonPrimitive?.contentOrNull,
            )
        }.getOrElse { ElementRead(error = "js_failed") }
    }

    /** JSON-encode a Kotlin string so it can be embedded as a JS string literal safely. */
    private fun jsLiteral(value: String): String = JsonPrimitive(value).toString()
}
