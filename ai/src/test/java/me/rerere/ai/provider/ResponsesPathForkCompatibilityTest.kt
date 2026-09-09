package me.rerere.ai.provider

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsesPathForkCompatibilityTest {
    @Test
    fun `custom responses path and fork routing survive settings roundtrip`() {
        val setting = ProviderSetting.OpenAI(
            responsesPath = "/custom/responses",
            routing = OpenRouterRouting(
                order = listOf("provider-a"),
                allowFallbacks = false,
                fallbackModels = listOf("fallback-model"),
            ),
        )
        val restored = Json.decodeFromString<ProviderSetting.OpenAI>(Json.encodeToString(setting))
        assertEquals("/custom/responses", restored.responsesPath)
        assertEquals(setting.routing, restored.routing)
    }

    @Test
    fun `older settings without responses path retain standard endpoint and routing`() {
        val restored = Json.decodeFromString<ProviderSetting.OpenAI>(
            """{"routing":{"order":["provider-a"],"allowFallbacks":false}}"""
        )
        assertEquals("/responses", restored.responsesPath)
        assertEquals(listOf("provider-a"), restored.routing.order)
        assertEquals(false, restored.routing.allowFallbacks)
    }
}
