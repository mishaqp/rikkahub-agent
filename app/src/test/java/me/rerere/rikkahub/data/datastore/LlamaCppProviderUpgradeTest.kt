package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LlamaCppProviderUpgradeTest {
    private val llamaDefault: ProviderSetting.LlamaCppLocal
        get() = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.LlamaCppLocal>().single()

    private fun remote(name: String) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = name,
        baseUrl = "https://${name.lowercase()}.invalid/v1",
        builtIn = false,
    )

    @Test
    fun `upgrade seeds llama cpp once after the other on-device runtimes`() {
        val aicore = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.AICore>().single()
        val liteRt = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.LiteRtLocal>().single()
        val cloud = remote("Cloud")

        val upgraded = reconcileBuiltInProviders(
            persistedProviders = listOf(cloud, aicore, liteRt),
            deletedDefaultIds = emptySet(),
            defaults = listOf(aicore, liteRt, llamaDefault),
        )
        val upgradedAgain = reconcileBuiltInProviders(
            persistedProviders = upgraded,
            deletedDefaultIds = emptySet(),
            defaults = listOf(aicore, liteRt, llamaDefault),
        )

        assertEquals(
            listOf(aicore.id, liteRt.id, llamaDefault.id, cloud.id),
            upgraded.map { it.id },
        )
        assertEquals(upgraded.map { it.id }, upgradedAgain.map { it.id })
        assertEquals(1, upgradedAgain.count { it.id == llamaDefault.id })
    }

    @Test
    fun `llama cpp configuration and drag order survive reconciliation`() {
        val first = remote("First")
        val last = remote("Last")
        val model = Model(modelId = "local-model.gguf", displayName = "Local model")
        val configured = llamaDefault.copy(
            enabled = true,
            name = "My llama.cpp",
            models = listOf(model),
        )

        val reconciled = reconcileBuiltInProviders(
            persistedProviders = listOf(first, configured, last),
            deletedDefaultIds = emptySet(),
            defaults = listOf(llamaDefault),
        )

        assertEquals(listOf(first.id, configured.id, last.id), reconciled.map { it.id })
        val restored = reconciled[1] as ProviderSetting.LlamaCppLocal
        assertTrue(restored.enabled)
        assertTrue(restored.builtIn)
        assertEquals("My llama.cpp", restored.name)
        assertEquals(listOf(model), restored.models)
    }

    @Test
    fun `explicitly deleted llama cpp built-in is not seeded again`() {
        val existing = remote("Existing")

        val reconciled = reconcileBuiltInProviders(
            persistedProviders = listOf(existing),
            deletedDefaultIds = setOf(llamaDefault.id),
            defaults = listOf(llamaDefault),
        )

        assertEquals(listOf(existing.id), reconciled.map { it.id })
        assertFalse(reconciled.any { it.id == llamaDefault.id })
    }
}
