package me.rerere.rikkahub.ui.pages.setting.components

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionRetryTest {
    @Test
    fun `recognizes typed raw and provider-worded transient failures`() {
        assertTrue(
            isRetryableProviderConnectionFailure(
                HttpException("service unavailable", statusCode = 503)
            )
        )
        assertTrue(
            isRetryableProviderConnectionFailure(
                Exception("Failed to get response: 503 {\"error\":\"upstream\"}")
            )
        )
        assertTrue(
            isRetryableProviderConnectionFailure(
                HttpException("The model is temporarily unavailable. Please try again later.")
            )
        )
        assertTrue(isRetryableProviderConnectionFailure(IOException("connection reset")))
    }

    @Test
    fun `does not retry deterministic client balance quota or tool failures`() {
        for (statusCode in listOf(400, 401, 403, 404, 422)) {
            assertFalse(
                "status $statusCode must not retry",
                isRetryableProviderConnectionFailure(
                    HttpException("client error", statusCode = statusCode)
                )
            )
        }
        assertFalse(
            isRetryableProviderConnectionFailure(
                HttpException("RESOURCE_EXHAUSTED: quota exceeded", statusCode = 429)
            )
        )
        assertFalse(
            isRetryableProviderConnectionFailure(
                IllegalArgumentException("model does not support tool calls")
            )
        )
    }

    @Test
    fun `uses bounded backoff and succeeds on the third attempt`() = runBlocking {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        val retries = mutableListOf<Int>()

        val result = retryProviderConnectionTest(
            backoffMs = listOf(10L, 20L),
            sleeper = { delayMs -> sleeps += delayMs },
            onRetry = { retryNumber, _ -> retries += retryNumber },
        ) {
            attempts++
            if (attempts < 3) {
                throw HttpException("temporary", statusCode = 503)
            }
            "connected"
        }

        assertEquals("connected", result)
        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), sleeps)
        assertEquals(listOf(1, 2), retries)
    }

    @Test
    fun `stops after configured retries and preserves the final failure`() {
        val finalFailure = HttpException("still unavailable", statusCode = 503)
        var attempts = 0

        val thrown = assertThrows(HttpException::class.java) {
            runBlocking {
                retryProviderConnectionTest(
                    backoffMs = listOf(1L),
                    sleeper = {},
                ) {
                    attempts++
                    if (attempts == 1) {
                        throw HttpException("first unavailable", statusCode = 503)
                    }
                    throw finalFailure
                }
            }
        }

        assertSame(finalFailure, thrown)
        assertEquals(2, attempts)
    }

    @Test
    fun `cancellation escapes without retry or sleep`() {
        val cancellation = CancellationException("dialog closed")
        var attempts = 0
        var sleeps = 0

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                retryProviderConnectionTest(
                    sleeper = { sleeps++ },
                ) {
                    attempts++
                    throw cancellation
                }
            }
        }

        assertSame(cancellation, thrown)
        assertEquals(1, attempts)
        assertEquals(0, sleeps)
    }
}
