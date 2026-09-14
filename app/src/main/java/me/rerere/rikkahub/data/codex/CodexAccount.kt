package me.rerere.rikkahub.data.codex

import kotlinx.serialization.Serializable

@Serializable
data class CodexAccount(
    val id: String,
    val userId: String = "",
    val name: String,
    val email: String = "",
    val chatgptAccountId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: CodexTokenStatus = CodexTokenStatus.UNKNOWN,
    val usage: CodexUsageSnapshot? = null,
)

@Serializable
enum class CodexTokenStatus {
    UNKNOWN,
    AVAILABLE,
    EXPIRED,
    INVALID,
}

@Serializable
data class CodexUsageSnapshot(
    val primary: CodexUsageWindow? = null,
    val secondary: CodexUsageWindow? = null,
    val additional: Map<String, CodexUsageLimit> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class CodexUsageLimit(
    val name: String? = null,
    val primary: CodexUsageWindow? = null,
    val secondary: CodexUsageWindow? = null,
)

@Serializable
data class CodexUsageWindow(
    val usedPercent: Double,
    val windowMinutes: Long? = null,
    val resetsAt: Long? = null,
)

internal const val CODEX_SPARK_MODEL_ID = "gpt-5.3-codex-spark"
internal const val CODEX_SPARK_LIMIT_ID = "codex_bengalfox"

internal fun isCodexSparkModel(modelId: String?): Boolean =
    modelId.equals(CODEX_SPARK_MODEL_ID, ignoreCase = true)

internal fun CodexAccount.isAvailable(
    nowMillis: Long = System.currentTimeMillis(),
    modelId: String? = null,
): Boolean {
    if (!enabled || tokenStatus == CodexTokenStatus.INVALID) return false
    val windows = if (isCodexSparkModel(modelId)) {
        // Spark is metered separately. If the service has not reported that bucket yet, let the
        // authoritative backend decide instead of incorrectly applying the regular Codex quota.
        val sparkLimit = usage?.additional?.get(CODEX_SPARK_LIMIT_ID)
            ?: usage?.additional?.values?.firstOrNull {
                it.name.equals(CODEX_SPARK_MODEL_ID, ignoreCase = true)
            }
        if (sparkLimit == null) return true
        listOfNotNull(sparkLimit.primary, sparkLimit.secondary)
    } else {
        listOfNotNull(usage?.primary, usage?.secondary)
    }
    val exhausted = windows
        .any { it.usedPercent >= 100.0 && (it.resetsAt == null || it.resetsAt * 1000 > nowMillis) }
    return !exhausted
}
