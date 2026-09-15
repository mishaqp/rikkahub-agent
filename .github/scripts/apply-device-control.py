from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise RuntimeError(f"{label}: start marker not found")
    end = text.find(end_marker, start)
    if end < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:start] + replacement + text[end:]

root = Path('.')

# ---------------------------------------------------------------------------
# DeviceControlTool.kt
# ---------------------------------------------------------------------------
device_control = r'''package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

internal val DEVICE_CONTROL_ACTION_ORDER = listOf(
    "set_torch",
    "vibrate",
    "get_brightness",
    "set_brightness",
    "get_volume",
    "set_volume",
)

/**
 * Composite for small device-control/read actions that previously occupied six schemas.
 *
 * Existing assistant toggles stay granular. [enabledActions] is derived from Torch,
 * Vibrate, Brightness and Volume, and both the advertised enum and runtime dispatch are
 * restricted to that exact subset. Delegating to the legacy Tool objects preserves their
 * Android implementation, return payloads, permission checks, headless streaming, and
 * action-level approval semantics instead of duplicating them here.
 */
fun deviceControlTool(
    context: Context,
    enabledActions: Set<String>,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = deviceControlTool(
    enabledActions = enabledActions,
    delegates = mapOf(
        "set_torch" to torchTool(context),
        "vibrate" to vibrateTool(context),
        "get_brightness" to getBrightnessTool(context),
        "set_brightness" to setBrightnessTool(context, invocationContext, streamer),
        "get_volume" to getVolumeTool(context),
        "set_volume" to setVolumeTool(context, invocationContext, streamer),
    ),
)

private fun delegateArgs(input: JsonElement): JsonObject =
    JsonObject(input.jsonObject.filterKeys { it != "action" })

/** JVM-test seam: production uses the overload above with the real Android-backed tools. */
internal fun deviceControlTool(
    enabledActions: Set<String>,
    delegates: Map<String, Tool>,
): Tool {
    val allowedActions = DEVICE_CONTROL_ACTION_ORDER.filter { it in enabledActions }
    require(allowedActions.isNotEmpty()) { "device_control requires at least one enabled action" }

    fun availableActionsJson() = buildJsonArray {
        allowedActions.forEach { add(JsonPrimitive(it)) }
    }

    return Tool(
        name = "device_control",
        description = buildString {
            append("Read or control basic device hardware using one enabled action. Available actions: ")
            append(allowedActions.joinToString(", "))
            append(". Use one action per call; only include parameters required by that action.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "Device control action to perform")
                        put("enum", availableActionsJson())
                    })
                    if ("set_torch" in allowedActions) {
                        put("on", buildJsonObject {
                            put("type", "boolean")
                            put("description", "set_torch: true to turn the torch on, false to turn it off")
                        })
                    }
                    if ("vibrate" in allowedActions) {
                        put("duration_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "vibrate: duration in milliseconds (default 500, max 5000)")
                        })
                        put("pattern", buildJsonObject {
                            put("type", "array")
                            put("description", "vibrate: alternating off/on milliseconds (max 20 entries); mutually exclusive with duration_ms")
                            put("items", buildJsonObject { put("type", "integer") })
                        })
                    }
                    if ("set_brightness" in allowedActions) {
                        put("value", buildJsonObject {
                            put("type", "integer")
                            put("description", "set_brightness: target brightness, 1-255")
                        })
                    }
                    if ("get_volume" in allowedActions || "set_volume" in allowedActions) {
                        put("stream", buildJsonObject {
                            put("type", "string")
                            put("description", "get/set_volume: media, ring, notification, alarm, voice_call, or system; get defaults to media")
                            put("enum", buildJsonArray {
                                listOf("media", "ring", "notification", "alarm", "voice_call", "system")
                                    .forEach { add(JsonPrimitive(it)) }
                            })
                        })
                    }
                    if ("set_volume" in allowedActions) {
                        put("percent", buildJsonObject {
                            put("type", "integer")
                            put("description", "set_volume: target volume percentage, 0-100")
                        })
                    }
                },
                required = listOf("action"),
            )
        },
        needsApproval = { input ->
            val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull
            if (action == null || action !in allowedActions) {
                false
            } else {
                delegates[action]?.needsApproval(delegateArgs(input)) ?: false
            }
        },
        execute = { input ->
            val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull
            when {
                action == null -> listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "missing_action")
                            put("available_actions", availableActionsJson())
                        }.toString()
                    )
                )

                action !in allowedActions -> listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "action_disabled")
                            put("action", action)
                            put("available_actions", availableActionsJson())
                        }.toString()
                    )
                )

                else -> {
                    val delegate = delegates[action]
                    if (delegate == null) {
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("error", "action_unavailable")
                                    put("action", action)
                                }.toString()
                            )
                        )
                    } else {
                        delegate.execute(delegateArgs(input))
                    }
                }
            }
        },
    )
}
'''
(root / 'app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/DeviceControlTool.kt').write_text(device_control)

# ---------------------------------------------------------------------------
# DeviceControlToolTest.kt
# ---------------------------------------------------------------------------
device_control_test = r'''package me.rerere.rikkahub.data.ai.tools.local

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
'''
(root / 'app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/DeviceControlToolTest.kt').write_text(device_control_test)

# ---------------------------------------------------------------------------
# LocalTools.kt
# ---------------------------------------------------------------------------
p = root / 'app/src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt'
t = p.read_text()
for line in [
    'import me.rerere.rikkahub.data.ai.tools.local.getBrightnessTool\n',
    'import me.rerere.rikkahub.data.ai.tools.local.getVolumeTool\n',
    'import me.rerere.rikkahub.data.ai.tools.local.setBrightnessTool\n',
    'import me.rerere.rikkahub.data.ai.tools.local.setVolumeTool\n',
    'import me.rerere.rikkahub.data.ai.tools.local.torchTool\n',
    'import me.rerere.rikkahub.data.ai.tools.local.vibrateTool\n',
]:
    if line not in t:
        raise RuntimeError(f'LocalTools import missing: {line.strip()}')
    t = t.replace(line, '', 1)
t = replace_once(
    t,
    'import me.rerere.rikkahub.data.ai.tools.local.deviceInfoTool\n',
    'import me.rerere.rikkahub.data.ai.tools.local.deviceInfoTool\nimport me.rerere.rikkahub.data.ai.tools.local.deviceControlTool\n',
    'LocalTools deviceControl import',
)
t = replace_once(
    t,
    'private val TOP_TOOL_EXAMPLES: Map<String, String> = mapOf(\n    "device_info" to "device_info(section=\\"battery\\")",\n',
    'private val TOP_TOOL_EXAMPLES: Map<String, String> = mapOf(\n    "device_info" to "device_info(section=\\"battery\\")",\n    "device_control" to "device_control(action=\\"set_torch\\", on=true)",\n',
    'LocalTools example',
)
start = '        if (options.contains(LocalToolOption.Torch)) {\n'
end = '        if (options.contains(LocalToolOption.MediaPlayer)) {\n'
replacement = '''        val deviceControlActions = buildSet {
            if (options.contains(LocalToolOption.Torch)) add("set_torch")
            if (options.contains(LocalToolOption.Vibrate)) add("vibrate")
            if (options.contains(LocalToolOption.Brightness)) {
                add("get_brightness")
                add("set_brightness")
            }
            if (options.contains(LocalToolOption.Volume)) {
                add("get_volume")
                add("set_volume")
            }
        }
        if (deviceControlActions.isNotEmpty()) {
            tools.add(
                deviceControlTool(
                    context = context,
                    enabledActions = deviceControlActions,
                    invocationContext = invocationContext,
                    streamer = interactiveToolStreamer,
                )
            )
        }
'''
t = replace_between(t, start, end, replacement, 'LocalTools control registration')
p.write_text(t)

# ---------------------------------------------------------------------------
# ToolNameAliases.kt
# ---------------------------------------------------------------------------
p = root / 'app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolNameAliases.kt'
t = p.read_text()
t = replace_once(
    t,
    ' * [RULES] now contains the first production composite migration: the six legacy\n * read-only device-info names map to device_info(section=...). Other names remain strict no-op.\n',
    ' * [RULES] contains the production composite migrations for device_info and device_control.\n * Unrelated names remain strict no-op.\n',
    'aliases docs',
)
marker = ''' private fun deviceInfoSection(section: String): ArgsTransform = ArgsTransform { legacyArgs ->
 require(legacyArgs is JsonObject) { "legacy device-info args must be a JSON object" }
 buildJsonObject { put("section", section) }
 }
'''
insert = marker + '''
 private fun deviceControlAction(action: String): ArgsTransform = ArgsTransform { legacyArgs ->
 require(legacyArgs is JsonObject) { "legacy device-control args must be a JSON object" }
 buildJsonObject {
 put("action", action)
 legacyArgs.forEach { (key, value) ->
 if (key != "action") put(key, value)
 }
 }
 }
'''
t = replace_once(t, marker, insert, 'aliases transform helper')
rule_marker = ''' "get_storage_info" to CompatibilityRule("device_info", deviceInfoSection("storage")),
 "list_sensors" to CompatibilityRule("device_info", deviceInfoSection("sensors")),
 )
'''
rule_insert = ''' "get_storage_info" to CompatibilityRule("device_info", deviceInfoSection("storage")),
 "list_sensors" to CompatibilityRule("device_info", deviceInfoSection("sensors")),
 "set_torch" to CompatibilityRule("device_control", deviceControlAction("set_torch"), approvalName = "set_torch"),
 "vibrate" to CompatibilityRule("device_control", deviceControlAction("vibrate"), approvalName = "vibrate"),
 "get_brightness" to CompatibilityRule("device_control", deviceControlAction("get_brightness"), approvalName = "get_brightness"),
 "set_brightness" to CompatibilityRule("device_control", deviceControlAction("set_brightness"), approvalName = "set_brightness"),
 "get_volume" to CompatibilityRule("device_control", deviceControlAction("get_volume"), approvalName = "get_volume"),
 "set_volume" to CompatibilityRule("device_control", deviceControlAction("set_volume"), approvalName = "set_volume"),
 )
'''
t = replace_once(t, rule_marker, rule_insert, 'aliases rules')
p.write_text(t)

# ---------------------------------------------------------------------------
# GenerationHandler.kt: selector-aware loop policy for composite read actions.
# Also repairs the device_info freshness/read-only semantics after its earlier merge.
# ---------------------------------------------------------------------------
p = root / 'app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt'
t = p.read_text()
t = replace_once(
    t,
    'import kotlinx.serialization.json.JsonPrimitive\n',
    'import kotlinx.serialization.json.JsonPrimitive\nimport kotlinx.serialization.json.contentOrNull\nimport kotlinx.serialization.json.jsonObject\nimport kotlinx.serialization.json.jsonPrimitive\n',
    'GenerationHandler json imports',
)
map_end = ''')

/**
 * UI-observation tools that read screen/device state without changing it. Used by the loop
'''
helper = ''')

/**
 * Composite tools share one provider-visible name across read and write actions. Loop-guard
 * policy must still distinguish the old read identities: otherwise device_info/device_control
 * reads lose freshness TTLs, while treating all device_control calls as read-only would let
 * repeated writes reset incorrectly. The signature stays canonical; only the policy bucket is
 * projected back to the legacy read name.
 */
internal fun loopGuardPolicyToolName(canonicalToolName: String, canonicalInput: String): String {
    fun selector(key: String): String? = runCatching {
        Json.parseToJsonElement(canonicalInput.ifBlank { "{}" })
            .jsonObject[key]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    return when (canonicalToolName) {
        "device_info" -> when (selector("section")) {
            "battery" -> "get_battery_status"
            "audio" -> "get_audio_info"
            "telephony" -> "get_telephony_info"
            "wifi" -> "get_wifi_info"
            "storage" -> "get_storage_info"
            else -> canonicalToolName
        }
        "device_control" -> when (val action = selector("action")) {
            "get_brightness", "get_volume" -> action
            else -> canonicalToolName
        }
        else -> canonicalToolName
    }
}

/**
 * UI-observation tools that read screen/device state without changing it. Used by the loop
'''
t = replace_once(t, map_end, helper, 'GenerationHandler policy helper')
old_prior = '''                                    PriorToolCall(
                                        resolvedPrior.canonicalName,
                                        resolvedPrior.signature,
                                        epochMs,
                                    )
'''
new_prior = '''                                    PriorToolCall(
                                        loopGuardPolicyToolName(
                                            resolvedPrior.canonicalName,
                                            resolvedPrior.canonicalInput,
                                        ),
                                        resolvedPrior.signature,
                                        epochMs,
                                    )
'''
t = replace_once(t, old_prior, new_prior, 'GenerationHandler prior policy')
old_eval = '''                        val loopDecision = LoopGuard.evaluate(
                            priorCalls = priorCalls,
                            toolName = canonicalToolName,
                            signature = signature,
                            nowMs = System.currentTimeMillis(),
                        )
'''
new_eval = '''                        val loopDecision = LoopGuard.evaluate(
                            priorCalls = priorCalls,
                            toolName = loopGuardPolicyToolName(
                                canonicalToolName,
                                resolvedCall.canonicalInput,
                            ),
                            signature = signature,
                            nowMs = System.currentTimeMillis(),
                        )
'''
t = replace_once(t, old_eval, new_eval, 'GenerationHandler current policy')
p.write_text(t)

# ---------------------------------------------------------------------------
# LoopGuardTest.kt
# ---------------------------------------------------------------------------
p = root / 'app/src/test/java/me/rerere/rikkahub/data/ai/LoopGuardTest.kt'
t = p.read_text()
insert_before = '\n    @Test\n    fun freshnessTtlBypass_letsStaleRealtimeReadThrough() {'
new_tests = r'''
    @Test
    fun compositePolicy_keepsDeviceInfoFreshnessIdentity() {
        assertEquals(
            "get_battery_status",
            loopGuardPolicyToolName("device_info", "{\"section\":\"battery\"}"),
        )
        assertEquals(
            "get_storage_info",
            loopGuardPolicyToolName("device_info", "{\"section\":\"storage\"}"),
        )
        // Sensors never had a freshness bucket before the composite migration.
        assertEquals(
            "device_info",
            loopGuardPolicyToolName("device_info", "{\"section\":\"sensors\"}"),
        )
    }

    @Test
    fun compositePolicy_onlyTreatsDeviceControlReadsAsObservers() {
        assertEquals(
            "get_brightness",
            loopGuardPolicyToolName("device_control", "{\"action\":\"get_brightness\"}"),
        )
        assertEquals(
            "get_volume",
            loopGuardPolicyToolName("device_control", "{\"action\":\"get_volume\",\"stream\":\"media\"}"),
        )
        assertEquals(
            "device_control",
            loopGuardPolicyToolName("device_control", "{\"action\":\"set_volume\",\"stream\":\"media\",\"percent\":50}"),
        )
    }
'''
t = replace_once(t, insert_before, '\n' + new_tests + insert_before, 'LoopGuard tests')
p.write_text(t)

# ---------------------------------------------------------------------------
# ToolNameAliasesTest.kt
# ---------------------------------------------------------------------------
p = root / 'app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolNameAliasesTest.kt'
t = p.read_text()
t = replace_once(
    t,
    ' * 1. Contract against the shipped table: the six migrated device-info names resolve\n * to device_info(section=...), while every unrelated name remains a strict byte-level no-op.\n',
    ' * 1. Contract against the shipped table: device-info and device-control legacy names\n * resolve to their composite schemas, while unrelated names remain strict byte-level no-op.\n',
    'aliases test docs',
)
old_prod_start = ''' @Test
 fun `production rules contain exactly the six device info migrations`() {
 val expected = mapOf(
 "get_battery_status" to "battery",
 "get_audio_info" to "audio",
 "get_telephony_info" to "telephony",
 "get_wifi_info" to "wifi",
 "get_storage_info" to "storage",
 "list_sensors" to "sensors",
 )
 assertEquals(expected.keys, ToolNameAliases.RULES.keys)
 assertTrue(ToolNameAliases.validate(ToolNameAliases.RULES).isEmpty())
 expected.forEach { (legacy, section) ->
 val resolved = ToolNameAliases.resolveCall(legacy, "{}")
 assertEquals("device_info", resolved.canonicalName)
 assertEquals(section, Json.parseToJsonElement(resolved.canonicalInput).jsonObject["section"]?.jsonPrimitive?.content)
 assertEquals("device_info", resolved.approvalName)
 assertNull(resolved.resolutionError)
 assertTrue(resolved.aliased)
 }
 }
'''
new_prod = ''' @Test
 fun `production rules contain device info and device control migrations`() {
 val deviceInfo = mapOf(
 "get_battery_status" to "battery",
 "get_audio_info" to "audio",
 "get_telephony_info" to "telephony",
 "get_wifi_info" to "wifi",
 "get_storage_info" to "storage",
 "list_sensors" to "sensors",
 )
 val deviceControlInputs = mapOf(
 "set_torch" to "{\\\"on\\\":true}",
 "vibrate" to "{\\\"duration_ms\\\":250}",
 "get_brightness" to "{}",
 "set_brightness" to "{\\\"value\\\":160}",
 "get_volume" to "{\\\"stream\\\":\\\"media\\\"}",
 "set_volume" to "{\\\"stream\\\":\\\"media\\\",\\\"percent\\\":50}",
 )
 assertEquals(deviceInfo.keys + deviceControlInputs.keys, ToolNameAliases.RULES.keys)
 assertTrue(ToolNameAliases.validate(ToolNameAliases.RULES).isEmpty())
 deviceInfo.forEach { (legacy, section) ->
 val resolved = ToolNameAliases.resolveCall(legacy, "{}")
 assertEquals("device_info", resolved.canonicalName)
 assertEquals(section, Json.parseToJsonElement(resolved.canonicalInput).jsonObject["section"]?.jsonPrimitive?.content)
 assertEquals("device_info", resolved.approvalName)
 assertNull(resolved.resolutionError)
 assertTrue(resolved.aliased)
 }
 deviceControlInputs.forEach { (legacy, input) ->
 val resolved = ToolNameAliases.resolveCall(legacy, input)
 assertEquals("device_control", resolved.canonicalName)
 val canonical = Json.parseToJsonElement(resolved.canonicalInput).jsonObject
 assertEquals(legacy, canonical["action"]?.jsonPrimitive?.content)
 Json.parseToJsonElement(input).jsonObject.forEach { (key, value) ->
 assertEquals(value, canonical[key])
 }
 assertEquals(legacy, resolved.approvalName)
 assertNull(resolved.resolutionError)
 assertTrue(resolved.aliased)
 }
 }

 @Test
 fun `legacy device control action cannot override its fixed canonical action`() {
 val resolved = ToolNameAliases.resolveCall(
 "set_torch",
 "{\\\"action\\\":\\\"set_volume\\\",\\\"on\\\":true}",
 )
 val canonical = Json.parseToJsonElement(resolved.canonicalInput).jsonObject
 assertEquals("device_control", resolved.canonicalName)
 assertEquals("set_torch", canonical["action"]?.jsonPrimitive?.content)
 assertEquals(true, canonical["on"]?.jsonPrimitive?.content?.toBoolean())
 }
'''
t = replace_once(t, old_prod_start, new_prod, 'aliases production tests')
t = replace_once(
    t,
    ' val tools = listOf(tool("files"), tool("web"), tool("device_info"))\n',
    ' val tools = listOf(tool("files"), tool("web"), tool("device_info"), tool("device_control"))\n',
    'aliases lookup tools',
)
t = replace_once(
    t,
    ' assertEquals("device_info", ToolNameAliases.resolveTool(tools, "get_battery_status")?.name)\n',
    ' assertEquals("device_info", ToolNameAliases.resolveTool(tools, "get_battery_status")?.name)\n assertEquals("device_control", ToolNameAliases.resolveTool(tools, "set_torch")?.name)\n',
    'aliases lookup assertion',
)
p.write_text(t)

# ---------------------------------------------------------------------------
# AssistantLocalToolPage.kt: same compact expandable UX as Device Info.
# ---------------------------------------------------------------------------
p = root / 'app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt'
t = p.read_text()
count_marker = '    val enabledDeviceInfoCount = deviceInfoOptions.count { it in assistant.localTools }\n'
count_insert = count_marker + '''    var deviceControlExpanded by remember { mutableStateOf(false) }
    val deviceControlOptions = remember {
        setOf(
            LocalToolOption.Torch,
            LocalToolOption.Vibrate,
            LocalToolOption.Brightness,
            LocalToolOption.Volume,
        )
    }
    val enabledDeviceControlCount = deviceControlOptions.count { it in assistant.localTools }
'''
t = replace_once(t, count_marker, count_insert, 'UI control state')
start = '        // Hardware control section\n'
end = '        // Personal data section\n'
ui_block = '''        // The model sees one device_control composite schema, while the UI keeps the
        // four granular capability toggles behind the same expandable pattern as Device Info.
        CardGroup {
            item(
                onClick = { deviceControlExpanded = !deviceControlExpanded },
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_section_hardware))
                },
                trailingContent = {
                    Text(
                        "$enabledDeviceControlCount/${deviceControlOptions.size}  " +
                            if (deviceControlExpanded) "▲" else "▼"
                    )
                },
            )
            if (deviceControlExpanded) {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_torch_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_torch_desc))
                    },
                    trailingContent = {
                        PermissionedSwitch(
                            checked = assistant.localTools.contains(LocalToolOption.Torch),
                            onCheckedChange = { toggleLocalTool(LocalToolOption.Torch, it) }
                        )
                    }
                )
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_vibrate_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_vibrate_desc))
                    },
                    trailingContent = {
                        PermissionedSwitch(
                            checked = assistant.localTools.contains(LocalToolOption.Vibrate),
                            onCheckedChange = { toggleLocalTool(LocalToolOption.Vibrate, it) }
                        )
                    }
                )
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_brightness_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_brightness_desc))
                    },
                    trailingContent = {
                        PermissionedSwitch(
                            checked = assistant.localTools.contains(LocalToolOption.Brightness),
                            onCheckedChange = { toggleLocalTool(LocalToolOption.Brightness, it) },
                            requiresWriteSettings = true,
                        )
                    }
                )
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_volume_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_volume_desc))
                    },
                    trailingContent = {
                        PermissionedSwitch(
                            checked = assistant.localTools.contains(LocalToolOption.Volume),
                            onCheckedChange = { toggleLocalTool(LocalToolOption.Volume, it) },
                            requiresDndAccess = true,
                        )
                    }
                )
            }
        }

'''
t = replace_between(t, start, end, ui_block, 'UI hardware group')
p.write_text(t)

# Basic sanity before the workflow commits.
for path in [
    'app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/DeviceControlTool.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolNameAliases.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt',
    'app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt',
    'app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/DeviceControlToolTest.kt',
    'app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolNameAliasesTest.kt',
    'app/src/test/java/me/rerere/rikkahub/data/ai/LoopGuardTest.kt',
]:
    if not (root / path).exists():
        raise RuntimeError(f'missing output: {path}')

print('device_control patch applied')
