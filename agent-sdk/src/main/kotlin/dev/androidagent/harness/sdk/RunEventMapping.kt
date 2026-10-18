// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentHarnessTraceEvent
import dev.androidagent.harness.AgentProviderDisplayEvent
import dev.androidagent.harness.AgentToolResultEnvelope

internal fun AgentRunEvent.toStableEvent(policy: AgentRunPolicy, state: AgentRunState): AgentEvent? {
    val now = System.currentTimeMillis()
    val event = this
    return when (event) {
        is AgentRunEvent.Started -> AgentEvent.RunStarted(
            runId = event.runId,
            sessionId = event.sessionId,
            occurredAtEpochMillis = now,
            trigger = policy.trigger,
            providerId = event.providerId,
            budget = policy.budget
        )

        is AgentRunEvent.Trace -> when (val trace = event.event) {
            is AgentHarnessTraceEvent.ContextLoaded -> null

            is AgentHarnessTraceEvent.ProviderInvoked -> AgentEvent.ProviderStarted(
                runId = event.runId,
                sessionId = event.sessionId,
                occurredAtEpochMillis = now,
                step = trace.step,
                providerId = trace.providerId,
                toolNames = trace.toolNames
            )

            is AgentHarnessTraceEvent.ProviderCompleted -> AgentEvent.ProviderCompleted(
                runId = event.runId,
                sessionId = event.sessionId,
                occurredAtEpochMillis = now,
                step = trace.step,
                responseKind = trace.responseKind
            )

            is AgentHarnessTraceEvent.ProviderDisplay -> when (val display = trace.event) {
                is AgentProviderDisplayEvent.TextDelta -> AgentEvent.ProviderDelta(
                    runId = event.runId,
                    sessionId = event.sessionId,
                    occurredAtEpochMillis = now,
                    step = trace.step,
                    text = display.text
                )

                is AgentProviderDisplayEvent.ActionNarration ->
                    AgentEvent.ProviderDisplay(
                        runId = event.runId,
                        sessionId = event.sessionId,
                        occurredAtEpochMillis = now,
                        step = trace.step,
                        kind = "action_narration",
                        text = display.text
                    )

                is AgentProviderDisplayEvent.ToolStatus ->
                    AgentEvent.ProviderDisplay(
                        runId = event.runId,
                        sessionId = event.sessionId,
                        occurredAtEpochMillis = now,
                        step = trace.step,
                        kind = "tool_status",
                        text = display.status,
                        toolName = display.toolName
                    )

                is AgentProviderDisplayEvent.Usage ->
                    AgentEvent.ProviderDisplay(
                        runId = event.runId,
                        sessionId = event.sessionId,
                        occurredAtEpochMillis = now,
                        step = trace.step,
                        kind = "usage",
                        inputTokens = display.inputTokens,
                        outputTokens = display.outputTokens
                    )
            }

            is AgentHarnessTraceEvent.ToolRequested -> AgentEvent.ToolRequested(
                runId = event.runId,
                sessionId = event.sessionId,
                occurredAtEpochMillis = now,
                step = trace.step,
                callId = trace.callId,
                toolName = trace.toolName,
                argumentNames = trace.arguments.keys
            )

            is AgentHarnessTraceEvent.ToolExecuted -> AgentEvent.ToolCompleted(
                runId = event.runId,
                sessionId = event.sessionId,
                occurredAtEpochMillis = now,
                step = trace.step,
                callId = trace.callId,
                toolName = trace.toolName,
                envelope = trace.envelope ?: AgentToolResultEnvelope(
                    status = if (trace.succeeded) {
                        dev.androidagent.harness.AgentToolResultStatus.SUCCESS
                    } else {
                        dev.androidagent.harness.AgentToolResultStatus.FAILURE
                    },
                    summary = trace.content.ifBlank { "Tool returned no summary." },
                    createdAtEpochMillis = now
                )
            )

            is AgentHarnessTraceEvent.ToolLoopActivated -> AgentEvent.DeviceLoopActivated(
                runId = event.runId,
                sessionId = event.sessionId,
                occurredAtEpochMillis = now,
                step = trace.step,
                toolName = trace.toolName,
                maxProviderSteps = trace.maxProviderSteps
            )

            is AgentHarnessTraceEvent.Completed -> null
        }

        is AgentRunEvent.Finished -> AgentEvent.RunFinished(
            runId = event.runId,
            sessionId = event.sessionId,
            occurredAtEpochMillis = now,
            state = state,
            summary = when (val outcome = event.outcome) {
                is AgentRunOutcome.Success -> outcome.result.output
                is AgentRunOutcome.Failure -> outcome.error.message
                is AgentRunOutcome.Cancelled -> outcome.reason
                is AgentRunOutcome.Expired -> outcome.reason
            }
        )
    }
}
