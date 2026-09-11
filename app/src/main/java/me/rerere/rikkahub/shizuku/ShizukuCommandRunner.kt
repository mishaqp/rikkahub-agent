package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.local.BoundedOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pure command-execution core shared by the Shizuku and root backends. It captures bounded
 * stdout/stderr and enforces [timeoutMs] by destroying the process.
 *
 * Deliberately free of Android and Shizuku dependencies: [ProcessBuilder] runs the same way
 * whether this code executes inside the app process, inside the separate shell-UID process
 * Shizuku spawns for [ShizukuUserService], or in a host JVM unit test. Reuses
 * [BoundedOutputStream] so stdout/stderr truncation behaves identically to the other shell
 * tools (SSH, Termux).
 */
internal object ShizukuCommandRunner {

    /** Run a normal shell command inside the current process' UID. */
    fun run(command: String, timeoutMs: Int, maxStdoutBytes: Int, maxStderrBytes: Int): JsonObject =
        runProcess(
            processCommand = listOf("sh", "-c", command),
            timeoutMs = timeoutMs,
            maxStdoutBytes = maxStdoutBytes,
            maxStderrBytes = maxStderrBytes,
        )

    /**
     * Run an explicit argv without routing it through another shell first. Root uses this as
     * `su -c <command>`, which avoids hand-built shell quoting around an LLM supplied command.
     */
    fun runProcess(
        processCommand: List<String>,
        timeoutMs: Int,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
    ): JsonObject {
        require(processCommand.isNotEmpty()) { "processCommand must not be empty" }

        val process = try {
            ProcessBuilder(processCommand).start()
        } catch (e: IOException) {
            return buildJsonObject {
                put("error", "exec_failed")
                put("reason", e.message ?: e::class.java.simpleName)
            }
        }

        val stdoutSink = BoundedOutputStream(maxStdoutBytes)
        val stderrSink = BoundedOutputStream(maxStderrBytes)
        // Drain both streams concurrently. A chatty process can otherwise fill a pipe buffer
        // and deadlock before waitFor() ever reaches its timeout.
        val stdoutThread = Thread({ runCatching { process.inputStream.copyTo(stdoutSink) } }, "shell-stdout")
            .apply { isDaemon = true; start() }
        val stderrThread = Thread({ runCatching { process.errorStream.copyTo(stderrSink) } }, "shell-stderr")
            .apply { isDaemon = true; start() }

        val finished = try {
            process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // runInterruptible() uses thread interruption for coroutine cancellation. Never
            // leave a privileged child process running when its caller is cancelled.
            process.destroyForcibly()
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            Thread.currentThread().interrupt()
            throw e
        }
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            return buildJsonObject {
                put("error", "command_timeout")
                put(
                    "recovery",
                    "Command did not complete within ${timeoutMs / 1000}s. Bump timeout_ms if the " +
                        "command genuinely needs longer. Partial output captured before the " +
                        "timeout is included."
                )
                put("partial_stdout", stdoutSink.snapshot())
                put("partial_stderr", stderrSink.snapshot())
            }
        }

        stdoutThread.join(2_000)
        stderrThread.join(2_000)
        val exitCode = process.exitValue()
        return buildJsonObject {
            put("success", exitCode == 0)
            put("exit_code", exitCode)
            put("stdout", stdoutSink.snapshot())
            put("stderr", stderrSink.snapshot())
        }
    }
}
