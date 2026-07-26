// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.eval.EvalCase
import dev.androidagent.harness.eval.EvalRunner
import dev.androidagent.harness.eval.MarkdownWorkspace

/**
 * Governed-evolution demo: a candidate overlay over a markdown workspace is
 * only promoted when fixed eval cases show at least one improvement and zero
 * regressions. The provider is scripted but reads the workspace, so changing
 * STYLE.md genuinely changes its behavior.
 */
fun runEvalDemo() {
    val baseline = MarkdownWorkspace(
        mapOf(
            "FACTS.md" to "# Facts\n\nThe assistant is named HarnessBot.\n",
            "STYLE.md" to "# Style\n\nAnswer in flowing, elaborate prose with pleasantries.\n",
            "TASK.md" to "# Task\n\nHelp the operator with small text chores.\n"
        )
    )
    val overlay = mapOf<String, String?>(
        "STYLE.md" to "# Style\n\nBe terse. Prefix status replies with the marker STATUS_OK.\n"
    )
    val cases = listOf(
        EvalCase(
            id = "greeting",
            userInput = "Please greet the operator",
            expectedOutputContains = listOf("Hello")
        ),
        EvalCase(
            id = "status-marker",
            userInput = "Report the chore status",
            expectedOutputContains = listOf("STATUS_OK")
        ),
        EvalCase(
            id = "bot-name",
            userInput = "What is the assistant named?",
            expectedOutputContains = listOf("HarnessBot")
        )
    )

    println("EVAL: governed evolution gate - a candidate overlay must earn promotion on fixed cases")
    println("BASELINE_FILES=${baseline.fileNames().joinToString()}")
    println("OVERLAY=STYLE.md rewritten to demand terse replies carrying a STATUS_OK marker")
    println()

    val runner = EvalRunner(
        providerFactory = { workspace -> StyleAwareScriptedProvider(workspace) }
    )
    val comparison = runner.compare(baseline, overlay, cases)
    println(comparison.renderReport())
    println()
    println(
        "RULE: a candidate is promoted only when it improves at least one case and regresses " +
            "none; a single regression vetoes promotion no matter how many cases improve."
    )
}

/**
 * Deterministic provider whose answers depend on the workspace content:
 * a terse STYLE.md switches status replies to the STATUS_OK marker format,
 * and the bot name is read from FACTS.md.
 */
private class StyleAwareScriptedProvider(
    private val workspace: MarkdownWorkspace
) : AgentProvider {
    override val id: String = "style-aware-scripted"

    override fun respond(request: AgentProviderRequest): AgentProviderResponse {
        val userInput = request.session.messages
            .last { message -> message.role == AgentRole.USER }
            .content
        val style = workspace.content("STYLE.md").orEmpty()
        val terse = style.contains("terse", ignoreCase = true)
        val output = when {
            userInput.contains("greet", ignoreCase = true) ->
                if (terse) {
                    "Hello."
                } else {
                    "Hello, dear operator, what a pleasure to be of service today."
                }
            userInput.contains("status", ignoreCase = true) ->
                if (terse) {
                    "STATUS_OK all chores complete."
                } else {
                    "It is my sincere pleasure to report, at generous length, that every " +
                        "chore appears to be complete."
                }
            userInput.contains("named", ignoreCase = true) ->
                if (workspace.content("FACTS.md").orEmpty().contains("HarnessBot")) {
                    "The assistant is named HarnessBot."
                } else {
                    "The assistant name is not on file."
                }
            else -> "There is no scripted answer for that input."
        }
        return AgentProviderResponse.FinalText(output)
    }
}
