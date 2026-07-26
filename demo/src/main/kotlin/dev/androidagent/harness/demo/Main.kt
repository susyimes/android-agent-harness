// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
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

/**
 * Demo entrypoint with four subcommands:
 *
 * - `scenarios` runs five deterministic controlled-context-plane scenarios.
 * - `live` runs one turn against a real OpenAI-compatible endpoint (env-configured).
 * - `eval` runs the governed-evolution baseline-vs-candidate comparison.
 * - `phone` runs the fake payment flow on the device loop with a high-risk pause.
 *
 * Any other invocation (including no arguments) keeps the original behavior:
 * all arguments are joined into the input of the scripted uppercase demo.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "scenarios" -> runScenarios()
        "live" -> runLive(args.drop(1))
        "eval" -> runEvalDemo()
        "phone" -> runPhoneDemo()
        else -> runScriptedDemo(args)
    }
}

private fun runScriptedDemo(args: Array<String>) {
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

    printTurnSummary(result)
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
