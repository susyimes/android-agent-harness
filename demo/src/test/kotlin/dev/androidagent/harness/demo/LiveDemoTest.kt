// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDemoTest {

    @Test
    fun liveWithoutCredentialPrintsFriendlySetupMessageAndReturnsNormally() {
        val lines = captureStdout { runLive(emptyList()) { _ -> null } }

        assertEquals("LIVE_SKIPPED=OPENAI_API_KEY is not set", lines.first())
        assertTrue(lines.any { line -> line.contains("OPENAI_BASE_URL") })
        assertTrue(lines.any { line -> line.contains("OPENAI_MODEL") })
        assertTrue(lines.any { line -> line.contains("deepseek-chat") })
        assertTrue(lines.any { line -> line.contains("Exiting normally") })
    }

    @Test
    fun liveWithoutCredentialNamesTheDefaults() {
        val lines = captureStdout { runLive(emptyList()) { _ -> null } }

        assertTrue(lines.any { line -> line.contains("https://api.openai.com/v1") })
        assertTrue(lines.any { line -> line.contains("gpt-4o-mini") })
    }
}
