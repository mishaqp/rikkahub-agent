package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ClodexProviderUpgradeTest {
    private val clodexDefault: ProviderSetting.OpenAI
        get() = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == DEFAULT_CLODEX_PROVIDER_ID }

    private fun customProvider(name: String) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = name,
        baseUrl = "https://${name.lowercase()}.invalid/v1",
        builtIn = false,
    )

    @Test
    fun `upgrade appends clodex exactly once without reordering existing remote providers`() {
        val first = customProvider("First")
        val second = customProvider("Second")

        val upgraded = reconcileBuiltInProviders(
            persistedProviders = listOf(first, second),
            deletedDefaultIds = emptySet(),
            defaults = listOf(clodexDefault),
        )
        val upgradedAgain = reconcileBuiltInProviders(
            persistedProviders = upgraded,
            deletedDefaultIds = emptySet(),
            defaults = listOf(clodexDefault),
        )

        assertEquals(listOf(first.id, second.id, DEFAULT_CLODEX_PROVIDER_ID), upgraded.map { it.id })
        assertEquals(upgraded.map { it.id }, upgradedAgain.map { it.id })
        assertEquals(1, upgradedAgain.count { it.id == DEFAULT_CLODEX_PROVIDER_ID })
    }

    @Test
    fun `clodex configuration and drag order survive settings reconciliation`() {
        val first = customProvider("First")
        val last = customProvider("Last")
        val selectedModel = Model(modelId = "openai/gpt-test", displayName = "GPT test")
        val configured = clodexDefault.copy(
            enabled = true,
            name = "My CLODEX",
            models = listOf(selectedModel),
            apiKey = "user-key-is-preserved",
            baseUrl = "https://relay.example/v1",
            responsesPath = "/custom-responses",
        )

        val reconciled = reconcileBuiltInProviders(
            // This is the order produced after dragging CLODEX between two other rows.
            persistedProviders = listOf(first, configured, last),
            deletedDefaultIds = emptySet(),
            defaults = listOf(clodexDefault),
        )

        assertEquals(listOf(first.id, configured.id, last.id), reconciled.map { it.id })
        val restored = reconciled[1] as ProviderSetting.OpenAI
        assertTrue(restored.enabled)
        assertTrue(restored.builtIn)
        assertEquals("My CLODEX", restored.name)
        assertEquals("user-key-is-preserved", restored.apiKey)
        assertEquals("https://relay.example/v1", restored.baseUrl)
        assertEquals("/custom-responses", restored.responsesPath)
        assertEquals(listOf(selectedModel), restored.models)
    }

    @Test
    fun `a user provider also named clodex is not mistaken for the built in row`() {
        val userCreated = customProvider("CLODEX")

        val reconciled = reconcileBuiltInProviders(
            persistedProviders = listOf(userCreated),
            deletedDefaultIds = emptySet(),
            defaults = listOf(clodexDefault),
        )

        assertEquals(2, reconciled.count { it.name == "CLODEX" })
        assertEquals(1, reconciled.count { it.id == DEFAULT_CLODEX_PROVIDER_ID })
        assertFalse(reconciled.first { it.id == userCreated.id }.builtIn)
    }

    @Test
    fun `an explicitly deleted clodex built in is not seeded again`() {
        val existing = customProvider("Existing")

        val reconciled = reconcileBuiltInProviders(
            persistedProviders = listOf(existing),
            deletedDefaultIds = setOf(DEFAULT_CLODEX_PROVIDER_ID),
            defaults = listOf(clodexDefault),
        )

        assertEquals(listOf(existing.id), reconciled.map { it.id })
    }
}
