// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleConfigTest {

    @Test
    fun usesDefaultsWhenEnvironmentIsEmpty() {
        val config = OpenAiCompatibleConfig.fromEnvironment { null }

        assertEquals("https://api.openai.com/v1", config.baseUrl)
        assertEquals("gpt-4o-mini", config.model)
        assertNull(config.keyValue)
        assertNull(config.parallelToolCalls)
        assertNull(config.historyCharBudget)
        assertTrue(config.extraHeaders.isEmpty())
        assertTrue(config.extraBodyFields.isEmpty())
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

    @Test
    fun toStringRedactsTheCredential() {
        val config = OpenAiCompatibleConfig(
            baseUrl = "https://example.invalid/v1",
            model = "test-model",
            keyValue = RAW_CREDENTIAL,
            parallelToolCalls = false,
            historyCharBudget = 4096
        )

        val rendered = config.toString()

        assertFalse(
            "The credential must never appear in toString: $rendered",
            rendered.contains(RAW_CREDENTIAL)
        )
        assertFalse(
            "Not even a fragment of the credential may appear: $rendered",
            rendered.contains("must-not-appear")
        )
        assertTrue(rendered.contains(OpenAiCompatibleConfig.REDACTED))
        assertTrue(rendered.contains("baseUrl=https://example.invalid/v1"))
        assertTrue(rendered.contains("model=test-model"))
        assertTrue(rendered.contains("parallelToolCalls=false"))
        assertTrue(rendered.contains("historyCharBudget=4096"))
    }

    @Test
    fun toStringOfInterpolatedConfigAlsoRedactsTheCredential() {
        val config = OpenAiCompatibleConfig(
            baseUrl = "https://example.invalid/v1",
            model = "test-model",
            keyValue = RAW_CREDENTIAL
        )

        val logLine = "provider config: $config"
        val listLine = listOf(config).toString()

        assertFalse(logLine, logLine.contains(RAW_CREDENTIAL))
        assertFalse(listLine, listLine.contains(RAW_CREDENTIAL))
    }

    @Test
    fun toStringMarksAnAbsentCredentialAsNull() {
        val rendered = OpenAiCompatibleConfig(
            baseUrl = "https://example.invalid/v1",
            model = "test-model",
            keyValue = null
        ).toString()

        assertTrue(rendered, rendered.contains("keyValue=null"))
        assertFalse(rendered, rendered.contains(OpenAiCompatibleConfig.REDACTED))
    }

    @Test
    fun rejectsNonPositiveHistoryCharBudget() {
        listOf(0, -1).forEach { budget ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                OpenAiCompatibleConfig(
                    baseUrl = "https://example.invalid/v1",
                    model = "test-model",
                    historyCharBudget = budget
                )
            }
            assertTrue(
                error.message ?: "",
                (error.message ?: "").contains("History char budget must be positive")
            )
        }
    }

    @Test
    fun rejectsOverridesOfCoreProtocolFields() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                extraHeaders = mapOf("Authorization" to "unexpected")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                extraBodyFields = mapOf("model" to "unexpected")
            )
        }
    }

    private companion object {
        const val RAW_CREDENTIAL = "sk-must-not-appear-in-any-rendering-0001"
    }
}
