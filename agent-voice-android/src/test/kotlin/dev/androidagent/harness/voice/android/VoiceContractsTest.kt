// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.voice.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceContractsTest {
    @Test
    fun `transcripts are not retained unless explicitly enabled`() {
        val disabled = InMemoryVoiceSessionRepository()
        disabled.save(transcript("one"))
        assertTrue(disabled.list("session").isEmpty())

        val enabled = InMemoryVoiceSessionRepository(persistenceEnabled = true)
        enabled.save(transcript("one"))
        enabled.save(transcript("two"))
        assertEquals(listOf("one", "two"), enabled.list("session").map { it.text })
        assertEquals(2, enabled.deleteSession("session"))
    }

    private fun transcript(text: String) = VoiceTranscript(
        id = text,
        sessionId = "session",
        text = text,
        createdAtEpochMillis = 1L,
        isFinal = true
    )
}
