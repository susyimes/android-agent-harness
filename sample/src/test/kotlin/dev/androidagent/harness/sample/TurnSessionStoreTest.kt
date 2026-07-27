// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.InMemoryAgentSessionStore
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSessionStoreTest {

    @Test
    fun `staged session stays private until commit`() {
        val delegate = InMemoryAgentSessionStore()
        val turn = TurnSessionStore(delegate, SESSION_ID)
        val session = session("first")

        turn.save(session)

        assertNull(delegate.load(SESSION_ID))
        assertEquals(session.messages, turn.load(SESSION_ID)?.messages)
        assertTrue(turn.commit())
        assertEquals(session.messages, delegate.load(SESSION_ID)?.messages)
        assertFalse(turn.commit())
    }

    @Test
    fun `discard drops staged history and ignores late saves`() {
        val delegate = InMemoryAgentSessionStore()
        val original = session("kept")
        delegate.save(original)
        val turn = TurnSessionStore(delegate, SESSION_ID)

        turn.save(session("partial"))
        assertTrue(turn.discard())
        turn.save(session("late"))

        assertEquals(original.messages, delegate.load(SESSION_ID)?.messages)
        assertThrows(CancellationException::class.java) {
            turn.load(SESSION_ID)
        }
        assertFalse(turn.commit())
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
        const val SESSION_ID = "chat-1"
    }
}
