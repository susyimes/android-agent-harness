// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessResult
import dev.androidagent.harness.AgentIdGenerator
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolRegistry
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.DeterministicAgentHarness
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.InMemoryAgentSessionStore
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.StaticAgentContextProvider
import java.util.Locale

object SampleAgent {
    fun run(input: String): AgentHarnessResult {
        val idGenerator: AgentIdGenerator = SequentialAgentIdGenerator("sample")
        val harness = DeterministicAgentHarness(
            provider = ScriptedSampleProvider(),
            contextProvider = StaticAgentContextProvider(
                listOf(
                    AgentContextItem(
                        id = "sample-scope",
                        source = "bundled-public-sample",
                        content = "Transform only the text entered in this sample screen.",
                        trust = AgentContextTrust.APPLICATION
                    )
                )
            ),
            toolRegistry = AgentToolRegistry(listOf(UppercaseTool())),
            sessionStore = InMemoryAgentSessionStore(),
            clock = FixedAgentClock(1_700_000_000_000L),
            idGenerator = idGenerator
        )
        return harness.run(AgentHarnessRequest("sample-session", input))
    }

    private class ScriptedSampleProvider : AgentProvider {
        override val id: String = "local-scripted-sample"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            val toolResult = request.session.messages.lastOrNull { message ->
                message.role == AgentRole.TOOL && message.toolName == "uppercase"
            }
            if (toolResult != null) {
                return AgentProviderResponse.FinalText("Harness result: ${toolResult.content}")
            }
            val userInput = request.session.messages.last { message -> message.role == AgentRole.USER }.content
            return AgentProviderResponse.ToolRequests(
                listOf(
                    AgentToolCall(
                        id = "sample-uppercase-1",
                        toolName = "uppercase",
                        arguments = mapOf("text" to userInput)
                    )
                )
            )
        }
    }

    private class UppercaseTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "uppercase",
            description = "Converts the provided sample text to uppercase.",
            requiredArguments = setOf("text")
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            return AgentToolResult.success(
                invocation.arguments.getValue("text").uppercase(Locale.ROOT)
            )
        }
    }
}

