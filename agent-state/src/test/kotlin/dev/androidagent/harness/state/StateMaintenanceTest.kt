// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMaintenanceTest {
    @Test
    fun retentionBoundsActivityAndKeepsReferencedExpiredEvidence() {
        val snapshot = emptyAgentStateSnapshot().copy(
            events = (1L..4L).map { index ->
                AgentStateEvent(
                    id = "event-$index",
                    type = "test",
                    source = "host:test",
                    summary = "event $index",
                    evidenceRefs = if (index == 4L) listOf("referenced") else emptyList(),
                    createdAtEpochMillis = index
                )
            },
            evidence = listOf(
                AgentStateEvidence(
                    id = "referenced",
                    source = "tool:test",
                    summary = "still referenced",
                    contentHash = "hash-a",
                    privacy = ContextPrivacy.INTERNAL,
                    trust = ContextTrust.TOOL_OBSERVED,
                    observedAtEpochMillis = 1,
                    validUntilEpochMillis = 2
                ),
                AgentStateEvidence(
                    id = "expired",
                    source = "tool:test",
                    summary = "not referenced",
                    contentHash = "hash-b",
                    privacy = ContextPrivacy.INTERNAL,
                    trust = ContextTrust.TOOL_OBSERVED,
                    observedAtEpochMillis = 1,
                    validUntilEpochMillis = 2
                )
            )
        )

        val result = AgentStateRetentionEngine().retain(
            snapshot,
            nowEpochMillis = 100,
            policy = AgentStateRetentionPolicy(
                maxEvents = 2,
                maxBriefs = 0,
                maxPsycheObservations = 0,
                expiredEvidenceGraceMillis = 0
            )
        )

        assertEquals(listOf("event-3", "event-4"), result.snapshot.events.map { it.id })
        assertEquals(listOf("referenced"), result.snapshot.evidence.map { it.id })
        assertEquals(2, result.report.removedEvents)
        assertEquals(1, result.report.removedEvidence)
        assertTrue(result.report.removedRecords > 0)
    }
}
