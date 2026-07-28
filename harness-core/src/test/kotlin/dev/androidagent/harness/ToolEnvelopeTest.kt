// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEnvelopeTest {
    @Test
    fun legacySuccessBecomesBoundedEnvelope() {
        val envelope = AgentToolResultEnvelope.fromLegacy(
            result = AgentToolResult.success("done"),
            createdAtEpochMillis = 123L
        )

        assertEquals(AgentToolResultStatus.SUCCESS, envelope.status)
        assertEquals("done", envelope.summary)
        assertEquals(123L, envelope.createdAtEpochMillis)
        assertFalse(envelope.isError)
    }

    @Test
    fun explicitEnvelopeIsPreserved() {
        val explicit = AgentToolResultEnvelope(
            status = AgentToolResultStatus.UNAVAILABLE,
            summary = "permission missing",
            privacy = AgentPrivacyLabel.SENSITIVE,
            createdAtEpochMillis = 10L,
            expiresAtEpochMillis = 20L
        )

        val converted = AgentToolResultEnvelope.fromLegacy(
            AgentToolResult.failure("legacy", explicit),
            createdAtEpochMillis = 11L
        )

        assertEquals(explicit, converted)
        assertTrue(converted.isError)
    }

    @Test
    fun capabilitySeparatesReadsAndMutation() {
        assertFalse(AgentToolCapability.localRead("todo").mayMutate)
        assertTrue(
            AgentToolCapability(
                sideEffect = AgentToolSideEffect.EXTERNAL_WRITE,
                risk = AgentToolRisk.HIGH
            ).mayMutate
        )
    }

    @Test
    fun legacyProviderContentIsTruncatedToEnvelopeLimit() {
        val result = AgentToolResult.success(
            "x".repeat(AgentToolResultEnvelope.MAX_PROVIDER_CONTENT_CHARS + 100)
        )

        val envelope = AgentToolResultEnvelope.fromLegacy(
            result = result,
            createdAtEpochMillis = 1L
        )

        assertEquals(AgentToolResultEnvelope.MAX_SUMMARY_CHARS, envelope.summary.length)
        assertTrue(envelope.summary.endsWith("…"))
    }

    @Test
    fun explicitEnvelopeRejectsUnboundedProviderFields() {
        assertThrows(IllegalArgumentException::class.java) {
            envelope(
                summary = "x".repeat(AgentToolResultEnvelope.MAX_SUMMARY_CHARS + 1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(
                dataJson = "x".repeat(AgentToolResultEnvelope.MAX_DATA_JSON_CHARS + 1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(
                evidence = List(AgentToolResultEnvelope.MAX_REFERENCES + 1) { index ->
                    AgentEvidenceRef(id = "e$index", source = "test")
                }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(
                evidence = listOf(
                    AgentEvidenceRef(
                        id = "evidence",
                        source = "test",
                        summary = "x".repeat(
                            AgentEvidenceRef.MAX_REFERENCE_SUMMARY_CHARS + 1
                        )
                    )
                )
            )
        }
    }

    @Test
    fun rawPayloadRejectsOversizedContent() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentRawPayload(
                ref = "raw",
                content = ByteArray(AgentRawPayload.MAX_CONTENT_BYTES + 1),
                mediaType = "application/octet-stream",
                privacy = AgentPrivacyLabel.RESTRICTED,
                scope = AgentRawPayloadScope("run", "session", "call"),
                createdAtEpochMillis = 1L,
                expiresAtEpochMillis = 2L
            )
        }
    }

    private fun envelope(
        summary: String = "ok",
        dataJson: String? = null,
        evidence: List<AgentEvidenceRef> = emptyList()
    ) = AgentToolResultEnvelope(
        status = AgentToolResultStatus.SUCCESS,
        summary = summary,
        dataJson = dataJson,
        evidence = evidence,
        createdAtEpochMillis = 1L
    )
}
