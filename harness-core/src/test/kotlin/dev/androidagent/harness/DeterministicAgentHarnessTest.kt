// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeterministicAgentHarnessTest {

    @Test
    fun runsProviderContextToolAndSessionContractsDeterministically() {
        val provider = UppercaseProvider()
        val store = InMemoryAgentSessionStore()
        val harness = DeterministicAgentHarness(
            provider = provider,
            contextProvider = StaticAgentContextProvider(
                listOf(
                    AgentContextItem("z-context", "test", "last", AgentContextTrust.APPLICATION),
                    AgentContextItem("a-context", "test", "first", AgentContextTrust.USER)
                )
            ),
            toolRegistry = AgentToolRegistry(listOf(UppercaseTool())),
            sessionStore = store,
            clock = FixedAgentClock(1_700_000_000_000L),
            idGenerator = SequentialAgentIdGenerator("test")
        )

        val result = harness.run(AgentHarnessRequest("session-1", "android"))

        assertEquals("Harness result: ANDROID", result.output)
        assertEquals(2, result.providerSteps)
        assertEquals(listOf("a-context", "z-context"), provider.contextIdsSeen)
        assertEquals(listOf("uppercase"), provider.toolNamesSeen)
        assertEquals(
            listOf(AgentRole.USER, AgentRole.TOOL, AgentRole.ASSISTANT),
            result.session.messages.map { message -> message.role }
        )
        assertEquals(
            listOf("test-message-0001", "test-message-0002", "test-message-0003"),
            result.session.messages.map { message -> message.id }
        )
        assertEquals(result.session, store.load("session-1"))
        assertEquals(
            listOf(
                AgentHarnessTraceEvent.ContextLoaded(
                    itemIds = listOf("a-context", "z-context"),
                    totalContentChars = 9
                ),
                AgentHarnessTraceEvent.ProviderInvoked(1, "scripted-uppercase", listOf("uppercase")),
                AgentHarnessTraceEvent.ProviderCompleted(1, "tool_requests"),
                AgentHarnessTraceEvent.ToolRequested(
                    1,
                    "uppercase-1",
                    "uppercase",
                    mapOf("text" to "android")
                ),
                AgentHarnessTraceEvent.ToolExecuted(
                    1,
                    "uppercase-1",
                    "uppercase",
                    true,
                    "ANDROID",
                    mapOf("text" to "android"),
                    AgentToolResultEnvelope(
                        status = AgentToolResultStatus.SUCCESS,
                        summary = "ANDROID",
                        createdAtEpochMillis = 1_700_000_000_000L
                    )
                ),
                AgentHarnessTraceEvent.ProviderInvoked(2, "scripted-uppercase", listOf("uppercase")),
                AgentHarnessTraceEvent.ProviderCompleted(2, "final_text"),
                AgentHarnessTraceEvent.Completed(2, "Harness result: ANDROID")
            ),
            result.trace
        )
    }

    @Test
    fun rejectsAProviderThatNeverFinishesAtTheConfiguredBoundary() {
        val harness = DeterministicAgentHarness(
            provider = EndlessToolProvider(),
            contextProvider = EmptyAgentContextProvider,
            toolRegistry = AgentToolRegistry(listOf(UppercaseTool())),
            sessionStore = InMemoryAgentSessionStore(),
            clock = FixedAgentClock(0L),
            idGenerator = SequentialAgentIdGenerator("limit"),
            config = AgentHarnessConfig(maxProviderSteps = 2, maxToolCallsPerStep = 1)
        )

        val error = assertThrows(AgentHarnessLimitException::class.java) {
            harness.run(AgentHarnessRequest("session-limit", "loop"))
        }

        assertEquals(
            "Provider 'endless-tools' did not finish within 2 steps.",
            error.message
        )
    }

    @Test
    fun rejectsDuplicateToolNamesAtConstruction() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentToolRegistry(listOf(UppercaseTool(), UppercaseTool()))
        }

        assertEquals("Duplicate tool names: uppercase.", error.message)
    }

    @Test
    fun streamsTraceEventsToObserverWithoutLosingFinalTrace() {
        val observed = mutableListOf<AgentHarnessTraceEvent>()
        val runner = AgentHarnessRunner(
            provider = UppercaseProvider(),
            tools = listOf(UppercaseTool()),
            clock = FixedAgentClock(0L),
            idGenerator = SequentialAgentIdGenerator("observer"),
            observer = AgentHarnessObserver(observed::add)
        )

        val result = runner.run(AgentHarnessRequest("observer-session", "sdk"))

        assertEquals(result.trace, observed)
        val toolEvent = observed.filterIsInstance<AgentHarnessTraceEvent.ToolExecuted>().single()
        assertEquals(mapOf("text" to "sdk"), toolEvent.arguments)
        assertEquals(AgentToolResultStatus.SUCCESS, toolEvent.envelope?.status)
    }

    private class UppercaseProvider : AgentProvider {
        override val id: String = "scripted-uppercase"
        var contextIdsSeen: List<String> = emptyList()
        var toolNamesSeen: List<String> = emptyList()

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            contextIdsSeen = request.context.map { item -> item.id }
            toolNamesSeen = request.tools.map { tool -> tool.name }
            val toolResult = request.session.messages.lastOrNull { message ->
                message.role == AgentRole.TOOL && message.toolName == "uppercase"
            }
            if (toolResult != null) {
                return AgentProviderResponse.FinalText("Harness result: ${toolResult.content}")
            }
            val userInput = request.session.messages.last { message -> message.role == AgentRole.USER }.content
            return AgentProviderResponse.ToolRequests(
                listOf(AgentToolCall("uppercase-1", "uppercase", mapOf("text" to userInput)))
            )
        }
    }

    private class EndlessToolProvider : AgentProvider {
        override val id: String = "endless-tools"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            return AgentProviderResponse.ToolRequests(
                listOf(
                    AgentToolCall(
                        id = "call-${request.providerStep}",
                        toolName = "uppercase",
                        arguments = mapOf("text" to "loop")
                    )
                )
            )
        }
    }

    private class UppercaseTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "uppercase",
            description = "Converts public sample text to uppercase.",
            requiredArguments = setOf("text")
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            return AgentToolResult.success(
                invocation.arguments.getValue("text").uppercase(Locale.ROOT)
            )
        }
    }
}
