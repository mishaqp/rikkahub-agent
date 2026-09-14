package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import okhttp3.Headers
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CodexAccountTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `identity is parsed from OAuth ID token`() {
        val payload = """
            {
              "sub": "user-1",
              "email": "user@example.com",
              "name": "Test User",
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "account-1",
                "chatgpt_user_id": "user-1"
              }
            }
        """.trimIndent()
        val token = listOf("{}", payload, "signature")
            .joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray()) }

        val identity = parseCodexIdentity(token, json)

        assertEquals("account-1", identity.accountId)
        assertEquals("user-1", identity.userId)
        assertEquals("user@example.com", identity.email)
        assertEquals("Test User", identity.name)
    }

    @Test
    fun `usage JSON exposes five hour and weekly windows`() {
        val usage = parseCodexUsage(
            json.parseToJsonElement(
                """
                    {
                      "rate_limit": {
                        "primary_window": {
                          "used_percent": 25.5,
                          "limit_window_seconds": 18000,
                          "reset_at": 2000000000
                        },
                        "secondary_window": {
                          "used_percent": 70,
                          "limit_window_seconds": 604800,
                          "reset_at": 2000600000
                        }
                      }
                    }
                """.trimIndent()
            ).let { it as kotlinx.serialization.json.JsonObject }
        )

        assertEquals(300L, usage.primary?.windowMinutes)
        assertEquals(25.5, usage.primary?.usedPercent ?: 0.0, 0.0)
        assertEquals(10_080L, usage.secondary?.windowMinutes)
        assertEquals(70.0, usage.secondary?.usedPercent ?: 0.0, 0.0)
    }

    @Test
    fun `usage JSON exposes the separate Spark limit`() {
        val usage = parseCodexUsage(
            json.parseToJsonElement(
                """
                    {
                      "rate_limit": {
                        "primary_window": { "used_percent": 100 }
                      },
                      "additional_rate_limits": [
                        {
                          "limit_name": "GPT-5.3-Codex-Spark",
                          "metered_feature": "codex_bengalfox",
                          "rate_limit": {
                            "primary_window": {
                              "used_percent": 25,
                              "reset_at": 2000000000
                            }
                          }
                        }
                      ]
                    }
                """.trimIndent()
            ).let { it as kotlinx.serialization.json.JsonObject }
        )

        val spark = usage.additional[CODEX_SPARK_LIMIT_ID]
        assertEquals(CODEX_SPARK_MODEL_ID, spark?.name?.lowercase())
        assertEquals(25.0, spark?.primary?.usedPercent ?: 0.0, 0.0)
    }

    @Test
    fun `usage headers support reset after seconds`() {
        val before = System.currentTimeMillis() / 1000
        val usage = parseCodexUsage(
            Headers.headersOf(
                "x-codex-primary-used-percent", "50",
                "x-codex-primary-reset-after-seconds", "60",
            )
        )

        assertNotNull(usage)
        assertTrue(usage!!.primary!!.resetsAt!! >= before + 60)
    }

    @Test
    fun `usage headers expose arbitrary Codex limit families`() {
        val usage = parseCodexUsage(
            Headers.headersOf(
                "x-codex-bengalfox-primary-used-percent", "75",
                "x-codex-bengalfox-primary-window-minutes", "10080",
                "x-codex-bengalfox-limit-name", "GPT-5.3-Codex-Spark",
            )
        )

        val spark = usage?.additional?.get(CODEX_SPARK_LIMIT_ID)
        assertEquals(75.0, spark?.primary?.usedPercent ?: 0.0, 0.0)
        assertEquals(10_080L, spark?.primary?.windowMinutes)
    }

    @Test
    fun `free usage exposes monthly limit without empty secondary window`() {
        val usage = parseCodexUsage(
            json.parseToJsonElement(
                """
                    {
                      "rate_limit": {
                        "primary_window": {
                          "used_percent": 12,
                          "limit_window_seconds": 2592000
                        },
                        "secondary_window": null
                      }
                    }
                """.trimIndent()
            ).let { it as kotlinx.serialization.json.JsonObject }
        )

        assertEquals(43_200L, usage.primary?.windowMinutes)
        assertNull(usage.secondary)
    }

    @Test
    fun `round robin skips disabled invalid and exhausted accounts`() {
        val accounts = listOf(
            account("disabled", enabled = false),
            account("invalid", status = CodexTokenStatus.INVALID),
            account(
                "exhausted",
                usage = CodexUsageSnapshot(
                    primary = CodexUsageWindow(usedPercent = 100.0, resetsAt = 2_000_000_000)
                )
            ),
            account("available"),
        )

        assertEquals(3, selectCodexAccountIndex(accounts, startIndex = 0, nowMillis = 1_000))
        assertNull(selectCodexAccountIndex(accounts.dropLast(1), startIndex = 0, nowMillis = 1_000))
    }

    @Test
    fun `Spark can use its separate quota when regular Codex quota is exhausted`() {
        val accounts = listOf(
            account("disabled", enabled = false),
            account("invalid", status = CodexTokenStatus.INVALID),
            account(
                "spark-available",
                usage = CodexUsageSnapshot(
                    primary = CodexUsageWindow(usedPercent = 100.0, resetsAt = 2_000_000_000)
                )
            ),
        )

        assertNull(
            selectCodexAccountIndex(
                accounts = accounts,
                startIndex = 0,
                modelId = "gpt-5.6-sol",
                nowMillis = 1_000,
            )
        )
        assertEquals(
            2,
            selectCodexAccountIndex(
                accounts = accounts,
                startIndex = 0,
                modelId = CODEX_SPARK_MODEL_ID,
                nowMillis = 1_000,
            )
        )
    }

    @Test
    fun `Spark account selection skips its exhausted separate quota`() {
        fun sparkUsage(usedPercent: Double) = CodexUsageSnapshot(
            primary = CodexUsageWindow(usedPercent = 100.0, resetsAt = 2_000_000_000),
            additional = mapOf(
                CODEX_SPARK_LIMIT_ID to CodexUsageLimit(
                    name = CODEX_SPARK_MODEL_ID,
                    primary = CodexUsageWindow(
                        usedPercent = usedPercent,
                        resetsAt = 2_000_000_000,
                    ),
                )
            ),
        )
        val accounts = listOf(
            account("spark-exhausted", usage = sparkUsage(100.0)),
            account("spark-available", usage = sparkUsage(40.0)),
        )

        assertEquals(
            1,
            selectCodexAccountIndex(
                accounts = accounts,
                startIndex = 0,
                nowMillis = 1_000,
                modelId = CODEX_SPARK_MODEL_ID,
            )
        )
    }

    @Test
    fun `metadata account selection ignores exhausted quota`() {
        val accounts = listOf(
            account("disabled", enabled = false),
            account("invalid", status = CodexTokenStatus.INVALID),
            account(
                "exhausted-but-signed-in",
                usage = CodexUsageSnapshot(
                    primary = CodexUsageWindow(usedPercent = 100.0, resetsAt = 2_000_000_000)
                )
            ),
        )

        assertEquals(2, selectCodexMetadataAccountIndex(accounts, startIndex = 0))
        assertNull(selectCodexMetadataAccountIndex(accounts.take(2), startIndex = 0))
    }

    @Test
    fun `auto reasoning omits Codex effort`() {
        assertNull(codexReasoningEffort(ReasoningLevel.AUTO))
        assertEquals("high", codexReasoningEffort(ReasoningLevel.HIGH))
    }

    @Test
    fun `Spark Codex override never reintroduces reasoning summary`() {
        val high = codexReasoningOverride(
            modelId = CODEX_SPARK_MODEL_ID,
            level = ReasoningLevel.HIGH,
            supportsReasoning = true,
        )

        assertEquals("high", high?.get("effort")?.jsonPrimitive?.content)
        assertNull(high?.get("summary"))
        assertNull(
            codexReasoningOverride(
                modelId = CODEX_SPARK_MODEL_ID,
                level = ReasoningLevel.AUTO,
                supportsReasoning = true,
            )
        )
        assertNull(
            codexReasoningOverride(
                modelId = CODEX_SPARK_MODEL_ID,
                level = ReasoningLevel.OFF,
                supportsReasoning = true,
            )
        )
    }

    @Test
    fun `regular Codex reasoning keeps summary behavior`() {
        val regular = codexReasoningOverride(
            modelId = "gpt-5.6-sol",
            level = ReasoningLevel.HIGH,
            supportsReasoning = true,
        )

        assertEquals("high", regular?.get("effort")?.jsonPrimitive?.content)
        assertEquals("auto", regular?.get("summary")?.jsonPrimitive?.content)
        assertEquals(
            "none",
            codexReasoningOverride(
                modelId = "gpt-5.6-sol",
                level = ReasoningLevel.OFF,
                supportsReasoning = true,
            )?.get("effort")?.jsonPrimitive?.content,
        )
        assertNull(
            codexReasoningOverride(
                modelId = "gpt-5.6-sol",
                level = ReasoningLevel.HIGH,
                supportsReasoning = false,
            )
        )
    }

    @Test
    fun `Codex OAuth and refresh payload match current CLI contract`() {
        assertEquals("0.154.0", CODEX_CLIENT_VERSION)
        assertEquals(
            setOf(
                "openid",
                "profile",
                "email",
                "offline_access",
                "api.connectors.read",
                "api.connectors.invoke",
            ),
            CodexOAuthManager.DEFAULT_SCOPES.split(' ').toSet(),
        )

        val refresh = buildCodexRefreshBody("rotating-refresh-token")
        assertEquals(CodexOAuthManager.CLIENT_ID, refresh["client_id"]?.jsonPrimitive?.content)
        assertEquals("refresh_token", refresh["grant_type"]?.jsonPrimitive?.content)
        assertEquals("rotating-refresh-token", refresh["refresh_token"]?.jsonPrimitive?.content)
        assertNull(refresh["scope"])

        val request = buildCodexRefreshRequest(
            refreshToken = "rotating-refresh-token",
            userAgent = "codex_cli_rs/0.154.0 (test)",
        )
        val encodedBody = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("application/json", request.body?.contentType()?.toString())
        assertEquals(CODEX_ORIGINATOR, request.header("originator"))
        assertEquals("codex_cli_rs/0.154.0 (test)", request.header("User-Agent"))
        assertNull(request.header("version"))
        assertNull(request.header("OpenAI-Beta"))
        assertEquals(refresh, json.parseToJsonElement(encodedBody))

        val responseHeaders = codexProtocolHeaders(
            chatgptAccountId = "account-1",
            stream = true,
            userAgent = "codex_cli_rs/0.154.0 (test)",
        ).associate { it.name.lowercase() to it.value }
        assertEquals("account-1", responseHeaders["chatgpt-account-id"])
        assertEquals("0.154.0", responseHeaders["version"])
        assertEquals(CODEX_ORIGINATOR, responseHeaders["originator"])
        assertEquals("codex_cli_rs/0.154.0 (test)", responseHeaders["user-agent"])
        assertEquals("text/event-stream", responseHeaders["accept"])
        assertNull(responseHeaders["openai-beta"])
    }

    @Test
    fun `incomplete response exposes its reason`() {
        val payload = json.parseToJsonElement(
            """
                {
                  "response": {
                    "incomplete_details": {
                      "reason": "max_output_tokens"
                    }
                  }
                }
            """.trimIndent()
        ).let { it as kotlinx.serialization.json.JsonObject }

        assertEquals(
            "Codex response incomplete: max_output_tokens",
            parseCodexIncompleteMessage(payload),
        )
    }

    @Test
    fun `only authentication rejection invalidates refreshed account`() {
        assertTrue(
            isCodexRefreshAuthenticationFailure(
                statusCode = 400,
                responseBody = """{"error":"invalid_grant"}""",
                json = json,
            )
        )
        assertTrue(isCodexRefreshAuthenticationFailure(401, "", json))
        assertTrue(
            isCodexRefreshAuthenticationFailure(
                statusCode = 400,
                responseBody = """{"error":{"code":"refresh_token_reused"}}""",
                json = json,
            )
        )
        assertTrue(
            isCodexRefreshAuthenticationFailure(
                statusCode = 403,
                responseBody = """{"code":"refresh_token_invalidated"}""",
                json = json,
            )
        )
        assertEquals(
            false,
            isCodexRefreshAuthenticationFailure(
                statusCode = 500,
                responseBody = """{"error":"server_error"}""",
                json = json,
            )
        )
        assertEquals(
            false,
            isCodexRefreshAuthenticationFailure(
                statusCode = 400,
                responseBody = """{"error":"invalid_token"}""",
                json = json,
            )
        )
        assertEquals(
            false,
            isCodexRefreshAuthenticationFailure(
                statusCode = 400,
                responseBody = "temporarily malformed",
                json = json,
            )
        )
    }

    private fun account(
        id: String,
        enabled: Boolean = true,
        status: CodexTokenStatus = CodexTokenStatus.AVAILABLE,
        usage: CodexUsageSnapshot? = null,
    ) = CodexAccount(
        id = id,
        name = id,
        chatgptAccountId = "workspace-$id",
        accessToken = "token",
        refreshToken = "refresh",
        expiresAt = Long.MAX_VALUE,
        enabled = enabled,
        tokenStatus = status,
        usage = usage,
    )
}
