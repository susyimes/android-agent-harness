// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalArchitectureTest {

    @Test
    fun explicitArchitectureLayersRunOneEndToEndTurn() {
        val provider = ScriptedUppercaseProvider()
        val store = InMemoryAgentSessionStore()
        val contextCoordinator = AgentContextCoordinator(
            StaticAgentContextProvider(
                listOf(
                    AgentContextItem(
                        id = "run-scope",
                        source = "test",
                        content = "Transform only the current public input.",
                        trust = AgentContextTrust.APPLICATION,
                        priority = 100
                    )
                )
            )
        )
        val toolOrchestrator = AgentToolOrchestrator(
            registry = AgentToolRegistry(listOf(UppercaseTool(), ReverseTool())),
            profile = AgentToolProfile.only("minimal-test", setOf("uppercase"))
        )
        val orchestrator = AgentOrchestrator(
            provider = provider,
            contextCoordinator = contextCoordinator,
            toolOrchestrator = toolOrchestrator,
            sessionStore = store,
            clock = FixedAgentClock(1_700_000_000_000L),
            idGenerator = SequentialAgentIdGenerator("architecture")
        )

        val result = AgentHarnessRunner(orchestrator).run(
            AgentHarnessRequest(sessionId = "architecture-session", userInput = "android")
        )

        assertEquals("Harness result: ANDROID", result.output)
        assertEquals(2, result.providerSteps)
        assertEquals(listOf("run-scope"), provider.contextIdsSeen)
        assertEquals(listOf("uppercase"), provider.toolNamesSeen)
        assertEquals(
            listOf(AgentRole.USER, AgentRole.TOOL, AgentRole.ASSISTANT),
            result.session.messages.map { message -> message.role }
        )
        assertEquals(result.session, store.load("architecture-session"))
    }

    @Test
    fun contextCoordinatorAppliesTrustPriorityAndBudgets() {
        val coordinator = AgentContextCoordinator(
            StaticAgentContextProvider(
                listOf(
                    AgentContextItem("low", "test", "abcde", AgentContextTrust.USER, priority = 10),
                    AgentContextItem("high", "test", "1234", AgentContextTrust.APPLICATION, priority = 20),
                    AgentContextItem("external", "test", "external", AgentContextTrust.EXTERNAL, priority = 30)
                )
            ),
            AgentContextPolicy(
                maxItems = 2,
                maxContentChars = 8,
                allowedTrust = setOf(AgentContextTrust.APPLICATION, AgentContextTrust.USER)
            )
        )

        val bundle = coordinator.build(
            AgentContextRequest(
                session = AgentSession("context-session", 0L, 0L),
                userInput = "run"
            )
        )

        assertEquals(listOf("high"), bundle.items.map { item -> item.id })
        assertEquals(listOf("external", "low"), bundle.droppedItemIds)
        assertEquals(4, bundle.totalContentChars)
    }

    @Test
    fun toolProfileIsBothCatalogAndExecutionBoundary() {
        val orchestrator = AgentToolOrchestrator(
            registry = AgentToolRegistry(listOf(UppercaseTool(), ReverseTool())),
            profile = AgentToolProfile.only("uppercase-only", setOf("uppercase"))
        )

        assertEquals(listOf("uppercase"), orchestrator.specifications().map { spec -> spec.name })

        val allowed = orchestrator.execute(
            listOf(AgentToolCall("allowed", "uppercase", mapOf("text" to "android"))),
            sessionId = "tool-session"
        ).single().result
        val blocked = orchestrator.execute(
            listOf(AgentToolCall("blocked", "reverse", mapOf("text" to "android"))),
            sessionId = "tool-session"
        ).single().result

        assertFalse(allowed.isError)
        assertEquals("ANDROID", allowed.content)
        assertTrue(blocked.isError)
        assertEquals("Tool 'reverse' is not available in profile 'uppercase-only'.", blocked.content)
    }

    private class ScriptedUppercaseProvider : AgentProvider {
        override val id: String = "minimal-scripted-provider"
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
            val input = request.session.messages.last { message -> message.role == AgentRole.USER }.content
            return AgentProviderResponse.ToolRequests(
                listOf(AgentToolCall("uppercase-1", "uppercase", mapOf("text" to input)))
            )
        }
    }

    private class UppercaseTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "uppercase",
            description = "Converts public test text to uppercase.",
            requiredArguments = setOf("text")
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            return AgentToolResult.success(
                invocation.arguments.getValue("text").uppercase(Locale.ROOT)
            )
        }
    }

    private class ReverseTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "reverse",
            description = "Reverses public test text.",
            requiredArguments = setOf("text")
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            return AgentToolResult.success(invocation.arguments.getValue("text").reversed())
        }
    }
}
