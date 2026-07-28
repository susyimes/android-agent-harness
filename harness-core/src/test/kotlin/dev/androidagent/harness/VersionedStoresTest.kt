// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedStoresTest {
    @Test
    fun staleSessionRevisionCannotOverwriteNewerCommit() {
        val store = InMemoryVersionedAgentSessionStore()
        val session = session("one")
        assertEquals(0L, store.saveVersioned(session, expectedRevision = null))

        val first = TransactionalAgentSessionStore(store, session.id)
        val second = TransactionalAgentSessionStore(store, session.id)
        first.load(session.id)
        second.load(session.id)
        first.save(session("first"))
        second.save(session("second"))

        assertTrue(first.commit())
        val failure = runCatching(second::commit).exceptionOrNull()

        assertTrue(failure is AgentSessionRevisionConflictException)
        assertEquals("first", store.load(session.id)?.messages?.last()?.content)
    }

    @Test
    fun checkpointCompareAndSetUsesRevision() {
        val store = InMemoryAgentRunCheckpointStore()
        val initial = AgentRunCheckpoint(
            runId = "run-1",
            sessionId = "session-1",
            revision = 0,
            status = AgentRunCheckpointStatus.ACTIVE,
            payloadJson = "{}",
            updatedAtEpochMillis = 1L
        )

        assertTrue(store.compareAndSet(initial, expectedRevision = null))
        assertFalse(
            store.compareAndSet(
                initial.copy(revision = 1, payloadJson = "{\"late\":true}"),
                expectedRevision = null
            )
        )
        assertEquals(0L, store.load("run-1")?.revision)
        assertTrue(store.delete("run-1"))
        assertNull(store.load("run-1"))
    }

    private fun session(content: String): AgentSession {
        return AgentSession(
            id = "session-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            messages = listOf(
                AgentMessage(
                    id = "message-$content",
                    sessionId = "session-1",
                    role = AgentRole.ASSISTANT,
                    content = content,
                    createdAtEpochMillis = 2L
                )
            )
        )
    }
}
