// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import dev.androidagent.harness.AgentHarnessResult
import dev.androidagent.harness.AgentHarnessTraceEvent

internal fun AgentHarnessTraceEvent.label(): String {
    return when (this) {
        is AgentHarnessTraceEvent.ContextLoaded -> "ContextLoaded"
        is AgentHarnessTraceEvent.ProviderInvoked -> "ProviderInvoked($step)"
        is AgentHarnessTraceEvent.ToolLoopActivated -> "ToolLoopActivated($toolName)"
        is AgentHarnessTraceEvent.ToolExecuted -> "ToolExecuted($toolName)"
        is AgentHarnessTraceEvent.Completed -> "Completed($step)"
    }
}

/** Prints the canonical four-line turn summary shared by the scripted, live, and phone demos. */
internal fun printTurnSummary(result: AgentHarnessResult) {
    println("OUTPUT=${result.output}")
    println("PROVIDER_STEPS=${result.providerSteps}")
    println("TRACE=${result.trace.joinToString(" -> ") { event -> event.label() }}")
    println(
        "TRANSCRIPT=" + result.session.messages.joinToString(" | ") { message ->
            "${message.role}:${message.content}"
        }
    )
}
