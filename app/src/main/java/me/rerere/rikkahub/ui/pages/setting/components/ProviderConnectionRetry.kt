package me.rerere.rikkahub.ui.pages.setting.components

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.rerere.ai.util.HttpException

private val RETRYABLE_PROVIDER_TEST_STATUS_CODES = setOf(429, 502, 503, 504)
private val PROVIDER_TEST_STATUS_PATTERN = Regex(
    pattern = "(?:HTTP(?:\\s+status)?|response:)\\s*(429|502|503|504)\\b",
    option = RegexOption.IGNORE_CASE,
)
private val NON_RETRYABLE_QUOTA_MARKERS = listOf(
    "insufficient balance",
    "insufficient credits",
    "quota exceeded",
    "resource exhausted",
    "resource has been exhausted",
)
private val TRANSIENT_PROVIDER_MARKERS = listOf(
    "temporarily unavailable",
    "service unavailable",
    "upstream unavailable",
    "server overloaded",
    "capacity unavailable",
    "try again later",
)

internal val PROVIDER_CONNECTION_TEST_BACKOFF_MS = listOf(750L, 1_500L)

/**
 * Connection checks are explicit diagnostics, so retry only failures that are demonstrably
 * transient. In particular, never replay authentication, validation, unsupported-tool, balance,
 * or quota failures: doing so would add latency and could consume paid requests without changing
 * the outcome.
 */
internal fun isRetryableProviderConnectionFailure(failure: Throwable): Boolean {
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    if (causes.any { it is CancellationException }) return false

    val combinedMessage = causes
        .joinToString(" ") { cause -> cause.message.orEmpty() }
        .lowercase()
        .replace('_', ' ')
    if (NON_RETRYABLE_QUOTA_MARKERS.any { marker -> marker in combinedMessage }) return false

    val statusCode = causes
        .filterIsInstance<HttpException>()
        .firstNotNullOfOrNull { exception -> exception.statusCode }
        ?: PROVIDER_TEST_STATUS_PATTERN.find(combinedMessage)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    if (statusCode != null) return statusCode in RETRYABLE_PROVIDER_TEST_STATUS_CODES

    if (TRANSIENT_PROVIDER_MARKERS.any { marker -> marker in combinedMessage }) return true
    return causes.any { cause -> cause is IOException }
}

/**
 * At most two delayed retries (three total attempts). Cancellation always escapes immediately.
 * Keeping the sleeper injectable makes the retry contract testable without real-time waits.
 */
internal suspend fun <T> retryProviderConnectionTest(
    backoffMs: List<Long> = PROVIDER_CONNECTION_TEST_BACKOFF_MS,
    shouldRetry: (Throwable) -> Boolean = ::isRetryableProviderConnectionFailure,
    sleeper: suspend (Long) -> Unit = { delayMs -> delay(delayMs) },
    onRetry: (retryNumber: Int, failure: Throwable) -> Unit = { _, _ -> },
    request: suspend () -> T,
): T {
    var retryNumber = 0
    while (true) {
        try {
            return request()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val delayMs = backoffMs.getOrNull(retryNumber)
            if (delayMs == null || !shouldRetry(failure)) throw failure
            retryNumber++
            onRetry(retryNumber, failure)
            sleeper(delayMs)
        }
    }
}
