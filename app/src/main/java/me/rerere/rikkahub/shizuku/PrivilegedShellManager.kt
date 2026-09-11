package me.rerere.rikkahub.shizuku

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val ROOT_PROBE_TIMEOUT_MS = 8_000
private const val ROOT_SUCCESS_CACHE_MS = 60_000L
private const val ROOT_FAILURE_CACHE_MS = 15_000L
private const val MAX_STDOUT_BYTES = 8_000
private const val MAX_STDERR_BYTES = 2_000
private const val ROOT_PROBE_STDOUT_BYTES = 512
private const val ROOT_PROBE_STDERR_BYTES = 1_024

internal data class RootProbeResult(
    val available: Boolean,
    val reason: String? = null,
)

internal fun rootProbeSucceeded(result: JsonObject): Boolean {
    if (result["exit_code"]?.jsonPrimitive?.intOrNull != 0) return false
    val uid = result["stdout"]?.jsonPrimitive?.contentOrNull
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.lastOrNull()
    return uid == "0"
}

/**
 * Falling back after the privileged command itself started can execute a side effect twice.
 * Therefore only a ProcessBuilder launch failure is retryable through Shizuku. A non-zero exit
 * code or timeout is the result of the root attempt and MUST be returned as-is.
 */
internal fun rootExecutionCanFallback(result: JsonObject): Boolean =
    result["error"]?.jsonPrimitive?.contentOrNull == "exec_failed"

internal fun describeRootProbeFailure(result: JsonObject): String {
    val error = result["error"]?.jsonPrimitive?.contentOrNull
    if (error == "command_timeout") return "root_probe_timeout"
    if (error == "exec_failed") return "su_not_available"

    val exitCode = result["exit_code"]?.jsonPrimitive?.intOrNull
    if (exitCode != null && exitCode != 0) return "su_denied_or_failed"

    return if (rootProbeSucceeded(result)) "" else "su_did_not_grant_uid_0"
}

/**
 * Privileged command router for the assistant shell tool.
 *
 * Priority:
 *  1. Real root through `su -c`, but only after a probe proves effective uid 0.
 *  2. Existing Shizuku shell-UID backend when root is unavailable or the `su` process cannot
 *     even be launched.
 *
 * Root availability is cached briefly to avoid repeatedly triggering KernelSU/Magisk consent
 * checks. A failed probe is never cached permanently, so granting root in the manager becomes
 * visible without restarting the app.
 */
object PrivilegedShellManager {
    private data class CachedProbe(
        val result: RootProbeResult,
        val timestampMs: Long,
    )

    private val probeLock = Mutex()

    @Volatile
    private var cachedProbe: CachedProbe? = null

    suspend fun exec(context: Context, command: String, timeoutMs: Int): JsonObject {
        val root = probeRoot()
        if (root.available) {
            val rootResult = runInterruptible(Dispatchers.IO) {
                ShizukuCommandRunner.runProcess(
                    processCommand = listOf("su", "-c", command),
                    timeoutMs = timeoutMs,
                    maxStdoutBytes = MAX_STDOUT_BYTES,
                    maxStderrBytes = MAX_STDERR_BYTES,
                )
            }

            if (!rootExecutionCanFallback(rootResult)) {
                return withBackend(rootResult, backend = "root")
            }

            // `su` disappeared / could not be started after a successful probe. No child
            // command was executed, so a Shizuku retry cannot duplicate side effects.
            invalidateRootProbe()
            val shizukuResult = ShizukuManager.exec(context, command, timeoutMs)
            return withBackend(
                shizukuResult,
                backend = "shizuku",
                rootFallbackReason = "root_exec_failed",
            )
        }

        val shizukuResult = ShizukuManager.exec(context, command, timeoutMs)
        return withBackend(
            shizukuResult,
            backend = "shizuku",
            rootFallbackReason = root.reason,
        )
    }

    internal suspend fun probeRoot(): RootProbeResult = probeLock.withLock {
        val now = monotonicMs()
        cachedProbe?.let { cached ->
            val ttl = if (cached.result.available) ROOT_SUCCESS_CACHE_MS else ROOT_FAILURE_CACHE_MS
            if (now - cached.timestampMs < ttl) return@withLock cached.result
        }

        val raw = runInterruptible(Dispatchers.IO) {
            ShizukuCommandRunner.runProcess(
                processCommand = listOf("su", "-c", "id -u"),
                timeoutMs = ROOT_PROBE_TIMEOUT_MS,
                maxStdoutBytes = ROOT_PROBE_STDOUT_BYTES,
                maxStderrBytes = ROOT_PROBE_STDERR_BYTES,
            )
        }
        val result = if (rootProbeSucceeded(raw)) {
            RootProbeResult(available = true)
        } else {
            RootProbeResult(
                available = false,
                reason = describeRootProbeFailure(raw),
            )
        }
        cachedProbe = CachedProbe(result, now)
        result
    }

    internal suspend fun invalidateRootProbe() {
        probeLock.withLock { cachedProbe = null }
    }

    private fun withBackend(
        result: JsonObject,
        backend: String,
        rootFallbackReason: String? = null,
    ): JsonObject = buildJsonObject {
        result.forEach { (key, value) -> put(key, value) }
        put("backend", backend)
        if (!rootFallbackReason.isNullOrBlank()) {
            put("root_fallback_reason", rootFallbackReason)
        }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
}
