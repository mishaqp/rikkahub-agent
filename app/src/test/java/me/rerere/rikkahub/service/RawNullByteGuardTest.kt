package me.rerere.rikkahub.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the raw-NUL defect fixed in the 2.5.1 post-release pass.
 *
 * Five tracked text sources shipped with a *physical* 0x00 byte inside a string
 * literal instead of the textual escape `\u0000`. The bytes were behaviourally
 * equivalent (Kotlin compiles both to U+0000), but they made the files
 * unreadable to any byte-oriented tooling: `grep` classifies a file containing a
 * NUL as binary and silently prints "Binary file ... matches" instead of the line,
 * which is why the repository Guards — all built on grep — could skip these files
 * entirely and never notice a pattern they were supposed to catch.
 *
 * The fix replaced the physical byte with the textual escape, keeping the compiled
 * value identical. This test pins both halves of the contract: no raw 0x00 remains,
 * and the escape that replaced it is present. It is JVM-only, needs no device and
 * no network, and reads the files relative to the `:app` module directory (same
 * pattern as MediaPlaybackManifestTest).
 *
 * `.github/scripts/check-no-nul.py` covers the whole repository in CI; this test
 * covers the specific files that regressed, so a revert of the fix fails fast in
 * the normal unit-test run rather than only in the Guards job.
 */
class RawNullByteGuardTest {

    private data class Case(val path: String, val expectedEscapes: Int, val expectEscapes: Boolean)

    private val previouslyDefective = listOf(
        Case("src/main/java/me/rerere/rikkahub/automation/ExternalAutomationDispatcher.kt", 1, true),
        Case("src/main/java/me/rerere/rikkahub/data/ai/tools/local/FileManagerTool.kt", 1, true),
        Case("src/main/java/me/rerere/rikkahub/data/keyboard/KeyboardApiClient.kt", 1, true),
        Case("src/main/java/me/rerere/rikkahub/data/provider/WorkspaceDocumentsProvider.kt", 1, true),
        Case("src/test/java/me/rerere/rikkahub/service/SanitizeAttachmentFilenameTest.kt", 4, true),
    )

    private val rawNull = 0x00.toByte()
    private val textualEscape = "\\u0000".toByteArray(Charsets.US_ASCII)

    private fun read(path: String): ByteArray {
        val file = File(path)
        assertTrue("expected $path to exist relative to the :app module", file.isFile)
        return file.readBytes()
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return 0
        var count = 0
        var index = 0
        while (index <= haystack.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                count++
                index += needle.size
            } else {
                index++
            }
        }
        return count
    }

    @Test
    fun `previously defective sources carry no raw NUL byte`() {
        for (case in previouslyDefective) {
            val bytes = read(case.path)
            assertFalse(
                "${case.path} must not contain a raw 0x00 byte (use the textual \\u0000 escape)",
                bytes.any { it == rawNull },
            )
        }
    }

    @Test
    fun `the textual escape that replaced each raw NUL is present`() {
        for (case in previouslyDefective) {
            if (!case.expectEscapes) continue
            val count = countOccurrences(read(case.path), textualEscape)
            assertTrue(
                "${case.path} should contain ${case.expectedEscapes} textual \\u0000 escape(s), found $count",
                count >= case.expectedEscapes,
            )
        }
    }

    @Test
    fun `sentinel values survive the rewrite byte for byte`() {
        // The three sentinels below are the exact literals the fix touched. Pinning
        // them documents that the compiled string is unchanged: U+0000 followed by
        // the previous character payload of each site.
        val checks = listOf(
            "src/main/java/me/rerere/rikkahub/data/keyboard/KeyboardApiClient.kt" to "SENTINEL_NULL = \"\\u0000__keyboard_api_null__\"",
            "src/main/java/me/rerere/rikkahub/automation/ExternalAutomationDispatcher.kt" to "callerPackage + \"\\u0000\" + requestId",
        )
        for ((path, snippet) in checks) {
            val text = read(path).toString(Charsets.UTF_8)
            assertTrue("$path should still declare the exact sentinel value", text.contains(snippet))
        }
    }
}
