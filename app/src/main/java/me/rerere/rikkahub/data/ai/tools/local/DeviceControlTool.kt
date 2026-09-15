package me.rerere.rikkahub.data.ai.tools.local

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
