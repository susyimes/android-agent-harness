// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionalAgentSessionStoreTest {

    @Test
    fun stagedHistoryIsInvisibleUntilCommit() {
        val delegate = InMemoryAgentSessionStore()
        val transaction = TransactionalAgentSessionStore(delegate, SESSION_ID)
        val session = session("first")

        transaction.save(session)

        assertNull(delegate.load(SESSION_ID))
        assertEquals(session.messages, transaction.load(SESSION_ID)?.messages)
        assertTrue(transaction.commit())
        assertEquals(session.messages, delegate.load(SESSION_ID)?.messages)
        assertFalse(transaction.commit())
    }

    @Test
    fun discardPreservesDelegateAndIgnoresLateSaves() {
        val delegate = InMemoryAgentSessionStore()
        val original = session("kept")
        delegate.save(original)
        val transaction = TransactionalAgentSessionStore(delegate, SESSION_ID)

        transaction.save(session("partial"))
        assertTrue(transaction.discard())
        transaction.save(session("late"))

        assertEquals(original.messages, delegate.load(SESSION_ID)?.messages)
        assertThrows(CancellationException::class.java) {
            transaction.load(SESSION_ID)
        }
        assertFalse(transaction.commit())
    }

    private fun session(content: String): AgentSession {
        return AgentSession(
            id = SESSION_ID,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            messages = listOf(
                AgentMessage(
                    id = "message-$content",
                    sessionId = SESSION_ID,
                    role = AgentRole.USER,
                    content = content,
                    createdAtEpochMillis = 2
                )
            )
        )
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
