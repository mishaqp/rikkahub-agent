package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.InputSchema
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebExtractToolTest {

    @Test
    fun `tool is named web_extract and defaults to article`() {
        val tool = webExtractTool(OkHttpClient())

        assertEquals("web_extract", tool.name)
        assertTrue(tool.description.contains("article"))
    }

    @Test
    fun `schema accepts source_id and does not require url`() {
        val schema = webExtractTool(OkHttpClient()).parameters() as InputSchema.Obj

        assertFalse(
            "url must not be required - source_id alone is a valid call",
            schema.required?.contains("url") == true,
        )
        assertTrue(schema.properties.containsKey("url"))
        assertTrue(schema.properties.containsKey("source_id"))
    }

    @Test
    fun `description steers away from raw markup`() {
        val tool = webExtractTool(OkHttpClient())

        assertTrue(tool.description.lowercase().contains("readable"))
    }
}
