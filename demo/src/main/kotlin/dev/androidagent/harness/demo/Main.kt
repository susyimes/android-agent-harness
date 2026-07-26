// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentHarnessTraceEvent
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.StaticAgentContextProvider
import java.util.Locale

fun main(args: Array<String>) {
    val input = args.joinToString(" ").ifBlank { "android" }
    val runner = AgentHarnessRunner(
        provider = ScriptedDemoProvider(),
        contextProviders = listOf(
            StaticAgentContextProvider(
                listOf(
                    AgentContextItem(
                        id = "demo-scope",
                        source = "public-demo",
                        content = "Transform only the text supplied to this local demo.",
                        trust = AgentContextTrust.APPLICATION,
                        priority = 100
                    )
                )
            )
        ),
        tools = listOf(UppercaseTool()),
        clock = FixedAgentClock(1_700_000_000_000L),
        idGenerator = SequentialAgentIdGenerator("demo"),
        toolProfile = AgentToolProfile.only("minimal-demo", setOf("uppercase"))
    )

    val result = runner.run(
        AgentHarnessRequest(sessionId = "demo-session", userInput = input)
    )

    println("OUTPUT=${result.output}")
    println("PROVIDER_STEPS=${result.providerSteps}")
    println("TRACE=${result.trace.joinToString(" -> ") { event -> event.label() }}")
    println(
        "TRANSCRIPT=" + result.session.messages.joinToString(" | ") { message ->
            "${message.role}:${message.content}"
        }
    )
}

private fun AgentHarnessTraceEvent.label(): String {
    return when (this) {
        is AgentHarnessTraceEvent.ContextLoaded -> "ContextLoaded"
        is AgentHarnessTraceEvent.ProviderInvoked -> "ProviderInvoked($step)"
        is AgentHarnessTraceEvent.ToolExecuted -> "ToolExecuted($toolName)"
        is AgentHarnessTraceEvent.Completed -> "Completed($step)"
    }
}

private class ScriptedDemoProvider : AgentProvider {
    override val id: String = "local-scripted-demo"

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
                    id = "demo-uppercase-1",
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
        description = "Converts public demo text to uppercase.",
        requiredArguments = setOf("text")
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        return AgentToolResult.success(
            invocation.arguments.getValue("text").uppercase(Locale.ROOT)
        )
    }
}
