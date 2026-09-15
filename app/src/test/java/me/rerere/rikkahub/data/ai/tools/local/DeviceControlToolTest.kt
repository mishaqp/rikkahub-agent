package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlToolTest {
    private fun delegate(
        name: String,
        payload: String,
        calls: MutableList<String>,
        approval: Boolean = false,
    ): Tool = Tool(
        name = name,
        description = name,
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        needsApproval = { approval },
        execute = { input ->
            calls += "$name:${input}"
            listOf(UIMessagePart.Text(payload))
        },
    )

    @Test
    fun `enabled action delegates legacy args without action and preserves output`() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = deviceControlTool(
            enabledActions = setOf("set_volume"),
            delegates = mapOf("set_volume" to delegate("set_volume", "{\"success\":true}", calls)),
        )

        val result = tool.execute(buildJsonObject {
            put("action", "set_volume")
            put("stream", "media")
            put("percent", 42)
        })

        assertEquals(listOf("set_volume:{\"stream\":\"media\",\"percent\":42}"), calls)
        assertEquals("{\"success\":true}", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `disabled action is refused even when composite tool exists`() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = deviceControlTool(
            enabledActions = setOf("vibrate"),
            delegates = mapOf(
                "vibrate" to delegate("vibrate", "ok", calls),
                "set_torch" to delegate("set_torch", "torch", calls),
            ),
        )

        val result = tool.execute(buildJsonObject {
            put("action", "set_torch")
            put("on", true)
        })
        val text = (result.single() as UIMessagePart.Text).text

        assertTrue(calls.isEmpty())
        assertTrue(text.contains("action_disabled"))
        assertTrue(text.contains("vibrate"))
        assertFalse(text.contains("\"set_torch\",\"available_actions\":[\"set_torch\"]"))
    }

    @Test
    fun `unknown action is refused without invoking a delegate`() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = deviceControlTool(
            enabledActions = setOf("get_brightness"),
            delegates = mapOf("get_brightness" to delegate("get_brightness", "ok", calls)),
        )

        val result = tool.execute(buildJsonObject { put("action", "not_real") })

        assertTrue(calls.isEmpty())
        assertTrue((result.single() as UIMessagePart.Text).text.contains("action_disabled"))
    }

    @Test
    fun `composite delegates approval decision to selected action`() {
        val calls = mutableListOf<String>()
        val tool = deviceControlTool(
            enabledActions = setOf("get_volume", "set_volume"),
            delegates = mapOf(
                "get_volume" to delegate("get_volume", "read", calls, approval = false),
                "set_volume" to delegate("set_volume", "write", calls, approval = true),
            ),
        )

        assertFalse(tool.needsApproval(buildJsonObject { put("action", "get_volume") }))
        assertTrue(tool.needsApproval(buildJsonObject {
            put("action", "set_volume")
            put("stream", "media")
            put("percent", 50)
        }))
        assertFalse(tool.needsApproval(buildJsonObject { put("action", "set_torch") }))
    }
}
