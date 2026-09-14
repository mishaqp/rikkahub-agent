package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserController
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tool-level wiring for the browser research path, plus the architectural guarantee that the web
 * tools stay background-only: they never bind the browser controller and never open a browser.
 */
class BrowserGetTextToolTest {

    private val prose = "Kotlin compiler performance and incremental build cache. ".repeat(40)

    private class CountingReader(private val read: RenderedRead) : RenderedPageReader {
        val reads = AtomicInteger(0)
        override suspend fun read(request: ReadRequest): RenderedRead {
            reads.incrementAndGet()
            return read
        }
    }

    private class CountingWeb(private val html: String) : Interceptor {
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
    fun clear() {
        webSourceCache.clear()
        BrowserController.clearTaskWindow()
    }

    private fun invoke(tool: Tool, args: String): JsonObject {
        val text = runBlocking {
            (tool.execute(Json.parseToJsonElement(args)) as List<*>)
                .filterIsInstance<UIMessagePart.Text>()
                .first().text
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun okPage(text: String = prose) = RenderedRead.Ok(
        RenderedPage(
            url = "https://app.example.com/page",
            title = "Rendered page",
            text = text,
            extractMode = "raw_fallback",
            scope = RenderedScope.FULL_PAGE,
        ),
    )

    // ---- schema ---------------------------------------------------------------------------

    @Test
    fun `browser_get_text advertises focus and keeps its existing arguments`() {
        val schema = browserGetTextTool(CountingReader(okPage())).parameters() as InputSchema.Obj

        assertTrue(schema.properties.containsKey("selector"))
        assertTrue(schema.properties.containsKey("max_chars"))
        assertTrue(schema.properties.containsKey("extract_mode"))
        assertTrue("focus must be advertised", schema.properties.containsKey("focus"))
        assertNull("no argument has ever been required here", schema.required)
    }

    @Test
    fun `the description steers away from opening a browser and towards source_id reuse`() {
        val description = browserGetTextTool(CountingReader(okPage())).description

        assertTrue(description.contains("source_id"))
        assertTrue(description.contains("never opens a browser by itself"))
        assertTrue(description.contains("web_fetch"))
    }

    // ---- tool wiring ----------------------------------------------------------------------

    @Test
    fun `the tool returns a ranked answer and a reusable source id`() {
        val reader = CountingReader(okPage())
        val json = invoke(browserGetTextTool(reader), """{"focus":"incremental build cache"}""")

        assertTrue(json["focused"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("incremental build cache"))
        assertNotNull(json["source_id"])
        assertEquals("browser", json["source_kind"]!!.jsonPrimitive.content)
        assertEquals(1, reader.reads.get())
    }

    @Test
    fun `an unbound browser reports not_open and stores nothing`() {
        // The default reader talks to the live WebView; with nothing bound it must report the
        // existing envelope and must not create a research source.
        val json = invoke(browserGetTextTool(), """{"focus":"anything"}""")

        assertEquals("browser_not_open", json["error"]!!.jsonPrimitive.content)
        assertEquals(0, webSourceCache.size())
    }

    @Test
    fun `a read failure is surfaced as its own error code`() {
        val reader = CountingReader(RenderedRead.Failure("selector_not_found", detail = "#nope"))
        val json = invoke(browserGetTextTool(reader), """{"selector":"#nope"}""")

        assertEquals("selector_not_found", json["error"]!!.jsonPrimitive.content)
        assertEquals("#nope", json["detail"]!!.jsonPrimitive.content)
    }

    // ---- architecture: the browser is never invoked behind the user's back -----------------

    @Test
    fun `web_fetch and web_extract never bind the browser controller`() {
        BrowserController.clearTaskWindow()
        val web = CountingWeb("<html><body><article><p>${"Plain page prose. ".repeat(30)}</p></article></body></html>")
        val client = OkHttpClient.Builder().addInterceptor(web).build()

        invoke(webFetchTool(client), """{"url":"https://example.com/a","extract_mode":"article"}""")
        invoke(webExtractTool(client), """{"url":"https://example.com/a"}""")
        invoke(webExtractTool(client), """{"url":"https://example.com/empty"}""")

        // Nothing in the web tools reached for a WebView or claimed the controller slot: the only
        // tool entitled to surface a browser is browser_open, and it was never called.
        assertNull(BrowserController.activeWebView())
        assertTrue(BrowserController.canBindHeadless("conv-untouched"))
    }

    @Test
    fun `web_extract reading a source never consults a page reader`() {
        val reader = CountingReader(okPage())
        val id = invoke(browserGetTextTool(reader), """{}""")["source_id"]!!.jsonPrimitive.content
        assertEquals(1, reader.reads.get())

        val web = CountingWeb("<html><body><p>never fetched</p></body></html>")
        val extract = webExtractTool(OkHttpClient.Builder().addInterceptor(web).build())
        invoke(extract, """{"source_id":"$id","focus":"cache"}""")

        assertEquals("the page was not read again", 1, reader.reads.get())
        assertEquals("no HTTP was issued", 0, web.requests.get())
    }

    @Test
    fun `read tools other than get_text create no research source`() {
        invoke(browserCurrentUrlTool(), """{}""")
        invoke(browserWaitForTool(), """{"selector":".loaded"}""")
        invoke(browserGetDomTool(), """{"selector":"body"}""")

        assertEquals(0, webSourceCache.size())
    }
}
