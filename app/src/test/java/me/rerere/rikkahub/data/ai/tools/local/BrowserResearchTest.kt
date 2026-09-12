package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.search.extract.BoundedWebSourceCache
import me.rerere.search.extract.ExtractMode
import me.rerere.search.extract.WebSourceOrigin
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
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stage 3: the rendered browser page feeding the same research engine as `web_extract`.
 *
 * Everything here runs without a device, a WebView or a network: the page read is behind
 * [RenderedPageReader] (counting fakes below) and the transport is an OkHttp interceptor that also
 * counts requests, so "reuse does not re-read the page" and "reuse makes no HTTP call" are
 * observed rather than assumed.
 */
class BrowserResearchTest {

    private val headline = "Kotlin compiler performance"
    private val intro = "Компилятор Kotlin и инкрементальная сборка: обзор производительности. ".repeat(8)
    private val middle = "${"Раздел про кэш и оптимизацию времени сборки проекта. ".repeat(8)}\n\n"
    private val tail = "Гидроцикл с карбюратором требует обслуживания перед сезоном навигации. ".repeat(6)

    private fun longPage(): String = "$intro\n\n$middle$tail"

    private class FakeReader(private var pages: MutableList<RenderedRead>) : RenderedPageReader {
        val reads = AtomicInteger(0)
        var lastRequest: ReadRequest? = null
        var delayMs: Long = 0
        var failure: Throwable? = null

        override suspend fun read(request: ReadRequest): RenderedRead {
            reads.incrementAndGet()
            lastRequest = request
            failure?.let { throw it }
            if (delayMs > 0) delay(delayMs)
            val next = pages.removeFirstOrNull() ?: pages.firstOrNull()
            return next ?: RenderedRead.NotOpen
        }
    }

    private class FakeWeb(private val html: String) : Interceptor {
        val requests = AtomicInteger(0)
        override fun intercept(chain: Interceptor.Chain): Response {
            requests.incrementAndGet()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
    }

    @After
    fun clearCache() {
        webSourceCache.clear()
    }

    private fun page(
        text: String,
        mode: String = "raw_fallback",
        scope: RenderedScope = RenderedScope.FULL_PAGE,
        url: String? = "https://app.example.com/rendered",
        title: String? = headline,
        readTruncated: Boolean = false,
    ) = RenderedPage(url, title, text, mode, scope, readTruncated)

    private fun readerOf(vararg pages: RenderedRead) = FakeReader(pages.toMutableList())

    private fun invokeBrowser(reader: RenderedPageReader, args: String, timeoutMs: Long = 5_000L): JsonObject {
        val text = runBlocking {
            runBrowserTextRead(
                input = Json.parseToJsonElement(args),
                reader = reader,
                timeoutMs = timeoutMs,
                notOpen = { Json.parseToJsonElement("""{"error":"browser_not_open"}""").jsonObject },
            ).toString()
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun invokeTool(tool: Tool, args: String): JsonObject {
        val text = runBlocking {
            (tool.execute(Json.parseToJsonElement(args)) as List<*>)
                .filterIsInstance<UIMessagePart.Text>()
                .first().text
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    // ---- 1, 2: snapshot -> source_id -------------------------------------------------------

    @Test
    fun `a full page snapshot returns a source id for the rendered page`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        val json = invokeBrowser(reader, """{"max_chars":8000}""")

        assertEquals(1, reader.reads.get())
        val id = json["source_id"]!!.jsonPrimitive.content
        assertTrue("id was $id", id.startsWith("src_"))
        assertEquals("browser", json["source_kind"]!!.jsonPrimitive.content)
        assertEquals("https://app.example.com/rendered", json["current_url"]!!.jsonPrimitive.content)
        assertEquals(headline, json["title"]!!.jsonPrimitive.content)
        // Legacy contract preserved.
        assertTrue(json["text"]!!.jsonPrimitive.content.startsWith("Компилятор Kotlin"))
        assertFalse(json["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("raw_fallback", json["extract_mode"]!!.jsonPrimitive.content)

        val cached = webSourceCache.get(id)
        assertNotNull(cached)
        assertEquals(WebSourceOrigin.BROWSER, cached!!.origin)
        assertNull("a rendered page has no HTTP status", cached.status)
        assertEquals(ExtractMode.TEXT, cached.mode)
    }

    @Test
    fun `the snapshot stores the whole page, not the max_chars window`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        val json = invokeBrowser(reader, """{"max_chars":400}""")

        assertEquals(400, json["text"]!!.jsonPrimitive.content.length)
        assertTrue(json["truncated"]!!.jsonPrimitive.content.toBoolean())

        val cached = webSourceCache.get(json["source_id"]!!.jsonPrimitive.content)!!
        assertTrue("stored ${cached.text.length} chars", cached.text.length > 400)
        assertTrue(cached.text.contains("Гидроцикл"))
    }

    // ---- 3: ranking sees past the response window -----------------------------------------

    @Test
    fun `focus finds text that lies past the output window`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        val json = invokeBrowser(reader, """{"focus":"гидроцикл карбюратором","max_chars":400}""")

        assertTrue(json["focused"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("Гидроцикл"))
        assertTrue(json["chunks_total"]!!.jsonPrimitive.content.toInt() >= 2)
        assertEquals(1, json["chunks_selected"]!!.jsonPrimitive.content.toInt())
        assertTrue(json["selection_truncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            json["original_chars"]!!.jsonPrimitive.content.toInt() >
                json["returned_chars"]!!.jsonPrimitive.content.toInt(),
        )
        assertNotNull(json["source_id"])
    }

    @Test
    fun `focus respects a max_chars smaller than the focused budget`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        val json = invokeBrowser(reader, """{"focus":"гидроцикл","max_chars":300}""")

        assertTrue(json["returned_chars"]!!.jsonPrimitive.content.toInt() <= 300)
    }

    // ---- 4, 5: selector semantics ---------------------------------------------------------

    @Test
    fun `focus with a selector is refused as a conflict`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage(), scope = RenderedScope.SELECTOR)))
        val json = invokeBrowser(reader, """{"selector":"#main","focus":"кэш"}""")

        assertEquals("focus_selector_conflict", json["error"]!!.jsonPrimitive.content)
        assertTrue(json["recovery"]!!.jsonPrimitive.content.contains("selector"))
        assertEquals("nothing is read on a conflict", 0, reader.reads.get())
    }

    @Test
    fun `a selector read keeps the legacy shape and is never cached`() {
        // The reader hands back rendered text, never markup - so the legacy response is exactly
        // that text, unchanged by this stage.
        val reader = readerOf(
            RenderedRead.Ok(page("Targeted subtree text", mode = "raw", scope = RenderedScope.SELECTOR)),
        )
        val json = invokeBrowser(reader, """{"selector":"#main","max_chars":8000}""")

        assertEquals("Targeted subtree text", json["text"]!!.jsonPrimitive.content)
        assertEquals("raw", json["extract_mode"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("source_id"))
        assertFalse(json.containsKey("current_url"))
        assertFalse("a subtree is not a page source", json.containsKey("source_kind"))
        assertEquals("#main", reader.lastRequest!!.selector)
    }

    // ---- 6, 7: reuse through web_extract --------------------------------------------------

    @Test
    fun `reuse through web_extract never reads the page again and never touches the network`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        val browser = invokeBrowser(reader, """{"max_chars":8000}""")
        val id = browser["source_id"]!!.jsonPrimitive.content
        assertEquals(1, reader.reads.get())

        val web = FakeWeb("<html><body><p>should never be fetched</p></body></html>")
        val extract = webExtractTool(OkHttpClient.Builder().addInterceptor(web).build())

        val first = invokeTool(extract, """{"source_id":"$id","focus":"гидроцикл"}""")
        val second = invokeTool(extract, """{"source_id":"$id","focus":"инкрементальная сборка"}""")

        assertEquals("true", first["cached"]!!.jsonPrimitive.content)
        assertEquals("browser", first["source_kind"]!!.jsonPrimitive.content)
        assertFalse("a browser source has no HTTP status", first.containsKey("status"))
        assertEquals("https://app.example.com/rendered", first["final_url"]!!.jsonPrimitive.content)
        assertEquals("true", second["cached"]!!.jsonPrimitive.content)
        // The two questions were answered from one snapshot.
        assertEquals(1, reader.reads.get())
        assertEquals("no HTTP was involved", 0, web.requests.get())
    }

    @Test
    fun `a different focus over the same source selects different passages`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        val id = invokeBrowser(reader, """{}""")["source_id"]!!.jsonPrimitive.content
        val extract = webExtractTool(OkHttpClient())

        val hydro = invokeTool(extract, """{"source_id":"$id","focus":"гидроцикл карбюратором"}""")
        val build = invokeTool(extract, """{"source_id":"$id","focus":"кэш оптимизацию времени сборки"}""")

        assertTrue(hydro["text"]!!.jsonPrimitive.content.contains("Гидроцикл"))
        assertFalse(hydro["text"]!!.jsonPrimitive.content.contains("кэш и оптимизацию"))
        assertTrue(build["text"]!!.jsonPrimitive.content.contains("кэш и оптимизацию"))
        assertFalse(build["text"]!!.jsonPrimitive.content.contains("Гидроцикл"))
        assertEquals(1, reader.reads.get())
    }

    @Test
    fun `a cached browser source pages with max_chars and start_index`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        val id = invokeBrowser(reader, """{}""")["source_id"]!!.jsonPrimitive.content
        val extract = webExtractTool(OkHttpClient())

        val first = invokeTool(extract, """{"source_id":"$id","max_chars":400}""")
        assertTrue(first["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(400, first["next_start_index"]!!.jsonPrimitive.content.toInt())

        val second = invokeTool(extract, """{"source_id":"$id","max_chars":400,"start_index":400}""")
        assertEquals(
            800,
            first["text"]!!.jsonPrimitive.content.length +
                second["text"]!!.jsonPrimitive.content.length,
        )
        assertEquals(1, reader.reads.get())
    }

    @Test
    fun `focus with a start index on a browser source is still a conflict`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        val id = invokeBrowser(reader, """{}""")["source_id"]!!.jsonPrimitive.content
        val extract = webExtractTool(OkHttpClient())

        val json = invokeTool(extract, """{"source_id":"$id","focus":"кэш","start_index":400}""")

        assertEquals("focus_start_index_conflict", json["error"]!!.jsonPrimitive.content)
        assertEquals(id, json["source_id"]!!.jsonPrimitive.content)
    }

    // ---- 10, 11, 12: failures, readability, fallback ---------------------------------------

    @Test
    fun `an unbound browser reports not_open, reads nothing and stores nothing`() {
        val reader = readerOf()
        val json = invokeBrowser(reader, """{"focus":"кэш"}""")

        assertEquals("browser_not_open", json["error"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("source_id"))
        assertEquals(0, webSourceCache.size())
    }

    @Test
    fun `a failed read keeps its error code and stores nothing`() {
        val reader = readerOf(RenderedRead.Failure("readability_failed", recovery = "try auto"))
        val json = invokeBrowser(reader, """{"extract_mode":"readability"}""")

        assertEquals("readability_failed", json["error"]!!.jsonPrimitive.content)
        assertEquals("try auto", json["recovery"]!!.jsonPrimitive.content)
        assertEquals(0, webSourceCache.size())
    }

    @Test
    fun `a readability snapshot is stored as an article source`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage(), mode = MODE_READABILITY)))
        val json = invokeBrowser(reader, """{"extract_mode":"auto"}""")

        assertEquals("readability", json["extract_mode"]!!.jsonPrimitive.content)
        val cached = webSourceCache.get(json["source_id"]!!.jsonPrimitive.content)!!
        assertEquals(ExtractMode.ARTICLE, cached.mode)
        assertEquals(WebSourceOrigin.BROWSER, cached.origin)
    }

    @Test
    fun `the fallback path is stored as a text source`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage(), mode = "raw_fallback")))
        val json = invokeBrowser(reader, """{}""")

        val cached = webSourceCache.get(json["source_id"]!!.jsonPrimitive.content)!!
        assertEquals(ExtractMode.TEXT, cached.mode)
    }

    // ---- 13: a fresh snapshot after the page changed ---------------------------------------

    @Test
    fun `a page updated by JS is snapshotted afresh instead of reusing the old source`() {
        val before = page(longPage())
        val after = page(longPage().replace("сезоном навигации", "сезоном навигации и тюнингом"))
        val reader = readerOf(RenderedRead.Ok(before), RenderedRead.Ok(after))

        val first = invokeBrowser(reader, """{"max_chars":8000}""")
        val second = invokeBrowser(reader, """{"max_chars":8000}""")

        val firstId = first["source_id"]!!.jsonPrimitive.content
        val secondId = second["source_id"]!!.jsonPrimitive.content
        assertFalse("a snapshot is not reused from an earlier read", firstId == secondId)
        assertTrue(
            webSourceCache.get(secondId)!!.text.contains("тюнингом"),
        )
        assertFalse(
            "the older snapshot is untouched, not overwritten",
            webSourceCache.get(firstId)!!.text.contains("тюнингом"),
        )
        assertEquals(2, reader.reads.get())
    }

    // ---- 14, 15, 16: sanitisation ---------------------------------------------------------

    @Test
    fun `script style hidden nodes and form values never reach the source`() {
        val fixture = """
            <div class="article">
              <h1>Kotlin compiler performance</h1>
              <p>Visible prose about incremental compilation and the cache.</p>
              <script>var token = "SCRIPT_TOKEN_DO_NOT_STORE";</script>
              <style>.x { color: red } STYLE_TOKEN_DO_NOT_STORE</style>
              <input type="password" name="pw" value="PASSWORD_VALUE_DO_NOT_STORE">
              <input type="hidden" name="csrf" value="HIDDEN_INPUT_VALUE_DO_NOT_STORE">
              <input type="text" name="q" value="TYPED_VALUE_DO_NOT_STORE">
              <div hidden>HIDDEN_DIV_TOKEN_DO_NOT_STORE</div>
              <div aria-hidden="true">ARIA_HIDDEN_TOKEN_DO_NOT_STORE</div>
              <p>More visible prose.</p>
            </div>
        """.trimIndent()

        val sanitized = BrowserTextSanitizer.sanitize(fixture)

        assertTrue(sanitized.contains("Kotlin compiler performance"))
        assertTrue(sanitized.contains("Visible prose about incremental compilation"))
        assertTrue(sanitized.contains("More visible prose."))
        for (leak in listOf(
            "SCRIPT_TOKEN_DO_NOT_STORE",
            "STYLE_TOKEN_DO_NOT_STORE",
            "PASSWORD_VALUE_DO_NOT_STORE",
            "HIDDEN_INPUT_VALUE_DO_NOT_STORE",
            "TYPED_VALUE_DO_NOT_STORE",
            "HIDDEN_DIV_TOKEN_DO_NOT_STORE",
            "ARIA_HIDDEN_TOKEN_DO_NOT_STORE",
        )) {
            assertFalse("$leak leaked into the source", sanitized.contains(leak))
        }
        assertFalse("no raw markup may survive", sanitized.contains("<"))
        assertFalse(sanitized.contains(">"))
        assertFalse(sanitized.contains("input"))
    }

    @Test
    fun `a snapshot with markup is stored as clean prose`() {
        val reader = readerOf(
            RenderedRead.Ok(
                page("<p>Rendered prose paragraph.</p><script>var secret='X';</script>"),
            ),
        )
        val id = invokeBrowser(reader, """{}""")["source_id"]!!.jsonPrimitive.content

        val stored = webSourceCache.get(id)!!.text
        assertTrue(stored.contains("Rendered prose paragraph."))
        assertFalse(stored.contains("secret"))
        assertFalse(stored.contains("<"))
    }

    @Test
    fun `clean prose passes through the sanitiser unchanged and idempotently`() {
        val prose = "Первый абзац статьи.\n\nВторой абзац статьи."

        val once = BrowserTextSanitizer.sanitize(prose)
        val twice = BrowserTextSanitizer.sanitize(once)

        assertEquals(prose, once)
        assertEquals(once, twice)
    }

    // ---- 17, 18, 20: timeout, cancellation, cache policy -----------------------------------

    @Test
    fun `a read that overruns the tool budget is reported as a timeout`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        reader.delayMs = 300

        val json = invokeBrowser(reader, """{}""", timeoutMs = 50)

        assertEquals("tool_timeout", json["error"]!!.jsonPrimitive.content)
        assertEquals("browser_get_text", json["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancellation propagates instead of turning into an error envelope`() {
        val reader = readerOf(RenderedRead.Ok(page(longPage())))
        reader.failure = CancellationException("cancelled by the caller")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runBrowserTextRead(
                    input = Json.parseToJsonElement("""{}"""),
                    reader = reader,
                    timeoutMs = 5_000L,
                    notOpen = { Json.parseToJsonElement("""{"error":"browser_not_open"}""").jsonObject },
                )
            }
        }

        assertEquals("cancelled by the caller", thrown.message)
    }

    @Test
    fun `a browser source obeys the shared cache limits`() {
        val local = BoundedWebSourceCache(maxEntries = 1, maxBytes = 4L * 1024 * 1024, clock = { 1_000L })
        val reader = readerOf(
            RenderedRead.Ok(page(longPage())),
            RenderedRead.Ok(page(longPage(), url = "https://app.example.com/second")),
        )

        val first = runBlocking {
            runBrowserTextRead(
                input = Json.parseToJsonElement("""{}"""),
                reader = reader,
                timeoutMs = 5_000L,
                nowMillis = 1_000L,
                store = { local.put(it) },
                notOpen = { Json.parseToJsonElement("""{"error":"browser_not_open"}""").jsonObject },
            )
        }.jsonObject
        val second = runBlocking {
            runBrowserTextRead(
                input = Json.parseToJsonElement("""{}"""),
                reader = reader,
                timeoutMs = 5_000L,
                nowMillis = 1_000L,
                store = { local.put(it) },
                notOpen = { Json.parseToJsonElement("""{"error":"browser_not_open"}""").jsonObject },
            )
        }.jsonObject

        assertEquals(1, local.size())
        assertNull(local.get(first["source_id"]!!.jsonPrimitive.content))
        assertNotNull(local.get(second["source_id"]!!.jsonPrimitive.content))
    }
}
