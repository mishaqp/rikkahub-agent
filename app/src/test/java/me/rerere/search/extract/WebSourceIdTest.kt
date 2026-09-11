package me.rerere.search.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Determinism and normalisation contract for the web source identifier. Pure JVM.
 */
class WebSourceIdTest {

    @Test
    fun `same url yields the same id on every call`() {
        val url = "https://example.com/article"
        assertEquals(WebSourceId.of(url), WebSourceId.of(url))
        assertEquals(WebSourceId.of(url), WebSourceId.of(url))
    }

    @Test
    fun `id is short and url safe`() {
        val id = WebSourceId.of("https://example.com/article")

        assertTrue(id.startsWith("web-"))
        assertEquals(20, id.length)
        assertTrue(id.removePrefix("web-").all { it in "0123456789abcdef" })
    }

    @Test
    fun `fragment does not change the identity of a document`() {
        assertEquals(
            WebSourceId.of("https://example.com/a"),
            WebSourceId.of("https://example.com/a#section-3"),
        )
    }

    @Test
    fun `scheme and host case are folded but the path keeps its case`() {
        assertEquals(
            WebSourceId.of("https://Example.COM/Path"),
            WebSourceId.of("HTTPS://example.com/Path"),
        )
        assertNotEquals(
            WebSourceId.of("https://example.com/Path"),
            WebSourceId.of("https://example.com/path"),
        )
    }

    @Test
    fun `trailing slashes are dropped`() {
        assertEquals(
            WebSourceId.of("https://example.com/docs"),
            WebSourceId.of("https://example.com/docs///"),
        )
    }

    @Test
    fun `different urls get different ids`() {
        assertNotEquals(
            WebSourceId.of("https://example.com/a"),
            WebSourceId.of("https://example.com/b"),
        )
    }

    @Test
    fun `host extraction lowercases and tolerates junk`() {
        assertEquals("example.com", WebSourceId.host("https://EXAMPLE.com/x"))
        assertEquals("", WebSourceId.host("not a url"))
    }
}
