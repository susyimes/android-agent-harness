// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.context.ContextPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStateVaultTest {
    @Test
    fun documentWritesUseOptimisticRevisionAndFailedTransactionRollsBack() {
        val vault = InMemoryAgentStateVault(FixedAgentClock(100L))
        val first = vault.transaction {
            writeDocument(
                AgentStateDocumentWrite(
                    id = "current",
                    collection = AgentStateCollection.CURRENT_STATE,
                    title = "Current state",
                    content = "Ready",
                    source = "host:test",
                    expectedRevision = 0
                )
            )
        }

        assertEquals(1L, first.revision)
        val failed = runCatching {
            vault.transaction {
                writeDocument(
                    AgentStateDocumentWrite(
                        id = "temporary",
                        collection = AgentStateCollection.CURRENT_STATE,
                        title = "Temporary",
                        content = "Must roll back",
                        source = "host:test"
                    )
                )
                writeDocument(
                    AgentStateDocumentWrite(
                        id = "current",
                        collection = AgentStateCollection.CURRENT_STATE,
                        title = "Current state",
                        content = "Conflict",
                        source = "host:test",
                        expectedRevision = 0
                    )
                )
            }
        }

        assertTrue(failed.exceptionOrNull() is AgentStateConflictException)
        assertNull(
            vault.read {
                document(AgentStateCollection.CURRENT_STATE, "temporary")
            }
        )
        assertEquals(
            "Ready",
            vault.read {
                document(AgentStateCollection.CURRENT_STATE, "current")!!.content
            }
        )
    }

    @Test
    fun snapshotKeepsTypedCollectionsSeparate() {
        val vault = InMemoryAgentStateVault(FixedAgentClock(100L))
        vault.transaction {
            putEvidence(
                AgentStateEvidence(
                    id = "evidence-1",
                    source = "user:test",
                    summary = "User explicitly asked to remember.",
                    contentHash = "hash",
                    privacy = ContextPrivacy.INTERNAL,
                    trust = dev.androidagent.harness.context.ContextTrust.USER_CONFIRMED,
                    observedAtEpochMillis = 100L
                )
            )
            appendEvent(
                AgentStateEvent(
                    id = "event-1",
                    type = "TEST",
                    source = "host:test",
                    summary = "Test event",
                    evidenceRefs = listOf("evidence-1"),
                    createdAtEpochMillis = 100L
                )
            )
        }

        val snapshot = vault.snapshot()
        assertEquals(listOf("event-1"), snapshot.events.map(AgentStateEvent::id))
        assertEquals(listOf("evidence-1"), snapshot.evidence.map(AgentStateEvidence::id))
        assertTrue(snapshot.candidates.isEmpty())
    }
}
