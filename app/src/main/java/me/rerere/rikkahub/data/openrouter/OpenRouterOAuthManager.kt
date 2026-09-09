package me.rerere.rikkahub.data.openrouter

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

/**
 * OpenRouter's user-facing OAuth PKCE flow.
 *
 * OpenRouter allows loopback callbacks on any localhost/127.0.0.1 port, so this manager binds an
 * OS-assigned port synchronously with [ServerSocket] before opening the browser. That avoids the
 * asynchronous Ktor bind race that fixed-port OAuth servers can hit on Android.
 *
 * The authorization code is exchanged for a user-controlled OpenRouter API key. OpenRouter does
 * not return a refresh token for this flow: the generated key is the long-lived credential, so we
 * persist it in the existing OpenAI-compatible provider's apiKey field.
 */
class OpenRouterOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsStore: SettingsStore,
) {
    private val _status = MutableStateFlow<OpenRouterOAuthStatus>(OpenRouterOAuthStatus.Idle)
    val status: StateFlow<OpenRouterOAuthStatus> = _status.asStateFlow()

    @Volatile
    private var activeServer: ServerSocket? = null

    @Volatile
    private var activeSession: OAuthSession? = null

    @Synchronized
    fun startLogin(providerId: Uuid) {
        closeActiveServer()

        val verifier = randomUrlSafe(64)
        val challenge = openRouterCodeChallenge(verifier)
        val state = randomUrlSafe(32)
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
                soTimeout = LOGIN_TIMEOUT_MS
            }
        } catch (error: Throwable) {
            _status.value = OpenRouterOAuthStatus.Error(
                error.message ?: "Unable to start OpenRouter OAuth callback server"
            )
            return
        }

        val callbackUrl = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"
        val session = OAuthSession(
            providerId = providerId,
            state = state,
            verifier = verifier,
            callbackUrl = callbackUrl,
        )
        activeServer = server
        activeSession = session
        _status.value = OpenRouterOAuthStatus.Waiting

        scope.launch(Dispatchers.IO) {
            serveCallback(server, session)
        }

        val authUrl = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("callback_url", callbackUrl)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUrl).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { error ->
            closeSession(session)
            _status.value = OpenRouterOAuthStatus.Error(
                error.message ?: "Unable to open the OpenRouter sign-in page"
            )
        }
    }

    fun consumeResult() {
        _status.value = OpenRouterOAuthStatus.Idle
    }

    private suspend fun serveCallback(server: ServerSocket, session: OAuthSession) {
        try {
            while (!server.isClosed) {
                val handled = server.accept().use { socket ->
                    handleSocket(socket, session)
                }
                if (handled) return
            }
        } catch (_: SocketTimeoutException) {
            if (isCurrent(session)) {
                _status.value = OpenRouterOAuthStatus.Error(
                    "OpenRouter sign-in timed out. Please try again."
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (isCurrent(session) && !server.isClosed) {
                _status.value = OpenRouterOAuthStatus.Error(
                    error.message ?: "OpenRouter OAuth callback failed"
                )
            }
        } finally {
            closeSession(session)
        }
    }

    /** Returns true once the OAuth callback itself was handled. */
    private suspend fun handleSocket(socket: Socket, session: OAuthSession): Boolean {
        val requestLine = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
            ?: return false
        val target = requestLine.split(' ').getOrNull(1) ?: return false
        val uri = runCatching { Uri.parse("http://127.0.0.1$target") }.getOrNull()
            ?: return false

        if (uri.path != CALLBACK_PATH) {
            respondHtml(socket, callbackPage(false, "Waiting for OpenRouter authorization…"))
            return false
        }

        val callbackState = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val errorDescription = uri.getQueryParameter("error_description")
        val code = uri.getQueryParameter("code")

        if (callbackState != session.state) {
            _status.value = OpenRouterOAuthStatus.Error("OpenRouter OAuth state mismatch")
            respondHtml(socket, callbackPage(false, "OpenRouter sign-in failed."))
            return true
        }
        if (!error.isNullOrBlank()) {
            val message = errorDescription?.takeIf { it.isNotBlank() } ?: error
            _status.value = OpenRouterOAuthStatus.Error(message)
            respondHtml(socket, callbackPage(false, "OpenRouter sign-in failed."))
            return true
        }
        if (code.isNullOrBlank()) {
            _status.value = OpenRouterOAuthStatus.Error("Missing OpenRouter authorization code")
            respondHtml(socket, callbackPage(false, "OpenRouter sign-in failed."))
            return true
        }

        // Return the browser to RikkaHub immediately; the key exchange continues in this IO job.
        respondHtml(socket, callbackPage(true, "Authorization received. Returning to RikkaHub…"))

        try {
            val apiKey = exchangeCode(code, session.verifier)
            settingsStore.update { settings ->
                settings.copy(
                    providers = settings.providers.map { provider ->
                        if (provider.id == session.providerId && provider is ProviderSetting.OpenAI) {
                            provider.copy(apiKey = apiKey, enabled = true)
                        } else {
                            provider
                        }
                    }
                )
            }
            _status.value = OpenRouterOAuthStatus.Success(session.providerId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _status.value = OpenRouterOAuthStatus.Error(
                error.message ?: "OpenRouter authorization code exchange failed"
            )
        }
        return true
    }

    private suspend fun exchangeCode(code: String, verifier: String): String {
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("code", code)
                put("code_verifier", verifier)
                put("code_challenge_method", "S256")
            }
        ).toRequestBody(JSON_MEDIA_TYPE)

        val response = client.newCall(
            Request.Builder()
                .url(EXCHANGE_URL)
                .header("Accept", "application/json")
                .post(requestBody)
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("OpenRouter key exchange failed: ${response.code} $body")
        }
        return json.parseToJsonElement(body).jsonObject["key"]
            ?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("OpenRouter did not return an API key")
    }

    private fun respondHtml(socket: Socket, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().buffered().use { output ->
            output.write(headers)
            output.write(body)
            output.flush()
        }
    }

    private fun callbackPage(success: Boolean, message: String): String {
        val deepLink = "rikkahub://codex/oauth?target=openrouter"
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="refresh" content="0; url=$deepLink">
                <title>RikkaHub OpenRouter OAuth</title>
              </head>
              <body>
                <p>$message</p>
                <p><a href="$deepLink">Return to RikkaHub</a></p>
                <script>
                  window.location.replace("$deepLink");
                  setTimeout(function () { window.location.href = "$deepLink"; }, 500);
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    @Synchronized
    private fun closeSession(session: OAuthSession) {
        if (activeSession?.state != session.state) return
        runCatching { activeServer?.close() }
        activeServer = null
        activeSession = null
    }

    @Synchronized
    private fun closeActiveServer() {
        runCatching { activeServer?.close() }
        activeServer = null
        activeSession = null
    }

    private fun isCurrent(session: OAuthSession): Boolean = activeSession?.state == session.state

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val AUTH_URL = "https://openrouter.ai/auth"
        const val EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"
        const val CALLBACK_PATH = "/openrouter/oauth/callback"
        const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal fun openRouterCodeChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
    )

private data class OAuthSession(
    val providerId: Uuid,
    val state: String,
    val verifier: String,
    val callbackUrl: String,
)

sealed interface OpenRouterOAuthStatus {
    data object Idle : OpenRouterOAuthStatus
    data object Waiting : OpenRouterOAuthStatus
    data class Success(val providerId: Uuid) : OpenRouterOAuthStatus
    data class Error(val message: String) : OpenRouterOAuthStatus
}
