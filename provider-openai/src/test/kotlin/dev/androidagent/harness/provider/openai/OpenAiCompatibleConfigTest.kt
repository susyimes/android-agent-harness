// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiCompatibleConfigTest {

    @Test
    fun usesDefaultsWhenEnvironmentIsEmpty() {
        val config = OpenAiCompatibleConfig.fromEnvironment { null }

        assertEquals("https://api.openai.com/v1", config.baseUrl)
        assertEquals("gpt-4o-mini", config.model)
        assertNull(config.keyValue)
    }

    @Test
    fun readsOverridesAndTrimsTrailingSlash() {
        val values = mapOf(
            "OPENAI_BASE_URL" to "https://api.deepseek.com/",
            "OPENAI_MODEL" to "deepseek-chat",
            "OPENAI_API_KEY" to "test-credential-123"
        )

        val config = OpenAiCompatibleConfig.fromEnvironment { name -> values[name] }

        assertEquals("https://api.deepseek.com", config.baseUrl)
        assertEquals("deepseek-chat", config.model)
        assertEquals("test-credential-123", config.keyValue)
    }
}
