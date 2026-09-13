package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationFallbackTest {

    @Test
    fun `fallback back preserves the original forward destination`() {
        val snapshot = HistoryFallbackSnapshot(
            urls = listOf(
                "https://example.test/one",
                "https://example.test/two",
                "https://example.test/three",
            ),
            currentIndex = 2,
            currentUrl = "https://example.test/three",
        )

        val back = checkNotNull(snapshot.target(forward = false))
        assertEquals(1, back.index)
        assertEquals("https://example.test/two", back.url)
        snapshot.commit(back, loadedUrl = "https://redirected.test/two")
        assertTrue(snapshot.matches("https://redirected.test/two"))

        val forward = checkNotNull(snapshot.target(forward = true))
        assertEquals(2, forward.index)
        assertEquals("https://example.test/three", forward.url)
        snapshot.commit(forward)
        assertTrue(snapshot.matches("https://example.test/three"))
        assertNull(snapshot.target(forward = true))
    }

    @Test
    fun `fallback snapshot is ignored after an external navigation`() {
        val snapshot = HistoryFallbackSnapshot(
            urls = listOf("https://example.test/one", "https://example.test/two"),
            currentIndex = 1,
            currentUrl = "https://example.test/two",
        )

        assertFalse(snapshot.matches("https://elsewhere.test/"))
    }

    @Test
    fun `open waits until a different destination replaces the old page`() {
        assertFalse(
            navigationHasStarted(
                previousUrl = "https://example.com/",
                requestedUrl = "https://httpbin.org/forms/post",
                currentUrl = "https://example.com/",
            ),
        )
        assertTrue(
            navigationHasStarted(
                previousUrl = "https://example.com/",
                requestedUrl = "https://httpbin.org/forms/post",
                currentUrl = "https://httpbin.org/forms/post",
            ),
        )
        assertTrue(
            navigationHasStarted(
                previousUrl = "https://example.com/",
                requestedUrl = "https://httpbin.org/forms/post",
                currentUrl = "https://redirected.example/final",
            ),
        )
    }

    @Test
    fun `open accepts a fresh or same-url navigation but not about blank`() {
        assertTrue(
            navigationHasStarted(
                previousUrl = null,
                requestedUrl = "https://example.com",
                currentUrl = "https://example.com/",
            ),
        )
        assertTrue(
            navigationHasStarted(
                previousUrl = "https://example.com/",
                requestedUrl = "https://example.com",
                currentUrl = "https://example.com/",
            ),
        )
        assertFalse(
            navigationHasStarted(
                previousUrl = null,
                requestedUrl = "https://example.com",
                currentUrl = "about:blank",
            ),
        )
    }
}
