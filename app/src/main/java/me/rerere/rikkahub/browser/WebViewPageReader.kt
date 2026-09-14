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
import me.rerere.rikkahub.browser.ReadabilityRunner.runReadability

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

        val page = when (request.mode) {
            "readability" -> {
                val article = webView.runReadability()
                if (article.isNullOrEmpty()) {
                    return RenderedRead.Failure(
                        code = "readability_failed",
                        recovery = "Try extract_mode:'auto' or pass a specific selector",
                    )
                }
                pageFrom(url, title, article, MODE_READABILITY)
            }
            "raw" -> {
                val element = readElement(webView, "body")
                element.error?.let { return RenderedRead.Failure(it, element.detail) }
                RenderedPage(
                    url = url,
                    title = title,
                    text = element.text,
                    extractMode = "raw",
                    scope = RenderedScope.FULL_PAGE,
                    readTruncated = element.truncated,
                )
            }
            else -> {
                val article = webView.runReadability()
                if (!article.isNullOrEmpty() && article.length >= READABILITY_MIN_CHARS) {
                    pageFrom(url, title, article, MODE_READABILITY)
                } else {
                    val element = readElement(webView, "body")
                    element.error?.let { return RenderedRead.Failure(it, element.detail) }
                    RenderedPage(
                        url = url,
                        title = title,
                        text = element.text,
                        extractMode = "raw_fallback",
                        scope = RenderedScope.FULL_PAGE,
                        readTruncated = element.truncated,
                    )
                }
            }
        }

        return RenderedRead.Ok(attachResearchCorpus(webView, page))
    }

    /**
     * Attach the semantic corpus that ranking, caching and source reuse are allowed to use, without
     * changing the visible/Readability answer.
     *
     * A successful, non-empty semantic read is authoritative no matter how short it is. Length is
     * not a proxy for trust: the legacy text comes from Readability, which walks `textContent` and
     * therefore sees content the engine never rendered (CSS-hidden UI, canvas fallbacks, inert or
     * aria-hidden subtrees). Keeping whichever string happened to be longer is exactly how that
     * hidden text used to become the research corpus.
     *
     * When the semantic read fails or comes back empty the legacy text is explicitly *not* promoted
     * (see [RenderedPage.researchUnavailable]): the envelope then publishes no `source_id` and
     * refuses `focus` instead of caching or ranking text this stage cannot vouch for.
     */
    private suspend fun attachResearchCorpus(webView: WebView, page: RenderedPage): RenderedPage {
        val research = readResearchCorpus(webView)
        if (research.error != null || research.text.isBlank()) {
            return page.copy(researchUnavailable = true)
        }
        return page.copy(
            researchText = research.text,
            researchTruncated = research.truncated,
            researchUnavailable = false,
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
     * own, smaller `max_chars` window afterwards. Paragraph breaks are preserved for the legacy
     * answer and for the fallback research corpus.
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

        return evaluateTextRead(webView, js)
    }

    /**
     * Build a research corpus from semantic page content already present in the DOM.
     *
     * Unlike `innerText`, this read-only traversal includes semantic sections collapsed only by a
     * stylesheet (the mobile Wikipedia/Minerva case). Computed style still rejects arbitrary hidden
     * UI: the exception is limited to section containers and a heading's content container inside a
     * section. It never serialises DOM or form values, and executable, embedded, navigation, form
     * and explicitly hidden subtrees are skipped before text crosses the bridge.
     */
    private suspend fun readResearchCorpus(webView: WebView): ElementRead {
        val cap = BROWSER_RESEARCH_MAX_CHARS
        val js = """(function(){
            try {
                var root = document.querySelector('main') ||
                    document.querySelector('[role="main"]') ||
                    document.querySelector('article') ||
                    document.body ||
                    document.documentElement;
                if (!root) return JSON.stringify({error:'research_root_not_found'});

                var forbiddenTags = {
                    SCRIPT:1, STYLE:1, NOSCRIPT:1, TEMPLATE:1, IFRAME:1, SVG:1, CANVAS:1,
                    FORM:1, INPUT:1, TEXTAREA:1, SELECT:1, OPTION:1, BUTTON:1, DATALIST:1,
                    OBJECT:1, EMBED:1, APPLET:1, AUDIO:1, VIDEO:1, PICTURE:1, MAP:1, AREA:1,
                    NAV:1, ASIDE:1, DIALOG:1
                };
                var forbiddenRoles = {
                    navigation:1, dialog:1, alert:1, alertdialog:1, menu:1, menubar:1,
                    toolbar:1, search:1, complementary:1, banner:1, contentinfo:1
                };
                var blockTags = {
                    ADDRESS:1, ARTICLE:1, BLOCKQUOTE:1, DD:1, DIV:1, DL:1, DT:1,
                    FIGCAPTION:1, FIGURE:1, H1:1, H2:1, H3:1, H4:1, H5:1, H6:1,
                    HR:1, LI:1, MAIN:1, OL:1, P:1, PRE:1, SECTION:1, TABLE:1,
                    TBODY:1, TD:1, TFOOT:1, TH:1, THEAD:1, TR:1, UL:1
                };
                var pieces = [];
                var remaining = ($cap * 4) + 1;
                var stopped = false;

                // Element names are compared case-insensitively and namespace-aware. HTML reports an
                // uppercase `tagName`, while SVG (and MathML) elements report a lowercase one, so a
                // raw `tagName` lookup lets `<svg>` slip past a table keyed by "SVG" and its <text>
                // children end up in the corpus. `localName` carries the authored name for foreign
                // namespaces, which is why `localName || tagName`, upper-cased, is the only reliable
                // way to compare a node against the tables below.
                function normalizedTag(node) {
                    if (!node) return '';
                    var name = node.localName || node.tagName || node.nodeName || '';
                    return String(name).toUpperCase();
                }

                function isHeading(node) {
                    if (!node || node.nodeType !== 1) return false;
                    var tag = normalizedTag(node);
                    if (tag.length === 2 && tag.charAt(0) === 'H' &&
                        tag.charAt(1) >= '1' && tag.charAt(1) <= '6') return true;
                    if ((node.getAttribute('role') || '').trim().toLowerCase() === 'heading') return true;
                    var classes = ' ' + (node.getAttribute('class') || '')
                        .replace(/\s+/g, ' ').trim() + ' ';
                    return classes.indexOf(' mw-heading ') >= 0;
                }

                function isCollapsedSemanticContainer(node, tag, role) {
                    if (tag === 'SECTION' || tag === 'DETAILS' || role === 'region') return true;
                    var parent = node.parentElement;
                    return tag === 'DIV' && parent && normalizedTag(parent) === 'SECTION' &&
                        isHeading(node.previousElementSibling);
                }

                function appendValue(value) {
                    if (!value) return;
                    if (remaining <= 0) { stopped = true; return; }
                    if (value.length > remaining) {
                        pieces.push(value.substring(0, remaining));
                        remaining = 0;
                        stopped = true;
                    } else {
                        pieces.push(value);
                        remaining -= value.length;
                    }
                }

                function visit(node) {
                    if (stopped || !node) return;
                    if (node.nodeType === 3) {
                        appendValue(node.nodeValue || '');
                        return;
                    }
                    if (node.nodeType !== 1) return;

                    var tag = normalizedTag(node);
                    if (forbiddenTags[tag]) return;
                    var ariaHidden = (node.getAttribute('aria-hidden') || '').trim().toLowerCase();
                    if (node.hidden || node.hasAttribute('inert') || ariaHidden === 'true') return;
                    var role = (node.getAttribute('role') || '').trim().toLowerCase();
                    if (forbiddenRoles[role]) return;
                    var styleText = (node.getAttribute('style') || '').replace(/\s+/g, '').toLowerCase();
                    if (styleText.indexOf('display:none') >= 0 ||
                        styleText.indexOf('visibility:hidden') >= 0 ||
                        styleText.indexOf('content-visibility:hidden') >= 0) return;

                    var computed = null;
                    try {
                        computed = window.getComputedStyle ? window.getComputedStyle(node) : null;
                    } catch (ignored) {
                        computed = null;
                    }
                    if (computed) {
                        if (computed.visibility === 'hidden' || computed.visibility === 'collapse' ||
                            computed.contentVisibility === 'hidden') return;
                        if (computed.display === 'none' &&
                            !isCollapsedSemanticContainer(node, tag, role)) return;
                    }

                    if (tag === 'BR') {
                        appendValue('\n');
                        return;
                    }
                    var block = !!blockTags[tag];
                    if (block) appendValue('\n\n');
                    for (var child = node.firstChild; child; child = child.nextSibling) visit(child);
                    if (block) appendValue('\n\n');
                }

                visit(root);
                var text = pieces.join('')
                    .replace(/\r\n?/g, '\n')
                    .replace(/[ \t\u00a0\u2000-\u200d\ufeff]+/g, ' ')
                    .replace(/ *\n */g, '\n')
                    .replace(/\n{3,}/g, '\n\n')
                    .trim();
                var truncated = stopped || text.length > $cap;
                if (text.length > $cap) text = text.substring(0, $cap);
                return JSON.stringify({text:text, truncated:truncated});
            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
        })()"""

        return evaluateTextRead(webView, js)
    }

    /** Decode one bounded text result returned by the JavaScript bridge. */
    private suspend fun evaluateTextRead(webView: WebView, js: String): ElementRead {
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
