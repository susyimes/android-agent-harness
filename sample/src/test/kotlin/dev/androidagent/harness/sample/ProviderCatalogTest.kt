// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test
    fun `provider ids round trip and unknown ids stay offline`() {
        ProviderKind.entries.forEach { provider ->
            assertEquals(provider, ProviderKind.fromId(provider.id))
        }
        assertEquals(ProviderKind.OFFLINE, ProviderKind.fromId("not-a-provider"))
        assertEquals(ProviderKind.OFFLINE, ProviderKind.fromId(null))
    }

    @Test
    fun `plan providers use subscription endpoints and known model defaults`() {
        assertEquals(
            "https://api.kimi.com/coding/v1",
            ProviderKind.KIMI_PLAN.defaultBaseUrl
        )
        assertEquals("k3", ProviderKind.KIMI_PLAN.defaultModel)
        assertTrue(ProviderKind.KIMI_PLAN.models.any { preset -> preset.id == "kimi-k2.7-code" })

        assertEquals(
            "https://ark.cn-beijing.volces.com/api/plan/v3",
            ProviderKind.ARK_PLAN.defaultBaseUrl
        )
        assertEquals("doubao-seed-2.0-pro", ProviderKind.ARK_PLAN.defaultModel)
        assertEquals(
            setOf(
                "doubao-seed-2.0-pro",
                "doubao-seed-2.0-lite",
                "doubao-seed-2.0-mini",
                "doubao-seed-2.1-turbo",
                "doubao-seed-evolving",
                "glm-5.2",
                "kimi-k3",
                "kimi-k2.7-code",
                "kimi-k2.6",
                "minimax-m3",
                "minimax-m2.7",
                "deepseek-v4-pro",
                "deepseek-v4-flash"
            ),
            ProviderKind.ARK_PLAN.models.map { preset -> preset.id }.toSet()
        )
    }

    @Test
    fun `credential modes match provider product behavior`() {
        assertEquals(ProviderCredentialMode.NONE, ProviderKind.OFFLINE.credentialMode)
        assertEquals(ProviderCredentialMode.CODEX_LOGIN, ProviderKind.CODEX.credentialMode)
        assertEquals(ProviderCredentialMode.API_KEY, ProviderKind.KIMI_PLAN.credentialMode)
        assertEquals(ProviderCredentialMode.API_KEY, ProviderKind.ARK_PLAN.credentialMode)
        assertEquals(ProviderCredentialMode.API_KEY, ProviderKind.CUSTOM.credentialMode)
    }
}
