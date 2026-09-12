package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.search.extract.WebSourceId
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `source_id` reuse (Web Research v1, stage 2). Every test counts requests with an application
 * interceptor that answers without touching a socket, so "no second request" is observed, not
 * assumed.
 */
class WebSourceReuseTest {

    private val football = "Матч по футболу закончился вничью, и тренер остался недоволен игрой полузащиты во втором тайме. ".repeat(6)
    private val android = "Команда выпустила новую версию приложения с поддержкой офлайн режима работы и ускоренной синхронизацией. ".repeat(6)
    private val cooking = "Кулинарный рецепт борща включает свёклу, капусту, картофель и наваристый мясной бульон для подачи. ".repeat(6)
    private val html = "<html><body><article>" +
        "<p>$football</p><p>$android</p><p>$cooking</p></article></body></html>"

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

    private lateinit var net: FakeWeb

    private fun fetchTool(): Tool {
        net = FakeWeb(html)
        return webFetchTool(OkHttpClient.Builder().addInterceptor(net).build())
    }

    private fun extractTool(): Tool {
        net = FakeWeb(html)
        return webExtractTool(OkHttpClient.Builder().addInterceptor(net).build())
    }

    @After
    fun clearCache() {
        webSourceCache.clear()
    }

    private fun invoke(tool: Tool, args: String): JsonObject {
        val text = runBlocking {
            (tool.execute(Json.parseToJsonElement(args)) as List<*>)
                .filterIsInstance<UIMessagePart.Text>()
                .first().text
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    /** A live article fetch whose source_id is now in the cache. */
    private fun seed(tool: Tool): String {
        val json = invoke(tool, """{"url":"https://example.com/a","extract_mode":"article"}""")
        return json["source_id"]!!.jsonPrimitive.content
    }

    @Test
    fun `a live fetch publishes a source id that is reused with no second request`() {
        val tool = fetchTool()

        val first = invoke(tool, """{"url":"https://example.com/a","extract_mode":"article"}""")
        val id = first["source_id"]!!.jsonPrimitive.content
        assertTrue("id was $id", id.startsWith("src_"))
        assertTrue(WebSourceId.isWellFormed(id))
        assertTrue(first["text"]!!.jsonPrimitive.content.contains("Команда выпустила"))
        assertEquals(1, net.requests.get())

        val second = invoke(tool, """{"source_id":"$id"}""")
        assertEquals("true", second["cached"]!!.jsonPrimitive.content)
        assertEquals(id, second["source_id"]!!.jsonPrimitive.content)
        assertTrue(second["text"]!!.jsonPrimitive.content.contains("Команда выпустила"))
        // The proof: reusing a source_id issues no HTTP request at all.
        assertEquals(1, net.requests.get())
    }

    @Test
    fun `two reads of one url get different source ids`() {
        val tool = fetchTool()

        val first = invoke(tool, """{"url":"https://example.com/a","extract_mode":"article"}""")
        val second = invoke(tool, """{"url":"https://example.com/a","extract_mode":"article"}""")

        val firstId = first["source_id"]!!.jsonPrimitive.content
        val secondId = second["source_id"]!!.jsonPrimitive.content
        assertNotEquals(firstId, secondId)
        // Both are live at once, and the second one is the one the cache reports.
        assertNotNull(webSourceCache.get(firstId))
        assertNotNull(webSourceCache.get(secondId))
        assertEquals(secondId, webSourceCache.get(secondId)!!.sourceId)
        assertEquals(2, net.requests.get())
    }

    @Test
    fun `the source id reveals nothing about the url`() {
        val tool = fetchTool()

        val json = invoke(
            tool,
            """{"url":"https://unique-host-name-xyz.example/secret-path","extract_mode":"article"}""",
        )
        val id = json["source_id"]!!.jsonPrimitive.content

        assertFalse(id.contains("unique-host-name-xyz"))
        assertFalse(id.contains("example"))
        assertFalse(id.contains("secret-path"))
    }

    @Test
    fun `the cache is shared between web_fetch and web_extract`() {
        val id = seed(fetchTool())
        assertEquals(1, net.requests.get())

        val reader = extractTool()
        val json = invoke(reader, """{"source_id":"$id"}""")

        assertEquals("true", json["cached"]!!.jsonPrimitive.content)
        assertEquals(id, json["source_id"]!!.jsonPrimitive.content)
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("Команда выпустила"))
        assertEquals("web_extract read the cache", 0, net.requests.get())
    }

    @Test
    fun `an unknown source id is a structured error and never touches the network`() {
        val tool = fetchTool()

        val json = invoke(tool, """{"source_id":"web-0000000000000000"}""")

        assertEquals("unknown_source_id", json["error"]!!.jsonPrimitive.content)
        assertEquals("web-0000000000000000", json["source_id"]!!.jsonPrimitive.content)
        assertTrue(json["recovery"]!!.jsonPrimitive.content.contains("url"))
        assertEquals(0, net.requests.get())
    }

    @Test
    fun `url and source id together are refused`() {
        val tool = fetchTool()
        val id = seed(tool)

        val json = invoke(tool, """{"url":"https://example.com/a","source_id":"$id"}""")

        assertEquals("url_source_conflict", json["error"]!!.jsonPrimitive.content)
        assertEquals(1, net.requests.get())
    }

    @Test
    fun `neither url nor source id is refused as missing_source`() {
        val tool = fetchTool()

        assertEquals("missing_source", invoke(tool, """{}""")["error"]!!.jsonPrimitive.content)
        assertEquals("missing_source", invoke(tool, """{"url":"  "}""")["error"]!!.jsonPrimitive.content)
        assertEquals(0, net.requests.get())
    }

    @Test
    fun `a cached source supports max_chars and start_index without focus`() {
        val tool = fetchTool()
        val id = seed(tool)

        val first = invoke(tool, """{"source_id":"$id","max_chars":40}""")
        assertEquals("true", first["cached"]!!.jsonPrimitive.content)
        assertEquals("article", first["extract_mode"]!!.jsonPrimitive.content)
        assertEquals("true", first["truncated"]!!.jsonPrimitive.content)
        assertEquals(40, first["next_start_index"]!!.jsonPrimitive.content.toInt())

        val second = invoke(tool, """{"source_id":"$id","max_chars":40,"start_index":40}""")
        assertEquals(
            "the two pages concatenate to exactly two windows",
            80,
            first["text"]!!.jsonPrimitive.content.length + second["text"]!!.jsonPrimitive.content.length,
        )
        assertEquals(1, net.requests.get())
    }

    @Test
    fun `a cached source paginates the whole page not just the first window`() {
        val tool = fetchTool()
        val id = seed(tool)

        // Walk to the end of the cached page in 4000-char windows and confirm the tail is
        // reachable from the cache: the entry holds the full text, not the first window.
        var offset = 0
        var tail = ""
        var guard = 0
        while (guard++ < 20) {
            val json = invoke(
                tool,
                """{"source_id":"$id","max_chars":4000,"start_index":$offset}""",
            )
            val text = json["text"]!!.jsonPrimitive.content
            tail += text
            val next = json["next_start_index"]?.jsonPrimitive?.content?.toInt() ?: break
            offset = next
        }

        assertTrue(tail.contains("борща"))
        assertEquals(1, net.requests.get())
    }

    @Test
    fun `a cached source supports focus through the same ranking`() {
        val tool = fetchTool()
        val id = seed(tool)

        val json = invoke(tool, """{"source_id":"$id","focus":"офлайн режима работы"}""")

        assertEquals("true", json["focused"]!!.jsonPrimitive.content)
        assertEquals("true", json["cached"]!!.jsonPrimitive.content)
        assertTrue(json["chunks_selected"]!!.jsonPrimitive.content.toInt() >= 1)
        assertTrue(json["selection_truncated"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(json.containsKey("next_start_index"))
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("Команда выпустила"))
        assertFalse(json["text"]!!.jsonPrimitive.content.contains("борща"))
        assertEquals(1, net.requests.get())
    }

    @Test
    fun `focus with a start index on a cached source is still a conflict`() {
        val tool = fetchTool()
        val id = seed(tool)

        val json = invoke(tool, """{"source_id":"$id","focus":"офлайн","start_index":40}""")

        assertEquals("focus_start_index_conflict", json["error"]!!.jsonPrimitive.content)
        assertEquals(id, json["source_id"]!!.jsonPrimitive.content)
        assertEquals(1, net.requests.get())
    }

    @Test
    fun `links and metadata reads are never cached`() {
        val tool = fetchTool()

        val links = invoke(tool, """{"url":"https://example.com/a","extract_mode":"links"}""")
        val meta = invoke(tool, """{"url":"https://example.com/a","extract_mode":"metadata"}""")

        assertFalse(links.containsKey("source_id"))
        assertFalse(meta.containsKey("source_id"))
        assertEquals(2, net.requests.get())
    }

    @Test
    fun `a POST read is not cached`() {
        val tool = fetchTool()

        val json = invoke(
            tool,
            """{"url":"https://example.com/a","extract_mode":"article","method":"POST","body":"q=1"}""",
        )

        assertFalse(json.containsKey("source_id"))
        assertEquals(1, net.requests.get())
    }

    @Test
    fun `a read carrying caller headers is not cached`() {
        val tool = fetchTool()

        val json = invoke(
            tool,
            """{"url":"https://example.com/a","extract_mode":"article","headers":{"Authorization":"Bearer secret"}}""",
        )

        assertFalse(json.containsKey("source_id"))
        assertEquals(1, net.requests.get())
    }

    @Test
    fun `a focused live read also publishes its source for later reuse`() {
        val tool = fetchTool()

        val live = invoke(
            tool,
            """{"url":"https://example.com/a","extract_mode":"article","focus":"офлайн режима работы"}""",
        )
        val id = live["source_id"]!!.jsonPrimitive.content
        assertEquals("true", live["focused"]!!.jsonPrimitive.content)

        val reused = invoke(tool, """{"source_id":"$id","focus":"офлайн режима работы"}""")
        assertEquals("true", reused["cached"]!!.jsonPrimitive.content)
        assertEquals(
            live["text"]!!.jsonPrimitive.content,
            reused["text"]!!.jsonPrimitive.content,
        )
        assertEquals(1, net.requests.get())
    }
}
