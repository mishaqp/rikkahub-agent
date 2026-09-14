package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import java.util.Base64

internal data class CodexIdentity(
    val accountId: String,
    val userId: String,
    val email: String,
    val name: String,
)

internal fun parseCodexIdentity(idToken: String, json: Json): CodexIdentity {
    val parts = idToken.split('.')
    require(parts.size == 3) { "Invalid ID token" }
    val payload = Base64.getUrlDecoder().decode(parts[1])
    val claims = json.parseToJsonElement(payload.decodeToString()).jsonObject
    val auth = claims["https://api.openai.com/auth"]?.jsonObject ?: JsonObject(emptyMap())
    val accountId = auth["chatgpt_account_id"]?.jsonPrimitive?.contentOrNull
        ?: error("Missing ChatGPT account ID")
    return CodexIdentity(
        accountId = accountId,
        userId = auth["chatgpt_user_id"]?.jsonPrimitive?.contentOrNull
            ?: claims["sub"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        email = claims["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        name = claims["name"]?.jsonPrimitive?.contentOrNull
            ?: claims["email"]?.jsonPrimitive?.contentOrNull
            ?: "OpenAI",
    )
}

internal fun parseCodexUsage(jsonObject: JsonObject): CodexUsageSnapshot {
    val rateLimit = jsonObject["rate_limit"] as? JsonObject
    val additional = (jsonObject["additional_rate_limits"] as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item["metered_feature"]?.jsonPrimitive?.contentOrNull
                ?.normalizeCodexLimitId()
                ?: return@mapNotNull null
            val nested = item["rate_limit"] as? JsonObject ?: return@mapNotNull null
            val limit = CodexUsageLimit(
                name = item["limit_name"]?.jsonPrimitive?.contentOrNull,
                primary = (nested["primary_window"] as? JsonObject)?.toUsageWindow(),
                secondary = (nested["secondary_window"] as? JsonObject)?.toUsageWindow(),
            )
            id to limit
        }
        .toMap()
    return CodexUsageSnapshot(
        primary = (rateLimit?.get("primary_window") as? JsonObject)?.toUsageWindow(),
        secondary = (rateLimit?.get("secondary_window") as? JsonObject)?.toUsageWindow(),
        additional = additional,
    )
}

internal fun parseCodexUsage(headers: Headers): CodexUsageSnapshot? {
    fun window(prefix: String): CodexUsageWindow? {
        val used = headers["$prefix-used-percent"]?.toDoubleOrNull() ?: return null
        val windowMinutes = headers["$prefix-window-minutes"]?.toLongOrNull()
        if (windowMinutes != null && windowMinutes <= 0) return null
        val resetsAt = headers["$prefix-reset-at"]?.toLongOrNull()
            ?: headers["$prefix-reset-after-seconds"]?.toLongOrNull()
                ?.let { System.currentTimeMillis() / 1000 + it }
        return CodexUsageWindow(
            usedPercent = used,
            windowMinutes = windowMinutes,
            resetsAt = resetsAt,
        )
    }
    val primary = window("x-codex-primary")
    val secondary = window("x-codex-secondary")
    val additionalIds = headers.names().mapNotNull { rawName ->
        val name = rawName.lowercase()
        val limit = name
            .removePrefix("x-")
            .removeSuffix("-primary-used-percent")
        if (
            name.startsWith("x-") &&
            name.endsWith("-primary-used-percent") &&
            limit != "codex"
        ) {
            limit.normalizeCodexLimitId()
        } else {
            null
        }
    }.toSet()
    val additional = additionalIds.mapNotNull { id ->
        val prefix = "x-${id.replace('_', '-')}"
        val limit = CodexUsageLimit(
            name = headers["$prefix-limit-name"],
            primary = window("$prefix-primary"),
            secondary = window("$prefix-secondary"),
        )
        if (limit.primary == null && limit.secondary == null) null else id to limit
    }.toMap()
    if (primary == null && secondary == null && additional.isEmpty()) return null
    return CodexUsageSnapshot(
        primary = primary,
        secondary = secondary,
        additional = additional,
    )
}

private fun String.normalizeCodexLimitId(): String =
    trim().lowercase().replace('-', '_')

private fun JsonObject.toUsageWindow(): CodexUsageWindow? {
    val used = this["used_percent"]?.jsonPrimitive?.doubleOrNull ?: return null
    val windowSeconds = this["limit_window_seconds"]?.jsonPrimitive?.longOrNull
    if (windowSeconds != null && windowSeconds <= 0) return null
    return CodexUsageWindow(
        usedPercent = used,
        windowMinutes = windowSeconds?.div(60),
        resetsAt = this["reset_at"]?.jsonPrimitive?.longOrNull,
    )
}
