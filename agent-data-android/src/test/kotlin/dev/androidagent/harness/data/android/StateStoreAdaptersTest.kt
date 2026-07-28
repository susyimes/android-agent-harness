// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.state.AgentStateCollection
import dev.androidagent.harness.state.AgentStateDocumentWrite
import dev.androidagent.harness.state.AgentStateEvent
import dev.androidagent.harness.state.AgentStateRetentionPolicy
import dev.androidagent.harness.state.recordCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StateStoreAdaptersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stateVaultSnapshotSurvivesReopenWithSchemaAndHash() {
        val root = temporaryFolder.newFolder("state")
        val store = AndroidFileAgentStateVault(root, FixedAgentClock(100L))
        store.transaction {
            writeDocument(
                AgentStateDocumentWrite(
                    id = "current",
                    collection = AgentStateCollection.CURRENT_STATE,
                    title = "Current state",
                    content = "Ready",
                    source = "host:test",
                    privacy = ContextPrivacy.INTERNAL
                )
            )
        }

        val info = requireNotNull(store.info())
        val reopened = AndroidFileAgentStateVault(root, FixedAgentClock(200L))

        assertEquals(1, info.schemaVersion)
        assertEquals(64, info.snapshotHash.length)
        assertEquals(
            "Ready",
            reopened.read {
                document(AgentStateCollection.CURRENT_STATE, "current")!!.content
            }
        )
    }

    @Test
    fun damagedPayloadIsRejectedInsteadOfLoadedPartially() {
        val root = temporaryFolder.newFolder("state")
        AndroidFileAgentStateVault(root, FixedAgentClock(100L)).transaction {
            writeDocument(
                AgentStateDocumentWrite(
                    "state",
                    AgentStateCollection.CURRENT_STATE,
                    "State",
                    "ok",
                    "host:test"
                )
            )
        }
        val file = java.io.File(root, "state-vault.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertTrue(
            runCatching {
                AndroidFileAgentStateVault(root, FixedAgentClock(200L))
            }.isFailure
        )
    }

    @Test
    fun retentionAndExplicitDeleteAreAtomicAndSurviveReopen() {
        val root = temporaryFolder.newFolder("maintenance")
        val store = AndroidFileAgentStateVault(root, FixedAgentClock(1_000L))
        store.transaction {
            (1L..3L).forEach { index ->
                appendEvent(
                    AgentStateEvent(
                        id = "event-$index",
                        type = "test",
                        source = "host:test",
                        summary = "event $index",
                        createdAtEpochMillis = index
                    )
                )
            }
        }

        val retention = store.applyRetention(
            AgentStateRetentionPolicy(
                maxEvents = 1,
                maxBriefs = 0,
                maxPsycheObservations = 0,
                expiredEvidenceGraceMillis = 0
            )
        )
        assertEquals(2, retention.removedEvents)
        assertEquals(1, AndroidFileAgentStateVault(root).snapshot().events.size)

        val deletion = store.deleteAll()
        assertEquals(1, deletion.deletedRecords)
        assertTrue(deletion.completed)
        assertEquals(0, AndroidFileAgentStateVault(root).snapshot().recordCount())
    }
}
