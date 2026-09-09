package me.rerere.locallm

import kotlinx.serialization.Serializable

/** The local inference runtime shipped by RikkaHub Agent. */
@Serializable
sealed class LocalRuntime(val displayName: String, val fileExtension: String) {
    @Serializable data object LiteRT : LocalRuntime(displayName = "LiteRT", fileExtension = "litertlm")
}
