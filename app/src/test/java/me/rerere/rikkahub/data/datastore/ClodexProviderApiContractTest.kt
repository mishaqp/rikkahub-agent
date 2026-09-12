package me.rerere.rikkahub.data.datastore

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClodexProviderApiContractTest {
    private class ModelsTransport : Interceptor {
        var request: Request? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            request = chain.request()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"object":"list","data":[{"id":"openai/gpt-test"},{"id":"anthropic/claude-test"}]}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }
    }

    @Test
    fun `clodex model discovery uses the authorized openai compatible models endpoint`() = runBlocking {
        val transport = ModelsTransport()
        val client = OkHttpClient.Builder().addInterceptor(transport).build()
        val preset = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == DEFAULT_CLODEX_PROVIDER_ID }
            .copy(apiKey = "clodex-test-key")

        val models = OpenAIProvider(client).listModels(preset)

        assertEquals(
            listOf("anthropic/claude-test", "openai/gpt-test").sorted(),
            models.map { it.modelId }.sorted(),
        )
        val request = transport.request
        assertEquals("https://clodex.xyz/v1/models", request?.url.toString())
        assertEquals("Bearer clodex-test-key", request?.header("Authorization"))
        assertEquals("GET", request?.method)
        assertNull(request?.body)
        assertTrue(preset.useResponseApi)
    }
}
