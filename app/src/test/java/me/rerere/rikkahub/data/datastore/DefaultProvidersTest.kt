package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers include one first class clodex preset`() {
        val clodexProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.id == DEFAULT_CLODEX_PROVIDER_ID }

        assertEquals(1, clodexProviders.size)

        val provider = clodexProviders.single()
        assertEquals("CLODEX", provider.name)
        assertEquals("https://clodex.xyz/v1", provider.baseUrl)
        assertEquals("/responses", provider.responsesPath)
        assertEquals("/chat/completions", provider.chatCompletionsPath)
        assertTrue(provider.useResponseApi)
        assertTrue(provider.builtIn)
        assertFalse(provider.enabled)
        assertFalse(provider.balanceOption.enabled)
        assertTrue(provider.apiKey.isEmpty())
        assertTrue(provider.models.isEmpty())
        assertNotNull(provider.shortDescription)
    }

    @Test
    fun `every built in provider id is unique`() {
        assertEquals(DEFAULT_PROVIDERS.size, DEFAULT_PROVIDERS.map { it.id }.distinct().size)
    }

    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }
}
