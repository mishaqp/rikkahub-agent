package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoToolTest {
    private fun delegate(name: String, payload: String, calls: MutableList<String>): Tool = Tool(
        name = name,
        description = name,
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { input ->
            calls += "$name:${input}"
            listOf(UIMessagePart.Text(payload))
        },
    )

    @Test
    fun `enabled section delegates with empty legacy args and preserves output`() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = deviceInfoTool(
            enabledSections = setOf("battery"),
            delegates = mapOf("battery" to delegate("get_battery_status", "{\"percent\":50}", calls)),
        )

        val result = tool.execute(buildJsonObject { put("section", "battery") })

        assertEquals(listOf("get_battery_status:{}"), calls)
        assertEquals("{\"percent\":50}", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `disabled section is refused even when composite tool exists`() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = deviceInfoTool(
            enabledSections = setOf("wifi"),
            delegates = mapOf(
                "battery" to delegate("get_battery_status", "battery", calls),
                "wifi" to delegate("get_wifi_info", "wifi", calls),
            ),
        )

        val result = tool.execute(buildJsonObject { put("section", "battery") })
        val text = (result.single() as UIMessagePart.Text).text

        assertTrue(calls.isEmpty())
        assertTrue(text.contains("section_disabled"))
        assertTrue(text.contains("wifi"))
    }

    @Test
    fun `unknown section is refused without invoking a delegate`() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = deviceInfoTool(
            enabledSections = setOf("storage"),
            delegates = mapOf("storage" to delegate("get_storage_info", "storage", calls)),
        )

        val result = tool.execute(buildJsonObject { put("section", "not_real") })

        assertTrue(calls.isEmpty())
        assertTrue((result.single() as UIMessagePart.Text).text.contains("section_disabled"))
    }
}
