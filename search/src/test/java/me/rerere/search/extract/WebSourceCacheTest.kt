package me.rerere.search.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourceCacheTest {

    /** A fixed instant: entries only expire when a test moves the clock, never by accident. */
    private val now = 1_700_000_000_000L

    private fun source(
        id: String = "web-0000000000000001",
        url: String = "https://example.com/a",
        text: String = "Clean article text.",
        storedAt: Long = now,
    ) = WebSource(
        sourceId = id,
        url = url,
        status = 200,
        mode = ExtractMode.ARTICLE,
        title = "Title",
        siteName = "Site",
        description = "Description",
        language = "en",
        text = text,
        bodyTruncated = false,
        storedAtMillis = storedAt,
    )

    @Test
    fun `a stored source is returned unchanged`() {
        val cache = BoundedWebSourceCache(clock = { 1_000L })
        val stored = source(storedAt = 1_000L)

        assertTrue(cache.put(stored))
        assertEquals(stored, cache.get(stored.sourceId))
        assertEquals(1, cache.size())
        assertTrue(cache.contains(stored.sourceId))
    }

    @Test
    fun `an unknown id misses`() {
        val cache = BoundedWebSourceCache()
        assertNull(cache.get("web-ffffffffffffffff"))
        assertFalse(cache.contains("web-ffffffffffffffff"))
    }

    @Test
    fun `an entry expires after its TTL`() {
        var now = 0L
        val cache = BoundedWebSourceCache(clock = { now })
        val stored = source(storedAt = 0L)
        cache.put(stored)

        now = BoundedWebSourceCache.TTL_MILLIS
        assertNotNull("still live at exactly the TTL boundary", cache.get(stored.sourceId))

        now = BoundedWebSourceCache.TTL_MILLIS + 1
        assertNull(cache.get(stored.sourceId))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.bytes())
    }

    @Test
    fun `expired entries are purged when a new one is stored`() {
        var now = 0L
        val cache = BoundedWebSourceCache(clock = { now })
        cache.put(source(id = "web-0000000000000001", storedAt = 0L))
        val stale = cache.bytes()

        now = BoundedWebSourceCache.TTL_MILLIS + 1
        cache.put(source(id = "web-0000000000000002", storedAt = now))

        assertEquals(1, cache.size())
        assertTrue(cache.bytes() < stale + stale)
    }

    @Test
    fun `entry count is capped and the least recently used source is evicted`() {
        val cache = BoundedWebSourceCache(
            maxEntries = 3,
            maxBytes = Long.MAX_VALUE,
            clock = { now },
        )

        (1..3).forEach { cache.put(source(id = "web-000000000000000$it", text = "text $it")) }
        // Touch the first so the second becomes the least recently used.
        assertNotNull(cache.get("web-0000000000000001"))
        cache.put(source(id = "web-0000000000000004"))

        assertEquals(3, cache.size())
        assertNotNull(cache.get("web-0000000000000001"))
        assertNull(cache.get("web-0000000000000002"))
        assertNotNull(cache.get("web-0000000000000004"))
    }

    @Test
    fun `total byte budget is enforced`() {
        // Each entry (id + url + metadata + 40 chars of text) is a little over 100 bytes, so a
        // 250-byte budget holds two of them and the third evicts the least recently used.
        val cache = BoundedWebSourceCache(maxEntries = 100, maxBytes = 250, clock = { now })
        cache.put(source(id = "web-0000000000000001", text = "x".repeat(40)))
        cache.put(source(id = "web-0000000000000002", text = "y".repeat(40)))
        cache.put(source(id = "web-0000000000000003", text = "z".repeat(40)))

        assertTrue("bytes=${cache.bytes()}", cache.bytes() <= 250)
        assertEquals(2, cache.size())
        assertNull(cache.get("web-0000000000000001"))
        assertNotNull(cache.get("web-0000000000000003"))
    }

    @Test
    fun `a source larger than the whole budget is not stored`() {
        val cache = BoundedWebSourceCache(maxEntries = 10, maxBytes = 50)

        assertFalse(cache.put(source(text = "x".repeat(200))))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.bytes())
    }

    @Test
    fun `defaults are the documented policy`() {
        val cache = BoundedWebSourceCache()
        assertEquals(24, BoundedWebSourceCache.MAX_ENTRIES)
        assertEquals(4L * 1024 * 1024, BoundedWebSourceCache.MAX_BYTES)
        assertEquals(45L * 60 * 1000, BoundedWebSourceCache.TTL_MILLIS)
        assertEquals(BoundedWebSourceCache.TTL_MILLIS, cache.ttlMillis())
    }

    @Test
    fun `remove and clear drop entries and release their bytes`() {
        val cache = BoundedWebSourceCache(clock = { now })
        val a = source(id = "web-0000000000000001")
        val b = source(id = "web-0000000000000002")
        cache.put(a)
        cache.put(b)

        cache.remove(a.sourceId)
        assertNull(cache.get(a.sourceId))
        assertNotNull(cache.get(b.sourceId))

        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0L, cache.bytes())
    }

    @Test
    fun `the shared cache is a single instance the tools can rely on`() {
        webSourceCache.clear()
        webSourceCache.put(source(id = "web-00000000000000aa", storedAt = System.currentTimeMillis()))
        assertNotNull(webSourceCache.get("web-00000000000000aa"))
        webSourceCache.clear()
        assertNull(webSourceCache.get("web-00000000000000aa"))
    }

    @Test
    fun `concurrent access neither throws nor corrupts the bookkeeping`() {
        val cache = BoundedWebSourceCache(maxEntries = 8, maxBytes = 64 * 1024, clock = { now })
        val threads = (1..8).map { t ->
            Thread {
                repeat(200) { i ->
                    val id = String.format("web-%016x", t)
                    cache.put(source(id = id, text = "text $i from thread $t"))
                    cache.get(id)
                    cache.get(String.format("web-%016x", (t % 8) + 1))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(cache.size() <= 8)
        assertTrue(cache.bytes() >= 0)
    }
}
