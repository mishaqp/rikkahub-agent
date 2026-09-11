package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedShellManagerTest {

    @Test
    fun `root probe accepts only successful uid zero`() {
        val result = buildJsonObject {
            put("success", true)
            put("exit_code", 0)
            put("stdout", "0\n")
            put("stderr", "")
        }

        assertTrue(rootProbeSucceeded(result))
    }

    @Test
    fun `root probe tolerates banner but requires final uid zero`() {
        val result = buildJsonObject {
            put("success", true)
            put("exit_code", 0)
            put("stdout", "KernelSU\n0\n")
            put("stderr", "")
        }

        assertTrue(rootProbeSucceeded(result))
    }

    @Test
    fun `root probe rejects shell uid`() {
        val result = buildJsonObject {
            put("success", true)
            put("exit_code", 0)
            put("stdout", "2000\n")
            put("stderr", "")
        }

        assertFalse(rootProbeSucceeded(result))
        assertEquals("su_did_not_grant_uid_0", describeRootProbeFailure(result))
    }

    @Test
    fun `root probe rejects nonzero exit even if stdout says zero`() {
        val result = buildJsonObject {
            put("success", false)
            put("exit_code", 1)
            put("stdout", "0\n")
            put("stderr", "permission denied")
        }

        assertFalse(rootProbeSucceeded(result))
        assertEquals("su_denied_or_failed", describeRootProbeFailure(result))
    }

    @Test
    fun `only process launch failure may fall back to shizuku`() {
        val result = buildJsonObject {
            put("error", "exec_failed")
            put("reason", "Cannot run program su")
        }

        assertTrue(rootExecutionCanFallback(result))
    }

    @Test
    fun `nonzero root command must not be executed again via shizuku`() {
        val result = buildJsonObject {
            put("success", false)
            put("exit_code", 1)
            put("stdout", "")
            put("stderr", "command failed")
        }

        assertFalse(rootExecutionCanFallback(result))
    }

    @Test
    fun `timed out root command must not be executed again via shizuku`() {
        val result = buildJsonObject {
            put("error", "command_timeout")
            put("partial_stdout", "started")
            put("partial_stderr", "")
        }

        assertFalse(rootExecutionCanFallback(result))
    }
}
