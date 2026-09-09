package me.rerere.rikkahub.ui.pages.setting.locallm

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.locallm.litert.LiteRtModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingLocalLlmCapabilitiesTest {
    @Test
    fun `LiteRT files derive capabilities through the LiteRT metadata catalog`() {
        val direct = LiteRtModelMetadata.deriveCapabilities("some-litert-file.litertlm")
        val viaHelper = deriveLocalModelCapabilities("some-litert-file.litertlm")
        assertEquals(direct, viaHelper)
    }

    @Test
    fun `enableAfterFirstDownload enables LiteRT`() {
        val enabled = enableAfterFirstDownload(ProviderSetting.LiteRtLocal(enabled = false))
        assertTrue((enabled as ProviderSetting.LiteRtLocal).enabled)
    }

    @Test
    fun `enableAfterFirstDownload leaves an unrelated provider untouched`() {
        val original = ProviderSetting.OpenAI(enabled = false)
        assertEquals(original, enableAfterFirstDownload(original))
    }

    @Test
    fun `registerInstalledModel appends a new LiteRT model`() {
        val provider = ProviderSetting.LiteRtLocal()
        val model = Model(modelId = "model.litertlm", displayName = "model.litertlm")
        val result = registerInstalledModel(provider, model) as ProviderSetting.LiteRtLocal
        assertEquals(listOf(model), result.models)
    }

    @Test
    fun `registerInstalledModel updates an existing LiteRT model without duplicating it`() {
        val original = Model(modelId = "model.litertlm", displayName = "old name")
        val provider = ProviderSetting.LiteRtLocal(models = listOf(original))
        val reinstalled = Model(modelId = "model.litertlm", displayName = "model.litertlm")
        val result = registerInstalledModel(provider, reinstalled) as ProviderSetting.LiteRtLocal
        assertEquals(1, result.models.size)
        assertEquals(original.id, result.models.single().id)
        assertEquals("model.litertlm", result.models.single().displayName)
    }
}
