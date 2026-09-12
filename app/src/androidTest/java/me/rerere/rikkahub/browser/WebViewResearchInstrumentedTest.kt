package me.rerere.rikkahub.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.browserGetTextTool
import me.rerere.rikkahub.data.ai.tools.local.webExtractTool
import me.rerere.search.extract.webSourceCache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * On-device instrumentation coverage for Web Research v1 stage 3 - the browser/WebView half of the
 * research engine.
 *
 * Everything here drives the production path: a real [WebView] hosted the way
 * [HeadlessBrowserSession] hosts one (offscreen parent, manual `measure()` + `layout()`), bound
 * into [BrowserController] through [BrowserController.bindHeadless], read by the real
 * [WebViewPageReader] (i.e. `evaluateJavascript` plus [ReadabilityRunner.runReadability]) and
 * answered by the real tool factories.
 *
 * The JVM unit tests already cover argument handling, ranking and cache policy against a fake
 * `RenderedPageReader`. What they cannot cover - and what this class exists for - is the part that
 * only exists on a device:
 *
 *  * `innerText` really excludes script / style / `hidden` / non-rendered content;
 *  * a safe semantic corpus still reaches article sections collapsed only by page CSS;
 *  * `Readability.js` really loads from `assets/browser/readability.js` and parses a live DOM;
 *  * a selector with quotes, a backslash and non-ASCII characters survives the JS splice;
 *  * a snapshot really publishes a `source_id` that `web_extract` can re-read after the WebView is
 *    destroyed, without a second page read and without a single socket;
 *  * `focus` really reaches a passage that sits past the first 32 KiB of the rendered page;
 *  * the per-tool timeout really fires and caller cancellation really propagates;
 *  * no read path ever launches [BrowserActivity].
 *
 * No network is required: pages are inlined with `loadDataWithBaseURL`, and the only injected
 * transport is an interceptor that counts requests and never opens a socket.
 */
@RunWith(AndroidJUnit4::class)
class WebViewResearchInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private var session: HeadlessBrowserSession? = null
    private var savedToolTimeoutMs: Long = BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS

    @Before
    fun setUp() {
        savedToolTimeoutMs = BrowserController.perToolTimeoutMs
        webSourceCache.clear()
    }

    @After
    fun tearDown() {
        // Order matters: drop the controller binding first so a late tool dispatch fails fast with
        // browser_not_open instead of racing the WebView teardown.
        BrowserController.unbindHeadless(CONV_ID)
        BrowserController.clearTaskWindow()
        BrowserController.perToolTimeoutMs = savedToolTimeoutMs
        val s = session
        session = null
        if (s != null) onMain { s.stop() }
        webSourceCache.clear()
    }

    // ---- Reading the real rendered page -------------------------------------------------------

    @Test
    fun readableArticleIsExtractedThroughTheRealReadabilityRunner() {
        val env = withBoundPage(articlePageHtml()) { readText(buildJsonObject {}) }

        assertEquals(
            "readability should win on an article-shaped page; envelope: $env",
            "readability",
            env.str("extract_mode"),
        )
        assertTrue(
            "article prose must be returned; envelope: $env",
            env.str("text").orEmpty().contains(PROSE_MARKER),
        )
        // The envelope reads url/title off the live WebView, so both must be populated. The exact
        // URL is deliberately not compared: an inlined document (loadDataWithBaseURL) has no
        // navigation behind it and Chromium reports "about:blank" as the visible URL, because the
        // base URL only resolves relative references. The read path is still asserted end to end -
        // Readability extraction, article text, source_id and source_kind below.
        assertNotNull("the envelope must carry the live page's URL; envelope: $env", env.str("current_url"))
        assertTrue(
            "current_url must not be blank; envelope: $env",
            !env.str("current_url").orEmpty().isBlank(),
        )
        assertEquals("Instrumented research article", env.str("title"))
    }

    @Test
    fun renderedSnapshotPublishesBrowserSourceKindAndOpaqueId() {
        val env = withBoundPage(articlePageHtml()) { readText(buildJsonObject {}) }

        val id = env.str("source_id")
        assertNotNull("a whole-page read must publish a source_id; envelope: $env", id)
        assertTrue("source_id shape: $id", id!!.startsWith("src_"))
        assertFalse("the id must not leak the host", id.contains("research.invalid"))
        assertEquals("browser", env.str("source_kind"))
    }

    @Test
    fun scriptStyleHiddenAndFormContentStayOutOfTheAnswer() {
        val env = withBoundPage(articlePageHtml()) {
            readText(buildJsonObject { put("extract_mode", JsonPrimitive("raw")) })
        }
        val text = env.str("text").orEmpty()

        assertEquals("raw mode keeps the visible body; envelope: $env", "raw", env.str("extract_mode"))
        assertTrue("visible prose must survive; envelope: $env", text.contains(PROSE_MARKER))
        assertFalse("script bodies must never be read", text.contains(SCRIPT_SENTINEL))
        assertFalse("style sheets must never be read", text.contains(STYLE_SENTINEL))
        assertFalse("non-rendered content must never be read", text.contains(DISPLAY_NONE_SENTINEL))
        assertFalse("hidden content must never be read", text.contains(HIDDEN_SENTINEL))
        assertFalse("input values must never be read", text.contains(PASSWORD_SENTINEL))
        assertFalse("residual markup must not reach the answer", text.contains("<"))
    }

    @Test
    fun cssCollapsedSemanticSectionsFeedBrowserFocusAndCachedReuse() {
        val (visible, focused) = withBoundPage(collapsedArticlePageHtml()) {
            val visibleRead = readText(buildJsonObject {
                put("extract_mode", JsonPrimitive("raw"))
                put("max_chars", JsonPrimitive(8000))
            })
            val focusedRead = readText(buildJsonObject {
                put("extract_mode", JsonPrimitive("raw"))
                put("focus", JsonPrimitive(COLLAPSED_DEEP_MARKER))
                put("max_chars", JsonPrimitive(4000))
            })
            visibleRead to focusedRead
        }

        assertTrue(visible.str("text").orEmpty().contains(COLLAPSED_VISIBLE_MARKER))
        assertFalse(
            "raw keeps the legacy rendered answer; CSS-collapsed prose must stay out of it",
            visible.str("text").orEmpty().contains(COLLAPSED_DEEP_MARKER),
        )
        assertEquals("true", focused.str("focused"))
        assertNull("the deep marker must be selected, not returned by fallback", focused.str("focus_fallback"))
        assertTrue(
            "browser focus must reach the CSS-collapsed section; envelope: $focused",
            focused.str("text").orEmpty().contains(COLLAPSED_DEEP_MARKER),
        )
        assertTrue(
            "the research corpus must extend past the old visible slice; envelope: $focused",
            focused.int("original_chars") > 32 * 1024,
        )

        val sourceId = visible.str("source_id")
        assertNotNull("the raw full-page read must publish a source_id", sourceId)
        val cached = webSourceCache.get(sourceId!!)
        assertNotNull("the browser research corpus must be cached", cached)
        assertTrue(cached!!.text.contains(COLLAPSED_DEEP_MARKER))
        assertTrue("the cached corpus must include the deep section", cached.text.length > 32 * 1024)
        for (leak in listOf(
            SCRIPT_SENTINEL,
            STYLE_SENTINEL,
            DISPLAY_NONE_SENTINEL,
            HIDDEN_SENTINEL,
            PASSWORD_SENTINEL,
        )) {
            assertFalse("$leak leaked into the research source", cached.text.contains(leak))
        }

        val net = CountingTransport()
        val reused = invoke(
            webExtractTool(OkHttpClient.Builder().addInterceptor(net).build()),
            buildJsonObject {
                put("source_id", JsonPrimitive(sourceId))
                put("focus", JsonPrimitive(COLLAPSED_DEEP_MARKER))
            },
        )
        assertEquals("true", reused.str("cached"))
        assertEquals("browser", reused.str("source_kind"))
        assertTrue(reused.str("text").orEmpty().contains(COLLAPSED_DEEP_MARKER))
        assertEquals("cached reuse must not touch HTTP", 0, net.requests.get())
    }

    @Test
    fun autoFallsBackToTheVisibleBodyWhenThereIsNoArticle() {
        val env = withBoundPage(shortPageHtml()) { readText(buildJsonObject {}) }

        assertEquals(
            "Readability has too little to work with here; envelope: $env",
            "raw_fallback",
            env.str("extract_mode"),
        )
        assertTrue(env.str("text").orEmpty().contains(SHORT_PAGE_MARKER))
    }

    @Test
    fun forcedReadabilityReportsAFailureWhenThereIsNoArticleAtAll() {
        val env = withBoundPage(emptyPageHtml()) {
            readText(buildJsonObject { put("extract_mode", JsonPrimitive("readability")) })
        }

        assertEquals("readability_failed; envelope: $env", "readability_failed", env.str("error"))
        assertNotNull("the failure must carry a recovery hint", env.str("recovery"))
    }

    @Test
    fun selectorReadsSurviveHostileAndUnicodeSelectors() {
        withBoundPage(articlePageHtml()) {
            val scoped = readText(buildJsonObject { put("selector", JsonPrimitive("#hostile-target")) })
            assertNull("envelope: $scoped", scoped.str("error"))
            assertTrue(scoped.str("text").orEmpty().contains(SELECTOR_MARKER))
            assertEquals("raw", scoped.str("extract_mode"))
            assertNull(
                "a selector-scoped subtree is not the page and must not masquerade as a source",
                scoped.str("source_id"),
            )

            // A double quote and a backslash inside an attribute value: the JSON-encoded splice has
            // to keep both the CSS string and the JS string intact.
            val hostile = readText(buildJsonObject { put("selector", JsonPrimitive(HOSTILE_SELECTOR)) })
            assertNull("hostile selector must not fail; envelope: $hostile", hostile.str("error"))
            assertTrue(hostile.str("text").orEmpty().contains(HOSTILE_MARKER))

            // Non-ASCII id and attribute value.
            val unicode = readText(buildJsonObject { put("selector", JsonPrimitive("#идентификатор")) })
            assertNull("unicode selector must not fail; envelope: $unicode", unicode.str("error"))
            assertTrue(unicode.str("text").orEmpty().contains(UNICODE_MARKER))

            val missing = readText(buildJsonObject { put("selector", JsonPrimitive("#no-such-node")) })
            assertEquals("selector_not_found", missing.str("error"))
        }
    }

    @Test
    fun unboundControllerReportsBrowserNotOpenWithoutOpeningTheBrowserUi() {
        // The state a fresh process is in before any browser_open: nothing is bound.
        BrowserController.unbindHeadless(CONV_ID)
        assertFalse(BrowserController.isBound())
        assertTrue(BrowserController.currentMode() is BrowserController.Mode.Idle)

        val env = readText(buildJsonObject {})

        assertEquals("browser_not_open; envelope: $env", "browser_not_open", env.str("error"))
        assertNotNull(env.str("recovery"))
        assertFalse("a read must not bind anything", BrowserController.isBound())
        assertTrue(BrowserController.currentMode() is BrowserController.Mode.Idle)
        assertFalse(
            "a read tool must never launch the browser Activity (resumed: ${resumedActivityNames()})",
            resumedActivityNames().contains(BrowserActivity::class.java.name),
        )
    }

    // ---- Reuse, windowing and focus ------------------------------------------------------------

    @Test
    fun browserSnapshotIsReusedByWebExtractAfterTheWebViewIsGone() {
        val net = CountingTransport()
        // withBoundPage tears the session down on the way out, so the id below is read from a
        // WebView that no longer exists.
        val id = withBoundPage(articlePageHtml()) { readText(buildJsonObject {}) }.str("source_id")
        assertNotNull("the snapshot must publish a source_id", id)

        assertEquals(
            "the WebView must really be gone before reuse is exercised",
            "browser_not_open",
            readText(buildJsonObject {}).str("error"),
        )

        val extract = webExtractTool(OkHttpClient.Builder().addInterceptor(net).build())

        val firstWindow = invoke(extract, buildJsonObject {
            put("source_id", JsonPrimitive(id!!))
            put("max_chars", JsonPrimitive(200))
        })
        assertEquals("ok", "true", firstWindow.str("ok"))
        assertEquals("cached", "true", firstWindow.str("cached"))
        assertEquals("browser", firstWindow.str("source_kind"))
        assertEquals(id, firstWindow.str("source_id"))
        assertEquals(
            "the cached window must honour max_chars; envelope: $firstWindow",
            200,
            firstWindow.str("text").orEmpty().length,
        )
        assertEquals("true", firstWindow.str("truncated"))
        assertEquals("200", firstWindow.str("next_start_index"))

        val secondWindow = invoke(extract, buildJsonObject {
            put("source_id", JsonPrimitive(id))
            put("max_chars", JsonPrimitive(200))
            put("start_index", JsonPrimitive(200))
        })
        assertEquals(
            "pagination continues where the first window stopped",
            200,
            secondWindow.str("text").orEmpty().length,
        )
        assertFalse(
            "page two must not repeat page one",
            secondWindow.str("text").orEmpty() == firstWindow.str("text").orEmpty(),
        )

        val focused = invoke(extract, buildJsonObject {
            put("source_id", JsonPrimitive(id))
            put("focus", JsonPrimitive(PROSE_MARKER))
        })
        assertEquals("true", focused.str("focused"))
        assertTrue(
            "the cached snapshot is ranked by the shared engine; envelope: $focused",
            focused.str("text").orEmpty().contains(PROSE_MARKER),
        )
        assertNull("a ranked answer has no resume index", focused.str("next_start_index"))

        assertEquals(
            "re-reading a snapshot must not touch the network",
            0,
            net.requests.get(),
        )
    }

    @Test
    fun unfocusedWindowStaysBoundedAndFocusReachesPastIt() {
        withBoundPage(longPageHtml()) {
            val window = readText(buildJsonObject { put("max_chars", JsonPrimitive(300)) })
            assertEquals(
                "the unfocused answer stays inside its window; envelope: $window",
                300,
                window.str("text").orEmpty().length,
            )
            assertEquals("true", window.str("truncated"))

            val focused = readText(buildJsonObject { put("focus", JsonPrimitive(DEEP_MARKER)) })
            assertEquals("true", focused.str("focused"))
            val original = focused.int("original_chars")
            assertTrue(
                "ranking must see the whole rendered page, not a 32K window (got $original); envelope: $focused",
                original > 32 * 1024,
            )
            assertTrue(
                "the passage past the first window must be found; envelope: $focused",
                focused.str("text").orEmpty().contains(DEEP_MARKER),
            )
            assertTrue("more than one chunk must have been considered", focused.int("chunks_total") > 1)
            assertEquals("true", focused.str("selection_truncated"))
            assertNull("a ranked answer has no resume index", focused.str("next_start_index"))
        }
    }

    // ---- Timeout and cancellation --------------------------------------------------------------

    @Test
    fun perToolTimeoutFiresOnTheRealWebViewRead() {
        withBoundPage(articlePageHtml()) {
            val env = withMainThreadBlocked {
                // A 1 ms budget cannot survive the main-thread hop plus the JS round trip.
                BrowserController.perToolTimeoutMs = 1L
                try {
                    readText(buildJsonObject {})
                } finally {
                    BrowserController.perToolTimeoutMs = savedToolTimeoutMs
                }
            }
            assertEquals("tool_timeout; envelope: $env", "tool_timeout", env.str("error"))
            assertEquals("browser_get_text", env.str("tool"))
        }
    }

    @Test
    fun callerCancellationPropagatesInsteadOfBecomingAnEnvelope() {
        val outcome = withBoundPage(articlePageHtml()) {
            val tool = browserGetTextTool()
            val scope = CoroutineScope(Dispatchers.Default)
            try {
                withMainThreadBlocked {
                    // The read is parked on the main looper, so this cancel is guaranteed to land
                    // while the real WebView read is still in flight.
                    val deferred = scope.async { tool.execute(buildJsonObject {}) }
                    Thread.sleep(150)
                    deferred.cancel()
                    assertThrows(CancellationException::class.java) {
                        runBlocking { deferred.await() }
                    }
                    assertTrue("a cancelled read must not report success", deferred.isCancelled)
                    "cancelled"
                }
            } finally {
                scope.cancel()
            }
        }
        assertEquals("cancelled", outcome)
    }

    // ---- Harness -------------------------------------------------------------------------------

    private fun readText(args: JsonObject): JsonObject = invoke(browserGetTextTool(), args)

    private fun invoke(tool: Tool, args: JsonObject): JsonObject = runBlocking {
        val parts = tool.execute(args)
        val text = parts.filterIsInstance<UIMessagePart.Text>().first().text
        Json.parseToJsonElement(text).jsonObject
    }

    /**
     * Bind a fresh offscreen session, load [html] and run [block] against the live WebView. The
     * session is always unbound and destroyed again, so no test can leak a WebView or leave the
     * global controller pointing at a dead one.
     */
    private fun <T> withBoundPage(html: String, block: () -> T): T {
        val s = HeadlessBrowserSession(context)
        val webView = onMain { s.start(CONV_ID) }
        assertTrue(
            "the offscreen session must bind into BrowserController",
            BrowserController.bindHeadless(CONV_ID, webView),
        )
        session = s
        // browser_open arms this window on every navigation; every browser tool checks it.
        BrowserController.startTaskWindow()
        try {
            onMain { webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null) }
            awaitDom(webView)
            return block()
        } finally {
            BrowserController.unbindHeadless(CONV_ID)
            onMain { s.stop() }
            session = null
        }
    }

    /** The reader needs a live DOM; `readyState` alone says nothing about an offscreen WebView. */
    private fun awaitDom(webView: WebView, timeoutMs: Long = 15_000L) {
        runBlocking { webView.awaitReadyState(timeoutMs) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val raw = runBlocking {
                webView.evaluateJavascriptAsync("(function(){return document.body ? 1 : 0;})()", 2_000L)
            }
            if (raw?.trim() == "1") {
                // One settle tick so the render tree (and therefore innerText) exists.
                Thread.sleep(200)
                return
            }
            Thread.sleep(100)
        }
    }

    /**
     * Occupy the main looper for the duration of [block]. The reader hops to `Dispatchers.Main`
     * before it touches the WebView, so this holds a real read in flight deterministically - no
     * sleeps racing a page load.
     */
    private fun <T> withMainThreadBlocked(block: () -> T): T {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            started.countDown()
            gate.await(15, TimeUnit.SECONDS)
        }
        assertTrue("the main looper never picked the gate up", started.await(5, TimeUnit.SECONDS))
        return try {
            block()
        } finally {
            gate.countDown()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * `ActivityLifecycleMonitorRegistry.getActivitiesInStage` is a main-thread-only API and throws
     * `IllegalStateException: Querying activity state off main thread is not allowed` from any other
     * thread, so it goes through the same [onMain] helper the rest of the harness uses.
     */
    private fun resumedActivityNames(): List<String> = onMain {
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .map { it.javaClass.name }
            .sorted()
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: -1

    /** Answers every request from memory and counts them, so "no request" is observed, not assumed. */
    private class CountingTransport : Interceptor {
        val requests = AtomicInteger(0)

        override fun intercept(chain: Interceptor.Chain): Response {
            requests.incrementAndGet()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(NETWORK_ONLY_BODY.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
    }

    private fun articlePageHtml(): String = buildString {
        append("<!doctype html><html><head><title>Instrumented research article</title>")
        append("<meta name='description' content='Instrumented research article'>")
        append("<style>body{font-family:sans-serif}.x{content:\"$STYLE_SENTINEL\"}</style>")
        append("<script>window.__leak=\"$SCRIPT_SENTINEL\";</script>")
        append("</head><body><nav>Site navigation chrome</nav><article><h1>Instrumented research article</h1>")
        repeat(12) { index ->
            append("<p>Paragraph $index. ")
            append(PROSE)
            append("</p>")
        }
        append("</article>")
        append("<div id='hostile-target'>$SELECTOR_MARKER</div>")
        append("<p data-label='a\"b\\c'>$HOSTILE_MARKER</p>")
        append("<p id='идентификатор'>$UNICODE_MARKER</p>")
        append("<div style='display:none'>$DISPLAY_NONE_SENTINEL</div>")
        append("<div hidden>$HIDDEN_SENTINEL</div>")
        append("<form action='#'><input type='password' name='pw' value='$PASSWORD_SENTINEL'></form>")
        append("</body></html>")
    }

    /**
     * Models mobile article skins such as Minerva: the complete semantic article is already in the
     * DOM, but a stylesheet hides later sections from `innerText` until the user expands them.
     */
    private fun collapsedArticlePageHtml(): String = buildString {
        append("<!doctype html><html><head><title>Collapsed semantic article</title>")
        append("<style>.collapsed-section{display:none}.marker{content:'$STYLE_SENTINEL'}</style>")
        append("<script>window.__collapsedLeak='$SCRIPT_SENTINEL';</script>")
        append("</head><body><main><article><h1>Collapsed semantic article</h1>")
        repeat(8) {
            append("<p>$COLLAPSED_VISIBLE_MARKER. $PROSE</p>")
        }
        append("<section class='collapsed-section'><h2>Archived research section</h2>")
        val filler =
            "<p>The archived catalogue records an orbital observation, calibration sequence, " +
                "reference ledger, and seasonal measurement for later scientific review.</p>"
        repeat(260) { append(filler) }
        append("<p>The final archived finding is $COLLAPSED_DEEP_MARKER.</p>")
        append("</section>")
        append("<script>window.__insideLeak='$SCRIPT_SENTINEL';</script>")
        append("<style>.inside-marker{content:'$STYLE_SENTINEL'}</style>")
        append("<div style='display:none'>$DISPLAY_NONE_SENTINEL</div>")
        append("<div hidden>$HIDDEN_SENTINEL</div>")
        append("<form><input type='password' value='$PASSWORD_SENTINEL'></form>")
        append("</article></main></body></html>")
    }

    private fun shortPageHtml(): String =
        "<!doctype html><html><head><title>Short page</title></head><body>" +
            "<div id='only'>$SHORT_PAGE_MARKER and very little else.</div></body></html>"

    private fun emptyPageHtml(): String =
        "<!doctype html><html><head><title>Empty page</title></head><body></body></html>"

    /**
     * ~55 KB of prose with a unique term parked past the 32 KiB window: the read has to rank the
     * whole rendered page for the marker to be findable at all.
     */
    private fun longPageHtml(): String = buildString {
        append("<!doctype html><html><head><title>Long rendered page</title></head><body><article>")
        append("<h1>Long rendered page</h1>")
        val filler =
            "<p>The archive ledger records a transit observation with calibration notes and a mirror reading. " +
                "Operators annotate every entry so a later reader can retrace the season.</p>"
        repeat(230) { append(filler) }
        append("<p>The closing note names the $DEEP_MARKER and seals the ledger for the season.</p>")
        repeat(70) { append(filler) }
        append("</article></body></html>")
    }

    private companion object {
        const val CONV_ID = "instrumented-web-research"
        const val BASE_URL = "https://research.invalid/article"

        const val PROSE_MARKER = "Zephyrion"
        const val DEEP_MARKER = "Quintessence-9F3"
        const val COLLAPSED_VISIBLE_MARKER = "VISIBLE-COLLAPSED-INTRO-4"
        const val COLLAPSED_DEEP_MARKER = "TRANSHUMANISM-DEPTH-75"
        const val SELECTOR_MARKER = "SELECTORMARKER5"
        const val HOSTILE_MARKER = "HOSTILEMARKER7"
        const val UNICODE_MARKER = "UNICODEMARKER9"
        const val SHORT_PAGE_MARKER = "TINYPAGEMARKER3"

        const val SCRIPT_SENTINEL = "SCRIPTLEAK4217"
        const val STYLE_SENTINEL = "STYLELEAK9321"
        const val DISPLAY_NONE_SENTINEL = "DISPLAYNONELEAK6180"
        const val HIDDEN_SENTINEL = "HIDDENLEAK5183"
        const val PASSWORD_SENTINEL = "PASSWORDLEAK7734"

        const val NETWORK_ONLY_BODY = "<html><body><article><p>NETWORKONLY-5</p></article></body></html>"

        /** A double quote and a backslash inside the attribute value, escaped the way CSS requires. */
        const val HOSTILE_SELECTOR = "[data-label=\"a\\\"b\\\\c\"]"

        val PROSE =
            "The Zephyrion observatory records the transit of each satellite across a dark sky. " +
                "Operators calibrate the mirror array before every long exposure and log the result. " +
                "Every measurement is stored in a ledger that the team revisits at the end of the night. " +
                "A careful reading of the archive shows how the instrument drifts over the seasons. "
    }
}
