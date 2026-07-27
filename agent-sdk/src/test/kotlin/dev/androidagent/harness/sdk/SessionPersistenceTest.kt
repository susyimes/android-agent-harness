// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionPersistenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripsUnicodeAndToolMessagesAndBuildsCatalog() {
        val store = FileAgentSessionStore(temporaryFolder.newFolder("sessions"))
        val first = session(
            id = "会话/../one",
            updatedAt = 20,
            messages = listOf(
                message("u1", "会话/../one", AgentRole.USER, "帮我整理今天的计划", 10),
                AgentMessage(
                    id = "t1",
                    sessionId = "会话/../one",
                    role = AgentRole.TOOL,
                    content = "工具结果：完成",
                    createdAtEpochMillis = 11,
                    toolCallId = "call-1",
                    toolName = "todo"
                )
            )
        )
        val second = session(
            id = "two",
            updatedAt = 30,
            messages = listOf(message("u2", "two", AgentRole.USER, "第二个会话", 30))
        )

        store.save(first)
        store.save(second)

        assertEquals(first, store.load(first.id))
        assertEquals(listOf("two", first.id), store.listSessions().map { it.id })
        assertEquals("帮我整理今天的计划", store.listSessions()[1].title)
        assertEquals(2, temporaryFolder.root.walkTopDown().count { it.name.endsWith(".agent-session") })
        assertFalse(File(temporaryFolder.root, "one").exists())
    }

    @Test
    fun corruptFileDoesNotHideHealthySessions() {
        val directory = temporaryFolder.newFolder("sessions")
        val store = FileAgentSessionStore(directory)
        store.save(session("healthy", 1, emptyList()))
        File(directory, "corrupt.agent-session").writeText("not a session")

        assertEquals(listOf("healthy"), store.listSessions().map { it.id })
        assertNull(store.load("missing"))
        assertEquals(2, store.clearSessions())
        assertTrue(store.listSessions().isEmpty())
    }

    @Test
    fun deleteTargetsOnlyTheHashedSessionFile() {
        val store = FileAgentSessionStore(temporaryFolder.newFolder("sessions"))
        store.save(session("a", 1, emptyList()))
        store.save(session("b", 2, emptyList()))

        assertTrue(store.deleteSession("a"))
        assertFalse(store.deleteSession("a"))
        assertNull(store.load("a"))
        assertEquals(listOf("b"), store.listSessions().map { it.id })
    }

    private fun session(
        id: String,
        updatedAt: Long,
        messages: List<AgentMessage>
    ): AgentSession {
        return AgentSession(
            id = id,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = updatedAt,
            messages = messages
        )
    }

    private fun message(
        id: String,
        sessionId: String,
        role: AgentRole,
        content: String,
        createdAt: Long
    ): AgentMessage {
        return AgentMessage(
            id = id,
            sessionId = sessionId,
            role = role,
            content = content,
            createdAtEpochMillis = createdAt
        )
    }
}
