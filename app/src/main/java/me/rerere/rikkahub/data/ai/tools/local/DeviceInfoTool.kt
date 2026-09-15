package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
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

internal val DEVICE_INFO_SECTION_ORDER = listOf(
    "battery",
    "audio",
    "telephony",
    "wifi",
    "storage",
    "sensors",
)

/**
 * Composite read-only device information tool.
 *
 * The assistant still owns the same per-capability toggles as before. [enabledSections]
 * is derived from those toggles, and both the JSON schema and runtime execution are
 * restricted to that subset. This matters for persisted legacy calls: a legacy battery
 * call must not become executable merely because Wi-Fi is enabled and both now share one
 * canonical tool name.
 */
fun deviceInfoTool(
    context: Context,
    enabledSections: Set<String>,
): Tool = deviceInfoTool(
    enabledSections = enabledSections,
    delegates = mapOf(
        "battery" to batteryTool(context),
        "audio" to audioInfoTool(context),
        "telephony" to telephonyInfoTool(context),
        "wifi" to wifiInfoTool(context),
        "storage" to storageTool(context),
        "sensors" to listSensorsTool(context),
    ),
)

/** JVM-test seam: production uses the overload above with the real Android-backed tools. */
internal fun deviceInfoTool(
    enabledSections: Set<String>,
    delegates: Map<String, Tool>,
): Tool {
    val allowedSections = DEVICE_INFO_SECTION_ORDER.filter { it in enabledSections }
    require(allowedSections.isNotEmpty()) { "device_info requires at least one enabled section" }

    return Tool(
        name = "device_info",
        description = buildString {
            append("Read current device information from one enabled section. Available sections: ")
            append(allowedSections.joinToString(", "))
            append(". This tool is read-only. Use one section per call.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("section", buildJsonObject {
                        put("type", "string")
                        put("description", "Device information section to read")
                        put("enum", buildJsonArray {
                            allowedSections.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                },
                required = listOf("section"),
            )
        },
        execute = { input ->
            val section = input.jsonObject["section"]?.jsonPrimitive?.contentOrNull
            when {
                section == null -> listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "missing_section")
                            put("available_sections", buildJsonArray {
                                allowedSections.forEach { add(JsonPrimitive(it)) }
                            })
                        }.toString()
                    )
                )

                section !in allowedSections -> listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "section_disabled")
                            put("section", section)
                            put("available_sections", buildJsonArray {
                                allowedSections.forEach { add(JsonPrimitive(it)) }
                            })
                        }.toString()
                    )
                )

                else -> {
                    val delegate = delegates[section]
                    if (delegate == null) {
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("error", "section_unavailable")
                                    put("section", section)
                                }.toString()
                            )
                        )
                    } else {
                        // Every merged legacy info tool has an empty input schema. Delegate rather
                        // than reimplementing Android queries so output stays byte-for-byte compatible.
                        delegate.execute(JsonObject(emptyMap()))
                    }
                }
            }
        },
    )
}
