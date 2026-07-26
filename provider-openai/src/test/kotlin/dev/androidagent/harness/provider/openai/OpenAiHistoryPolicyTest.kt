// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [OpenAiCompatibleConfig.historyCharBudget]: a phone-mode turn appends
 * a full screen rendering per step, so an untrimmed prompt grows quadratically
 * over the turn. Trimming must bound it without breaking the OpenAI rule that
 * every `tool` message follows an assistant message declaring its id.
 */
class OpenAiHistoryPolicyTest {

    private class RecordingTransport(private val response: String) : HttpTransport {
        val bodies = mutableListOf<String>()

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            bodies += body
            return response
        }
    }

    @Test
    fun keepsEveryMessageWhenNoBudgetIsConfigured() {
        val messages = renderedMessages(mixedSession(), budget = null)

        assertEquals(
            listOf(
                "system", "user", "assistant", "tool", "tool",
                "assistant", "user", "assistant", "tool", "tool"
            ),
            messages.map { message -> message["role"] }
        )
        assertEquals(SCREEN_A, messages[3]["content"])
        assertEquals(ACT_RESULT, messages[4]["content"])
        assertEquals(INTERIM_ASSISTANT, messages[5]["content"])
        assertEquals(SCREEN_B, messages[8]["content"])
        assertEquals(SCREEN_C, messages[9]["content"])
        assertValidToolProtocol(messages)
    }

    @Test
    fun keepsEveryMessageWhenTheBudgetIsGenerous() {
        val generous = mixedSession().messages.sumOf { message -> message.content.length } + 1

        assertEquals(
            renderedMessages(mixedSession(), budget = null),
            renderedMessages(mixedSession(), budget = generous)
        )
    }

    @Test
    fun preservesFirstUserAndNewestToolResultAndOmitsOlderToolResults() {
        val budget = FIRST_USER.length + SCREEN_C.length + 30

        val messages = renderedMessages(mixedSession(), budget = budget)

        // The two dropped conversational messages merged the two tool groups
        // into one, so a single synthetic assistant now covers all four ids.
        assertEquals(
            listOf("system", "user", "assistant", "tool", "tool", "tool", "tool"),
            messages.map { message -> message["role"] }
        )
        assertEquals(FIRST_USER, messages[1]["content"])
        assertEquals(OpenAiCompatibleProvider.OMITTED_TOOL_RESULT, messages[3]["content"])
        assertEquals(OpenAiCompatibleProvider.OMITTED_TOOL_RESULT, messages[4]["content"])
        assertEquals(OpenAiCompatibleProvider.OMITTED_TOOL_RESULT, messages[5]["content"])
        assertEquals(SCREEN_C, messages[6]["content"])
        assertEquals(listOf("call-1", "call-2", "call-3", "call-4"), toolCallIds(messages[2]))
        assertValidToolProtocol(messages)
    }

    @Test
    fun droppedMessagesLeaveNoTraceInTheRequestBody() {
        val body = requestBody(mixedSession(), budget = FIRST_USER.length + SCREEN_C.length + 30)

        assertFalse("Older screens must be gone: $body", body.contains(SCREEN_A))
        assertFalse("Older screens must be gone: $body", body.contains(SCREEN_B))
        assertFalse(body.contains(SECOND_USER))
        assertFalse(body.contains(INTERIM_ASSISTANT))
        assertTrue(body.contains(SCREEN_C))
        assertTrue(body.contains(FIRST_USER))
    }

    @Test
    fun trimmedHistoryStaysProtocolValidForEveryBudget() {
        val session = mixedSession()
        val widest = session.messages.sumOf { message -> message.content.length } + 5

        (1..widest).forEach { budget ->
            val messages = renderedMessages(session, budget = budget)
            assertValidToolProtocol(messages)
            assertEquals(
                "The first user message must survive budget=$budget",
                FIRST_USER,
                messages.first { message -> message["role"] == "user" }["content"]
            )
            assertEquals(
                "The newest tool result must survive budget=$budget in full",
                SCREEN_C,
                messages.last()["content"]
            )
        }
    }

    @Test
    fun boundsALongPhoneModeTurnThatWouldOtherwiseGrowQuadratically() {
        val steps = 50
        val session = phoneModeSession(steps)
        val budget = 2_000

        val trimmed = requestBody(session, budget = budget)
        val untrimmed = requestBody(session, budget = null)

        val contentChars = renderedMessages(session, budget = budget)
            .drop(1)
            .sumOf { message -> (message["content"] as? String)?.length ?: 0 }
        val placeholderAllowance =
            OpenAiCompatibleProvider.OMITTED_TOOL_RESULT.length * steps
        assertTrue(
            "Content chars $contentChars must stay inside the budget plus placeholders.",
            contentChars <= budget + placeholderAllowance
        )
        assertTrue(
            "Untrimmed body of $steps steps should be large, was ${untrimmed.length}.",
            untrimmed.length > 50_000
        )
        assertTrue(
            "Trimmed body ${trimmed.length} should be a fraction of ${untrimmed.length}.",
            trimmed.length < untrimmed.length / 3
        )
        assertValidToolProtocol(renderedMessages(session, budget = budget))
    }

    /**
     * Enforces the OpenAI pairing rule: every `tool` message must follow an
     * assistant message whose `tool_calls` declare its `tool_call_id`, and
     * every declared id must be answered by exactly one following `tool`
     * message before the run of tool messages ends.
     */
    private fun assertValidToolProtocol(messages: List<Map<String, Any?>>) {
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message["role"] == "tool") {
                throw AssertionError(
                    "Tool message at index $index has no preceding assistant tool_calls."
                )
            }
            val declared = if (message["role"] == "assistant") toolCallIds(message) else emptyList()
            index++
            if (declared.isEmpty()) {
                assertFalse(
                    "Assistant without tool_calls at ${index - 1} is followed by a tool message.",
                    index < messages.size && messages[index]["role"] == "tool"
                )
                continue
            }
            val answered = mutableListOf<String>()
            while (index < messages.size && messages[index]["role"] == "tool") {
                answered += messages[index]["tool_call_id"] as String
                index++
            }
            assertEquals(
                "Every declared tool_call id must be answered exactly once, in order.",
                declared,
                answered
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toolCallIds(assistant: Map<String, Any?>): List<String> {
        val toolCalls = assistant["tool_calls"] as? List<Any?> ?: return emptyList()
        return toolCalls.map { call -> (call as Map<String, Any?>)["id"] as String }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderedMessages(session: AgentSession, budget: Int?): List<Map<String, Any?>> {
        val body = MinimalJson.parse(requestBody(session, budget)) as Map<String, Any?>
        return (body["messages"] as List<Any?>).map { message -> message as Map<String, Any?> }
    }

    private fun requestBody(session: AgentSession, budget: Int?): String {
        val transport = RecordingTransport(FINAL_RESPONSE)
        OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                historyCharBudget = budget
            ),
            transport = transport
        ).respond(
            AgentProviderRequest(
                session = session,
                context = emptyList(),
                tools = emptyList(),
                providerStep = 1
            )
        ).let { response -> check(response is AgentProviderResponse.FinalText) }
        return transport.bodies.single()
    }

    /** user -> 2 tool results -> assistant -> user -> 2 tool results. */
    private fun mixedSession(): AgentSession {
        return session(
            user(FIRST_USER),
            tool("call-1", "device_observe", SCREEN_A),
            tool("call-2", "device_act", ACT_RESULT),
            assistant(INTERIM_ASSISTANT),
            user(SECOND_USER),
            tool("call-3", "device_observe", SCREEN_B),
            tool("call-4", "device_observe", SCREEN_C)
        )
    }

    /** One task statement followed by [steps] full screen renderings. */
    private fun phoneModeSession(steps: Int): AgentSession {
        val messages = mutableListOf(user(FIRST_USER))
        (1..steps).forEach { step ->
            messages += tool(
                callId = "call-$step",
                toolName = "device_observe",
                content = "screen=step$step " + "node ".repeat(240)
            )
        }
        return session(*messages.toTypedArray())
    }

    private fun session(vararg messages: AgentMessage): AgentSession {
        return AgentSession(
            id = SESSION_ID,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            messages = messages.toList()
        )
    }

    private fun user(content: String): AgentMessage = message(AgentRole.USER, content)

    private fun assistant(content: String): AgentMessage = message(AgentRole.ASSISTANT, content)

    private fun tool(callId: String, toolName: String, content: String): AgentMessage {
        return AgentMessage(
            id = "message-${nextMessageId++}",
            sessionId = SESSION_ID,
            role = AgentRole.TOOL,
            content = content,
            createdAtEpochMillis = 1L,
            toolCallId = callId,
            toolName = toolName
        )
    }

    private fun message(role: AgentRole, content: String): AgentMessage {
        return AgentMessage(
            id = "message-${nextMessageId++}",
            sessionId = SESSION_ID,
            role = role,
            content = content,
            createdAtEpochMillis = 1L
        )
    }

    private var nextMessageId = 1

    private companion object {
        const val SESSION_ID = "history-policy-session"
        const val FIRST_USER = "TASK: pay for my order, never without approval"
        const val SECOND_USER = "now open the receipt screen"
        const val INTERIM_ASSISTANT = "Paid 12.50 after approval."
        const val ACT_RESULT = "OK: tapped pay_button"
        val SCREEN_A: String = "screen=checkout " + "alpha ".repeat(40)
        val SCREEN_B: String = "screen=receipt " + "bravo ".repeat(40)
        val SCREEN_C: String = "screen=orders " + "charlie ".repeat(40)

        val FINAL_RESPONSE: String = MinimalJson.encode(
            mapOf(
                "choices" to listOf(
                    mapOf("message" to mapOf("role" to "assistant", "content" to "done"))
                )
            )
        )
    }
}
