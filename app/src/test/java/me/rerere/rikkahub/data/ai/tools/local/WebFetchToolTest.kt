package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // ---- query-focused extraction (focus parameter) ------------------------------------

    private fun envelopeJson(
        html: String,
        mode: FetchExtract = FetchExtract.ARTICLE,
        maxChars: Int = 8_000,
        startIndex: Int = 0,
        focus: String? = null,
    ): JsonObject = Json.parseToJsonElement(
        buildExtractEnvelope(
            status = 200,
            ok = true,
            finalUrl = "https://example.com/release",
            html = html,
            contentType = "text/html",
            mode = mode,
            maxChars = maxChars,
            startIndex = startIndex,
            bodyTruncated = false,
            headers = null,
            focus = focus,
        ),
    ).jsonObject

    private fun focusedHtml(): String {
        val menu = "Home About Products Pricing Careers Blog Support Sign in Register"
        val body = listOf(
            menu,
            "The 2.5.1 release changes how the migration queue is applied: steps now run " +
                "strictly in order, and older databases are upgraded without losing any user " +
                "messages stored on the device. This paragraph repeats the words migration " +
                "queue and 2.5.1 several times because they are the subject under discussion.",
            "Subscribe to our newsletter this month and get a discount on your subscription " +
                "plan, including extra storage and priority support from our team of experts.",
            "Another 2.5.1 fix addresses attachments that failed to render in the chat list " +
                "after the application was restarted from a cold start on some devices.",
        )
        return "<html><body><article>" +
            body.joinToString("") { "<p>$it</p>" } +
            "</article></body></html>"
    }

    @Test
    fun `focus reduces text output to the relevant paragraphs`() {
        val json = envelopeJson(focusedHtml(), focus = "how does the migration queue work in 2.5.1")

        assertEquals(true, json["focus_applied"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("focused", json["content_mode"]!!.jsonPrimitive.content)
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("migration queue"))
        assertFalse(json["text"]!!.jsonPrimitive.content.contains("Subscribe to our newsletter"))
    }

    @Test
    fun `empty focus keeps the previous envelope unchanged`() {
        val plain = envelopeJson(focusedHtml(), focus = null)
        val blank = envelopeJson(focusedHtml(), focus = "   ")

        assertEquals(false, plain["focus_applied"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("reader", plain["content_mode"]!!.jsonPrimitive.content)
        assertEquals(false, blank["focus_applied"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            plain["text"]!!.jsonPrimitive.content,
            blank["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `unmatched focus falls back to the reader text and says so`() {
        val json = envelopeJson(focusedHtml(), focus = "квантовая запутанность в кулинарии")

        assertEquals(false, json["focus_applied"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("reader", json["content_mode"]!!.jsonPrimitive.content)
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("migration queue"))
    }

    @Test
    fun `raw links and metadata are never focus filtered`() {
        val html = focusedHtml()

        val unfocusedLinks = envelopeJson(html, mode = FetchExtract.LINKS)
            .let { json -> json["links"]!!.jsonArray.map { it.jsonObject["href"]!!.jsonPrimitive.content } }

        for (mode in listOf(FetchExtract.LINKS, FetchExtract.METADATA)) {
            val json = envelopeJson(html, mode = mode, focus = "migration queue 2.5.1")

            // Non-text modes are untouched by focus: none of the focus/provenance keys appear.
            assertFalse(
                "focus must not add focus fields to ${mode.name.lowercase()}",
                json.containsKey("focus_applied"),
            )
            assertFalse(
                "focus must not add a fenced content value to ${mode.name.lowercase()}",
                json.containsKey("content"),
            )
            assertFalse(
                "focus must not mark ${mode.name.lowercase()} untrusted",
                json.containsKey("untrusted"),
            )

            if (mode == FetchExtract.LINKS) {
                val focusedLinks = json["links"]!!.jsonArray
                    .map { it.jsonObject["href"]!!.jsonPrimitive.content }
                assertEquals("focus must not change the link list", unfocusedLinks, focusedLinks)
            }
        }
        // RAW never reaches buildExtractEnvelope; its own branch is covered by the cap tests above.
        assertTrue(html.contains("migration queue"))
    }

    @Test
    fun `envelope carries the untrusted provenance fields and the fences`() {
        val json = envelopeJson(focusedHtml(), focus = "migration queue")
        val text = json["text"]!!.jsonPrimitive.content
        val sourceId = json["source_id"]!!.jsonPrimitive.content

        assertEquals(true, json["untrusted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("example.com", json["host"]!!.jsonPrimitive.content)
        assertTrue(json["retrieved_at"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(sourceId.startsWith("web-"))

        val wrapper = json["content"]!!.jsonPrimitive.content
        assertTrue(wrapper.startsWith("<<<UNTRUSTED_WEB_CONTENT source_id=\"$sourceId\">>>"))
        assertTrue(wrapper.endsWith("<<<END_UNTRUSTED_WEB_CONTENT>>>"))
        assertTrue(wrapper.contains(text))
    }

    @Test
    fun `start index pagination still holds without focus`() {
        val html = "<html><body><article><p>${"Prose sentence here. ".repeat(30)}</p></article></body></html>"

        val first = envelopeJson(html, maxChars = 50, startIndex = 0)
        assertEquals(true, first["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(50, first["next_start_index"]!!.jsonPrimitive.content.toInt())

        val next = envelopeJson(html, maxChars = 50, startIndex = 50)
        val whole = envelopeJson(html, maxChars = 100_000, startIndex = 0)
        assertEquals(
            whole["text"]!!.jsonPrimitive.content.take(100),
            first["text"]!!.jsonPrimitive.content + next["text"]!!.jsonPrimitive.content.take(50),
        )
    }
}
