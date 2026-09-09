package me.rerere.rikkahub.data.openrouter

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterOAuthManagerTest {
    @Test
    fun `pkce S256 challenge matches RFC 7636 example`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            openRouterCodeChallenge(
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
            ),
        )
    }
}
