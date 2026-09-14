package me.rerere.locallm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class GgufModelInstallTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun ggufBytes(payloadSize: Int = 32): ByteArray =
        byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(payloadSize) { (it % 251).toByte() }

    @Test
    fun `gguf extension routes to llama cpp case insensitively`() {
        assertEquals(LocalRuntime.LlamaCpp, ModelInstall.runtimeForExtension("gguf"))
        assertEquals(LocalRuntime.LlamaCpp, ModelInstall.runtimeForExtension("GGUF"))
        assertEquals(LocalRuntime.LiteRT, ModelInstall.runtimeForExtension("litertlm"))
    }

    @Test
    fun `llama cpp models use their own storage directory`() {
        val base = tempFolder.newFolder("local-models")

        val target = ModelInstall.targetFile(base, LocalRuntime.LlamaCpp, "model.gguf")

        assertEquals("model.gguf", target.name)
        assertEquals("llamacpp", target.parentFile?.name)
    }

    @Test
    fun `gguf magic accepts a model and rejects corrupt substitutes`() {
        assertTrue(ModelInstall.isValidMagicForExtension("gguf", ggufBytes()))
        assertTrue(ModelInstall.isValidMagicForExtension("GGUF", ggufBytes()))
        assertFalse(ModelInstall.isValidMagicForExtension("gguf", ByteArray(16)))
        assertFalse(
            ModelInstall.isValidMagicForExtension(
                "gguf",
                "<!DOCTYPE html><html>".toByteArray(),
            ),
        )
        assertFalse(ModelInstall.isValidMagicForExtension("gguf", byteArrayOf(0x47, 0x47, 0x55)))
    }

    @Test
    fun `SAF import validates and installs GGUF byte exactly`() = runBlocking {
        val content = ggufBytes(5_000)
        val target = tempFolder.newFile("picked.gguf")
        target.delete()

        val events = ModelInstall.copyFromStream(
            input = ByteArrayInputStream(content),
            target = target,
            totalBytes = content.size.toLong(),
            expectedExtension = "gguf",
        ).toList()

        assertTrue(events.first() is ModelInstall.Progress.Started)
        assertTrue(events.last() is ModelInstall.Progress.Done)
        assertArrayEquals(content, target.readBytes())
        assertFalse(java.io.File("${target.absolutePath}.partial").exists())
    }
}
