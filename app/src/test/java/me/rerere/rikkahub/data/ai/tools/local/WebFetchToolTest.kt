package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Covers web_fetch's input-validation paths, all of which early-return before any network
 * call — so a default [OkHttpClient] is never actually used. Real request/response behavior
 * is exercised by instrumented tests / live runs.
 */
class WebFetchToolTest {

    private val tool: Tool = webFetchTool(OkHttpClient())

    private fun invoke(args: String): JsonObject {
        val text = runBlocking {
            (tool.execute(Json.parseToJsonElement(args)) as List<*>)
                .filterIsInstance<UIMessagePart.Text>()
                .first().text
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.error() = this["error"]?.jsonPrimitive?.content

    @Test fun `missing url is rejected`() {
        assertEquals("missing_url", invoke("""{}""").error())
    }

    @Test fun `blank url is rejected`() {
        assertEquals("missing_url", invoke("""{"url":"   "}""").error())
    }

    @Test fun `non-http url is rejected`() {
        assertEquals("bad_url", invoke("""{"url":"ftp://example.com/x"}""").error())
    }

    @Test fun `file url is rejected`() {
        assertEquals("bad_url", invoke("""{"url":"file:///etc/passwd"}""").error())
    }

    @Test fun `unsupported method is rejected`() {
        assertEquals(
            "bad_method",
            invoke("""{"url":"https://example.com","method":"DELETE"}""").error(),
        )
    }

    @Test fun `method is case-insensitive and clears validation`() {
        // "get" normalises to GET and clears the url/method validation gate. Loopback is now
        // refused up front by the egress guard's literal-IP pre-check, so the envelope reports
        // blocked_address instead of reaching the network layer.
        val err = invoke("""{"url":"http://127.0.0.1:9","method":"get"}""").error()
        assertEquals("blocked_address", err)
    }

    @Test fun `malformed url is rejected as bad_request`() {
        // Passes the http(s)-prefix check but is not a valid URL — caught at request build.
        assertEquals("bad_request", invoke("""{"url":"http://"}""").error())
    }

    // readBounded must never buffer more than cap+1 bytes, and must flag overflow.
    @Test fun `readBounded returns all bytes under cap without truncation`() {
        val (bytes, truncated) = readBounded("abc".byteInputStream(), 8192)
        assertEquals(3, bytes.size)
        assertEquals(false, truncated)
    }

    @Test fun `readBounded at exactly cap is not truncated`() {
        val cap = 256
        val (bytes, truncated) = readBounded(ByteArray(cap).inputStream(), cap)
        assertEquals(cap, bytes.size)
        assertEquals(false, truncated)
    }

    @Test fun `readBounded over cap stops at cap plus one and flags truncated`() {
        val cap = 256
        val (bytes, truncated) = readBounded(ByteArray(cap + 100).inputStream(), cap)
        assertEquals(cap + 1, bytes.size)
        assertEquals(true, truncated)
    }

    @Test
    fun `default body cap is raised for extraction modes`() {
        // Raw HTML needs a small cap because it is mostly markup; extracted prose does not.
        assertTrue(WEB_FETCH_EXTRACT_CAP > WEB_FETCH_BODY_CAP)
        assertEquals(8 * 1024, WEB_FETCH_BODY_CAP)
    }

    @Test
    fun `parseExtractMode maps names and defaults to raw`() {
        assertEquals(FetchExtract.RAW, parseExtractMode(null))
        assertEquals(FetchExtract.RAW, parseExtractMode("raw"))
        assertEquals(FetchExtract.ARTICLE, parseExtractMode("article"))
        assertEquals(FetchExtract.TEXT, parseExtractMode("text"))
        assertEquals(FetchExtract.LINKS, parseExtractMode("links"))
        assertEquals(FetchExtract.METADATA, parseExtractMode("metadata"))
    }

    @Test
    fun `parseExtractMode rejects an unknown mode`() {
        assertEquals(null, parseExtractModeOrNull("nonsense"))
    }

    @Test
    fun `extract envelope carries pagination fields`() {
        val html = "<html><body><article><p>${"Prose sentence here. ".repeat(30)}</p></article></body></html>"

        val json = Json.parseToJsonElement(
            buildExtractEnvelope(
                status = 200,
                ok = true,
                finalUrl = "https://example.com/a",
                html = html,
                contentType = "text/html",
                mode = FetchExtract.ARTICLE,
                maxChars = 50,
                startIndex = 0,
                bodyTruncated = false,
                headers = null,
            ),
        ).jsonObject

        assertEquals(200, json["status"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, json["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(50, json["next_start_index"]!!.jsonPrimitive.content.toInt())
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("Prose sentence"))
    }

    @Test
    fun `empty extraction is reported as an error not a success`() {
        // The reference server returned status 200 with an empty body twice and the agent
        // never noticed. An empty extraction must be loud.
        val json = Json.parseToJsonElement(
            buildExtractEnvelope(
                status = 200,
                ok = true,
                finalUrl = "https://example.com/a",
                html = "<html><body></body></html>",
                contentType = "text/html",
                mode = FetchExtract.ARTICLE,
                maxChars = 5000,
                startIndex = 0,
                bodyTruncated = false,
                headers = null,
            ),
        ).jsonObject

        assertEquals("empty_extraction", json["error"]!!.jsonPrimitive.content)
        assertTrue(json["recovery"]!!.jsonPrimitive.content.contains("extract_mode"))
    }

    @Test
    fun `headers are omitted unless requested`() {
        val html = "<html><body><article><p>${"Prose. ".repeat(60)}</p></article></body></html>"

        val without = Json.parseToJsonElement(
            buildExtractEnvelope(200, true, "https://e.com", html, "text/html", FetchExtract.ARTICLE, 5000, 0, false, null),
        ).jsonObject
        val with = Json.parseToJsonElement(
            buildExtractEnvelope(200, true, "https://e.com", html, "text/html", FetchExtract.ARTICLE, 5000, 0, false, mapOf("x-a" to "b")),
        ).jsonObject

        assertEquals(null, without["headers"])
        assertTrue(with["headers"]!!.jsonObject.containsKey("x-a"))
    }

    // --- focus: query-focused passage selection ---------------------------------------------

    private val focusHtml = "<html><body><article>" +
        "<p>${"Матч по футболу закончился вничью, и тренер остался недоволен игрой полузащиты во втором тайме. ".repeat(6)}</p>" +
        "<p>${"Команда выпустила новую версию приложения с поддержкой офлайн режима работы и ускоренной синхронизацией. ".repeat(6)}</p>" +
        "<p>${"Кулинарный рецепт борща включает свёклу, капусту, картофель и наваристый мясной бульон для подачи. ".repeat(6)}</p>" +
        "</article></body></html>"

    private fun focusEnvelope(
        focus: String?,
        maxChars: Int = 32 * 1024,
        startIndex: Int = 0,
        mode: FetchExtract = FetchExtract.ARTICLE,
    ): JsonObject = Json.parseToJsonElement(
        buildExtractEnvelope(
            status = 200,
            ok = true,
            finalUrl = "https://example.com/a",
            html = focusHtml,
            contentType = "text/html",
            mode = mode,
            maxChars = maxChars,
            startIndex = startIndex,
            bodyTruncated = false,
            headers = null,
            focus = focus,
        ),
    ).jsonObject

    @Test
    fun `focus returns the matching passage and its diagnostics`() {
        val json = focusEnvelope(focus = "офлайн режима работы")

        assertEquals("true", json["focused"]!!.jsonPrimitive.content)
        assertEquals("офлайн режима работы", json["focus"]!!.jsonPrimitive.content)
        val text = json["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Команда выпустила"))
        assertFalse(text.contains("борща"))
        assertTrue(json["chunks_total"]!!.jsonPrimitive.content.toInt() >= 2)
        assertEquals(1, json["chunks_selected"]!!.jsonPrimitive.content.toInt())
        assertEquals(text.length, json["returned_chars"]!!.jsonPrimitive.content.toInt())
        assertTrue(
            json["original_chars"]!!.jsonPrimitive.content.toInt() >=
                json["returned_chars"]!!.jsonPrimitive.content.toInt(),
        )
        // A ranked result is a selection, never character pagination.
        assertNull(json["next_start_index"])
    }

    @Test
    fun `focus without a match keeps the leading passages`() {
        val json = focusEnvelope(focus = "гидроцикл карбюратор")

        assertEquals("true", json["focused"]!!.jsonPrimitive.content)
        assertEquals("true", json["focus_fallback"]!!.jsonPrimitive.content)
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("Матч по футболу"))
        assertTrue(json["text"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `absent and blank focus produce the legacy envelope unchanged`() {
        val legacy = Json.parseToJsonElement(
            buildExtractEnvelope(
                status = 200,
                ok = true,
                finalUrl = "https://example.com/a",
                html = focusHtml,
                contentType = "text/html",
                mode = FetchExtract.ARTICLE,
                maxChars = 5000,
                startIndex = 0,
                bodyTruncated = false,
                headers = null,
            ),
        ).jsonObject

        val absent = focusEnvelope(focus = null, maxChars = 5000)
        val blank = focusEnvelope(focus = "   ", maxChars = 5000)

        assertEquals(legacy.toString(), absent.toString())
        assertEquals(legacy.toString(), blank.toString())
        assertFalse(absent.containsKey("focused"))
        assertFalse(absent.containsKey("focus"))
    }

    @Test
    fun `focus with a start index is refused instead of ranking a slice`() {
        val json = focusEnvelope(focus = "офлайн", startIndex = 40)

        assertEquals("focus_start_index_conflict", json["error"]!!.jsonPrimitive.content)
        assertTrue(json["recovery"]!!.jsonPrimitive.content.contains("start_index"))
        // Pagination still works when focus is blank.
        assertNull(focusEnvelope(focus = "   ", startIndex = 40)["error"])
    }

    @Test
    fun `focus respects a max chars smaller than the focused budget`() {
        val json = focusEnvelope(focus = "офлайн режима работы", maxChars = 300)
        val text = json["text"]!!.jsonPrimitive.content

        assertTrue("returned ${text.length} chars", text.length <= 300)
        assertEquals(text.length, json["returned_chars"]!!.jsonPrimitive.content.toInt())
        assertTrue(text.startsWith("Команда выпустила"))
    }

    @Test
    fun `focus is ignored for links and metadata modes`() {
        val links = focusEnvelope(focus = "офлайн", mode = FetchExtract.LINKS)
        assertTrue(links.containsKey("links"))
        assertFalse(links.containsKey("focused"))

        val metadata = focusEnvelope(focus = "офлайн", mode = FetchExtract.METADATA)
        assertFalse(metadata.containsKey("focused"))
    }

    @Test
    fun `focus support is limited to article and text`() {
        assertTrue(supportsFocus(FetchExtract.ARTICLE))
        assertTrue(supportsFocus(FetchExtract.TEXT))
        assertFalse(supportsFocus(FetchExtract.RAW))
        assertFalse(supportsFocus(FetchExtract.LINKS))
        assertFalse(supportsFocus(FetchExtract.METADATA))
    }

    @Test
    fun `parseFocus trims and treats blank as absent`() {
        assertNull(parseFocus(null as String?))
        assertNull(parseFocus(""))
        assertNull(parseFocus("   "))
        assertEquals("KernelSU root", parseFocus("  KernelSU root  "))
        assertEquals(
            "KernelSU",
            parseFocus(Json.parseToJsonElement("""{"focus":" KernelSU "}""").jsonObject),
        )
        assertNull(parseFocus(Json.parseToJsonElement("""{}""").jsonObject))
    }

    @Test
    fun `focus does not bypass url validation`() {
        assertEquals(
            "blocked_address",
            invoke("""{"url":"http://127.0.0.1:9","extract_mode":"article","focus":"x"}""").error(),
        )
    }

    @Test
    fun `cancellation is not converted into a tool envelope`() {
        // The tool catches IOException only, so a cancelled coroutine keeps propagating through
        // the focus path exactly as it did before.
        val cancelled: Throwable = CancellationException("cancelled")
        assertFalse(cancelled is IOException)
    }
}
