package me.rerere.rikkahub.data.codex

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody

class CodexProvider(
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
    private val json: Json,
    private val scope: AppScope,
) : Provider<ProviderSetting.Codex> {

    override suspend fun listModels(providerSetting: ProviderSetting.Codex): List<Model> =
        withContext(Dispatchers.IO) {
            // Model discovery only needs a valid OAuth session. It must keep working when the
            // generation quota is exhausted, otherwise the Models tab incorrectly reports
            // "No available Codex account" while the account is still signed in.
            val account = repository.acquireAccountForMetadata()
            val request = Request.Builder()
                .url("$CODEX_API_BASE/models?client_version=$CODEX_CLIENT_VERSION")
                .codexHeaders(account)
                .get()
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to get Codex models: ${response.code} ${response.body.string()}")
            }
            val models = (json.parseToJsonElement(response.body.string()) as? JsonObject)
                ?.get("models") as? JsonArray
                ?: return@withContext emptyList()
            models.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                if (item["visibility"]?.jsonPrimitive?.contentOrNull != "list") {
                    return@mapNotNull null
                }
                val slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val modalities = item["input_modalities"]?.jsonArray
                    ?.mapNotNull { modality ->
                        when ((modality as? JsonPrimitive)?.contentOrNull) {
                            "text" -> Modality.TEXT
                            "image" -> Modality.IMAGE
                            else -> null
                        }
                    }
                    ?.ifEmpty { listOf(Modality.TEXT) }
                    ?: listOf(Modality.TEXT, Modality.IMAGE)
                Model(
                    modelId = slug,
                    displayName = item["display_name"]?.jsonPrimitive?.contentOrNull ?: slug,
                    inputModalities = modalities,
                    abilities = buildList {
                        add(ModelAbility.TOOL)
                        if (
                            item["supported_reasoning_levels"]?.jsonArray?.isNotEmpty() == true ||
                            item["supports_reasoning_summary_parameter"]
                                ?.jsonPrimitive?.booleanOrNull == true ||
                            item["supports_reasoning_summaries"]?.jsonPrimitive?.booleanOrNull == true
                        ) {
                            add(ModelAbility.REASONING)
                        }
                    },
                )
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val account = repository.acquireAccount(params.model.modelId)
        return responseApiFor(account).generateText(
            providerSetting = syntheticSetting(providerSetting, account),
            messages = withDefaultInstructions(messages),
            params = withCodexParams(params, account, stream = false),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val account = repository.acquireAccount(params.model.modelId)
        return responseApiFor(account).streamText(
            providerSetting = syntheticSetting(providerSetting, account),
            messages = withDefaultInstructions(messages),
            params = withCodexParams(params, account, stream = true),
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by the Codex provider")
    }

    private fun Request.Builder.codexHeaders(account: CodexAccount): Request.Builder {
        header("Authorization", "Bearer ${account.accessToken}")
        codexProtocolHeaders(account.chatgptAccountId).forEach { protocolHeader ->
            header(protocolHeader.name, protocolHeader.value)
        }
        return this
    }

    // apiKey = the account's own OAuth token, so ResponseAPI's normal "Authorization: Bearer
    // <apiKey>" header lands on exactly the token codexHeaders used to set by hand.
    private fun syntheticSetting(providerSetting: ProviderSetting.Codex, account: CodexAccount) =
        ProviderSetting.OpenAI(
            id = providerSetting.id,
            enabled = providerSetting.enabled,
            name = providerSetting.name,
            models = providerSetting.models,
            baseUrl = CODEX_API_BASE,
            apiKey = account.accessToken,
            useResponseApi = true,
        )

    // The Codex backend needs a system/instructions item to behave; fall back to a generic one
    // when the caller didn't supply a system message, same as the request body used to do by
    // hand via the `instructions` field.
    private fun withDefaultInstructions(messages: List<UIMessage>): List<UIMessage> =
        if (messages.any { it.role == MessageRole.SYSTEM }) {
            messages
        } else {
            listOf(UIMessage.system(DEFAULT_INSTRUCTIONS)) + messages
        }

    private fun withCodexParams(
        params: TextGenerationParams,
        account: CodexAccount,
        stream: Boolean,
    ): TextGenerationParams {
        val reasoningOverride = codexReasoningOverride(
            modelId = params.model.modelId,
            level = params.reasoningLevel,
            supportsReasoning = params.model.abilities.contains(ModelAbility.REASONING),
        )
        return params.copy(
            customHeaders = params.customHeaders.filterNot { header ->
                header.name.lowercase() in CODEX_RESERVED_HEADER_NAMES
            } + codexProtocolHeaders(account.chatgptAccountId, stream = stream),
            customBody = params.customBody + listOfNotNull(
                reasoningOverride?.let { reasoning ->
                    CustomBody(
                        key = "reasoning",
                        value = reasoning,
                    )
                },
            ),
        )
    }

    /**
     * Wraps [client] with an account-scoped interceptor so the same response that carries the
     * model reply also carries the account's rate-limit headers (quota tracking) and a 401
     * (invalidated token) signal, without a second network round-trip. Also patches a missing
     * Content-Type so OkHttp's SSE factory recognizes the stream - some Codex backend responses
     * omit it.
     */
    private fun responseApiFor(account: CodexAccount): ResponseAPI {
        val accountAwareClient = client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                parseCodexUsage(response.headers)?.let { usage ->
                    scope.launch { repository.updateUsage(account.id, usage) }
                }
                if (response.code == 401) {
                    scope.launch { repository.markInvalid(account.id) }
                }
                if (response.isSuccessful && response.header("Content-Type") == null) {
                    val body = response.body
                    response.newBuilder()
                        .header("Content-Type", "text/event-stream")
                        .body(
                            body.source().asResponseBody(
                                contentType = "text/event-stream".toMediaType(),
                                contentLength = body.contentLength(),
                            )
                        )
                        .build()
                } else {
                    response
                }
            }
            .build()
        return ResponseAPI(accountAwareClient)
    }

    private companion object {
        const val CODEX_API_BASE = "${CodexAccountRepository.CODEX_BASE_URL}/codex"
        const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."
    }
}

internal const val CODEX_CLIENT_VERSION = "0.154.0"
internal const val CODEX_ORIGINATOR = "codex_cli_rs"
private val CODEX_RESERVED_HEADER_NAMES = setOf(
    "accept",
    "authorization",
    "chatgpt-account-id",
    "openai-beta",
    "originator",
    "user-agent",
    "version",
)

// The Codex backend routes model availability using the advertised client version and User-Agent.
// Keep both aligned with the current stable Codex CLI so eligible models are not hidden behind an
// obsolete minimum-client-version gate.
internal fun codexUserAgent(): String =
    "$CODEX_ORIGINATOR/$CODEX_CLIENT_VERSION (Android ${Build.VERSION.RELEASE}; " +
        "${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"})"

internal fun codexProtocolHeaders(
    chatgptAccountId: String,
    stream: Boolean = false,
    userAgent: String = codexUserAgent(),
): List<CustomHeader> = buildList {
    add(CustomHeader("ChatGPT-Account-Id", chatgptAccountId))
    add(CustomHeader("version", CODEX_CLIENT_VERSION))
    add(CustomHeader("originator", CODEX_ORIGINATOR))
    add(CustomHeader("User-Agent", userAgent))
    if (stream) add(CustomHeader("Accept", "text/event-stream"))
}

internal fun codexReasoningOverride(
    modelId: String,
    level: ReasoningLevel,
    supportsReasoning: Boolean,
): JsonObject? {
    if (!supportsReasoning) return null
    val effort = codexReasoningEffort(level) ?: return null
    if (isCodexSparkModel(modelId) && level == ReasoningLevel.OFF) return null
    return buildJsonObject {
        put("effort", effort)
        if (!isCodexSparkModel(modelId)) {
            put("summary", "auto")
        }
    }
}

internal fun codexReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> "xhigh"
        // Codex's API tops out at "xhigh"; mirror XHIGH rather than sending an effort it doesn't know.
        ReasoningLevel.MAX -> "xhigh"
        ReasoningLevel.OFF -> "none"
    }
}

internal fun parseCodexIncompleteMessage(payload: JsonObject): String {
    val reason = runCatching {
        payload["response"]?.jsonObject
            ?.get("incomplete_details")?.jsonObject
            ?.get("reason")?.jsonPrimitive?.contentOrNull
            ?: payload["incomplete_details"]?.jsonObject
                ?.get("reason")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return if (reason.isNullOrBlank()) {
        "Codex response incomplete"
    } else {
        "Codex response incomplete: $reason"
    }
}
