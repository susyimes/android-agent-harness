// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.provider.openai.OpenAiCompatibleConfig
import dev.androidagent.harness.provider.openai.OpenAiCompatibleProvider
import java.time.Instant
import java.util.Locale

/**
 * Runs one bounded turn against a real OpenAI-compatible endpoint with three
 * locally implemented tools. Without a credential in the environment it prints
 * setup instructions and returns normally instead of failing.
 */
fun runLive(args: List<String>, env: (String) -> String? = System::getenv) {
    val config = OpenAiCompatibleConfig.fromEnvironment(env)
    if (config.keyValue == null) {
        println("LIVE_SKIPPED=OPENAI_API_KEY is not set")
        println("The live demo talks to a real OpenAI-compatible endpoint and reads these environment variables:")
        println("  OPENAI_API_KEY   (required) credential for the endpoint")
        println("  OPENAI_BASE_URL  (optional) defaults to ${OpenAiCompatibleConfig.DEFAULT_BASE_URL}")
        println("  OPENAI_MODEL     (optional) defaults to ${OpenAiCompatibleConfig.DEFAULT_MODEL}")
        println("DeepSeek example: set OPENAI_BASE_URL to https://api.deepseek.com and OPENAI_MODEL to deepseek-chat.")
        println("Nothing was sent over the network. Exiting normally.")
        return
    }

    val input = args.joinToString(" ").ifBlank {
        "Use the current_time tool to get the time, the word_count tool on the text " +
            "'the quick brown fox jumps', and the uppercase tool on the word 'harness'. " +
            "Then summarize all three results in one sentence."
    }
    println("LIVE: baseUrl=${config.baseUrl} model=${config.model}")
    println("LIVE: input=$input")

    val runner = AgentHarnessRunner(
        provider = OpenAiCompatibleProvider(config),
        tools = listOf(CurrentTimeTool(), WordCountTool(), LiveUppercaseTool()),
        toolProfile = AgentToolProfile.only(
            "live-demo",
            setOf("current_time", "word_count", "uppercase")
        ),
        config = AgentHarnessConfig(maxProviderSteps = 8, maxToolCallsPerStep = 4)
    )
    val result = runner.run(
        AgentHarnessRequest(sessionId = "live-session", userInput = input)
    )
    printTurnSummary(result)
}

private class CurrentTimeTool : AgentTool {
    override val spec = AgentToolSpec(
        name = "current_time",
        description = "Returns the current wall-clock time in UTC as an ISO-8601 instant."
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        return AgentToolResult.success(Instant.now().toString())
    }
}

private class WordCountTool : AgentTool {
    override val spec = AgentToolSpec(
        name = "word_count",
        description = "Counts the whitespace-separated words in the given text.",
        requiredArguments = setOf("text")
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val text = invocation.arguments.getValue("text")
        val count = text.split(WHITESPACE).count { token -> token.isNotBlank() }
        return AgentToolResult.success(count.toString())
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

private class LiveUppercaseTool : AgentTool {
    override val spec = AgentToolSpec(
        name = "uppercase",
        description = "Converts the given text to uppercase.",
        requiredArguments = setOf("text")
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        return AgentToolResult.success(
            invocation.arguments.getValue("text").uppercase(Locale.ROOT)
        )
    }
}
