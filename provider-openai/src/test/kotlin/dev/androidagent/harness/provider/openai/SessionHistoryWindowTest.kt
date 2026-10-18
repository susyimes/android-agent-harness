// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryWindowTest {
    @Test
    fun aNewTurnDoesNotPinThePreviousTasksScreen() {
        val messages = listOf(
            message(AgentRole.USER, "old goal"),
            message(AgentRole.TOOL, "old screen".repeat(100)),
            message(AgentRole.ASSISTANT, "old answer"),
            message(AgentRole.USER, "current goal")
        )
        val selected = sessionHistoryWindow(messages, 1)
        assertEquals("current goal", selected.last().content)
        assertFalse(selected.any { it.content.contains("old screen") })
        assertEquals("old screen".repeat(100), messages[1].content)
    }

    @Test
    fun bothWireFormatsPreserveCurrentTaskAndToolPairing() {
        val session = AgentSession("session", 0, 0, listOf(
            message(AgentRole.USER, "obsolete goal"),
            message(AgentRole.ASSISTANT, "obsolete answer"),
            message(AgentRole.USER, "current goal"),
            message(AgentRole.TOOL, "current screen")
        ))
        val request = AgentProviderRequest(session, emptyList(), emptyList(), 1)
        val bodies = mutableListOf<String>()
        val transport = HttpTransport { _, _, body ->
            bodies += body
            """{"choices":[{"message":{"content":"done"}}],"output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}"""
        }
        OpenAiCompatibleProvider(OpenAiCompatibleConfig(
            baseUrl = "https://example.invalid/v1", model = "test", historyCharBudget = 1
        ), transport).respond(request)
        CodexResponsesProvider(CodexResponsesConfig(
            baseUrl = "https://example.invalid/v1", model = "test", historyCharBudget = 1
        ), CodexCredentialProvider { CodexCredential("stub-credential") }, transport).respond(request)
        bodies.forEach { body ->
            assertTrue(body, body.contains("current goal"))
            assertTrue(body, body.contains("current screen"))
            assertTrue(body, body.contains("call-1"))
            assertFalse(body, body.contains("obsolete goal"))
        }
    }

    private fun message(role: AgentRole, content: String) = AgentMessage(
        id = "$role-$content", sessionId = "session", role = role, content = content,
        createdAtEpochMillis = 0,
        toolCallId = if (role == AgentRole.TOOL) "call-1" else null,
        toolName = if (role == AgentRole.TOOL) "read_page" else null
    )
}
