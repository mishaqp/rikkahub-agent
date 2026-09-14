package me.rerere.search.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class WebSourceIdTest {

    @Test
    fun `two ids for the same url differ`() {
        // The id is not a function of the URL any more: reading the same page twice must not
        // hand the caller the same handle.
        val first = WebSourceId.newId()
        val second = WebSourceId.newId()

        assertNotEquals(first, second)
    }

    @Test
    fun `an id has the expected shape`() {
        val id = WebSourceId.newId()

        assertTrue(id.startsWith("src_"))
        assertEquals(WebSourceId.LENGTH, id.length)
        assertEquals(4 + WebSourceId.RANDOM_BYTES * 2, id.length)
        assertTrue("id was $id", WebSourceId.isWellFormed(id))
        assertTrue(id.removePrefix("src_").all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `an id carries nothing from the url or hostname`() {
        val url = "https://unique-host-name-xyz.example/secret-path?token=abcdef123456"
        val id = WebSourceId.newId()

        assertFalse(id.contains("unique-host-name-xyz"))
        assertFalse(id.contains("example"))
        assertFalse(id.contains("secret-path"))
        assertFalse(id.contains("abcdef123456"))
        assertFalse(id.contains(url))
    }

    @Test
    fun `ids do not collide across a thousand draws`() {
        val ids = HashSet<String>(2048)
        repeat(1000) { ids.add(WebSourceId.newId()) }

        assertEquals(1000, ids.size)
        assertTrue(ids.all { WebSourceId.isWellFormed(it) })
    }

    @Test
    fun `a seeded generator is reproducible, which is what the test seam is for`() {
        val first = WebSourceId.deterministic(42L)
        val second = WebSourceId.deterministic(42L)

        val a = (1..5).map { first() }
        val b = (1..5).map { second() }

        assertEquals(a, b)
        assertEquals(a.toSet().size, 5)
        assertTrue(WebSourceId.isWellFormed(a.first()))
    }

    @Test
    fun `the format check rejects the shapes an id never has`() {
        assertFalse(WebSourceId.isWellFormed(""))
        assertFalse(WebSourceId.isWellFormed("src_"))
        assertFalse(WebSourceId.isWellFormed("web-0123456789abcdef"))
        assertFalse(WebSourceId.isWellFormed("src_" + "A".repeat(32)))
        assertFalse(WebSourceId.isWellFormed("src_" + "g".repeat(32)))
        assertFalse(WebSourceId.isWellFormed("src_" + "0".repeat(31)))
        assertTrue(WebSourceId.isWellFormed("src_" + "0".repeat(32)))
    }

    @Test
    fun `a source stored under a random id is retrievable by that id`() {
        val cache = BoundedWebSourceCache()
        val id = WebSourceId.newId()
        val source = WebSource(
            sourceId = id,
            url = "https://example.com/a",
            status = 200,
            mode = ExtractMode.ARTICLE,
            text = "Clean article text.",
            storedAtMillis = System.currentTimeMillis(),
        )

        assertTrue(cache.put(source))
        assertNotNull(cache.get(id))
        assertEquals(id, cache.get(id)?.sourceId)
        assertNull(cache.get(WebSourceId.newId()))
    }
}
