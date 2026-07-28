// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

enum class AgentReplayIssueSeverity {
    INFO,
    WARNING,
    ERROR
}

data class AgentReplayIssue(
    val runId: String,
    val code: String,
    val severity: AgentReplayIssueSeverity,
    val summary: String
)

data class AgentReplayRunSummary(
    val runId: String,
    val sessionId: String,
    val terminalState: AgentRunState?,
    val eventCount: Int,
    val providerSteps: Int,
    val toolCalls: Int,
    val approvalRequests: Int,
    val complete: Boolean
)

data class AgentReplayReport(
    val totalEvents: Int,
    val runs: List<AgentReplayRunSummary>,
    val issues: List<AgentReplayIssue>
) {
    val healthy: Boolean
        get() = issues.none { issue -> issue.severity == AgentReplayIssueSeverity.ERROR }

    val completeRunCount: Int
        get() = runs.count(AgentReplayRunSummary::complete)
}

/**
 * Deterministic, side-effect-free replay evaluator for exported stable events.
 *
 * It does not re-run a provider or tool. It verifies lifecycle, session,
 * provider-step, tool-call, approval, timestamp, and late-event invariants so a
 * host can inspect a trace without exposing hidden reasoning or raw payloads.
 */
class AgentTraceReplayEvaluator {
    fun evaluate(events: List<AgentEvent>): AgentReplayReport {
        val summaries = mutableListOf<AgentReplayRunSummary>()
        val issues = mutableListOf<AgentReplayIssue>()
        events.groupBy(AgentEvent::runId)
            .toSortedMap()
            .forEach { (runId, values) ->
                val sessions = values.map(AgentEvent::sessionId).distinct()
                if (sessions.size != 1) {
                    issues += issue(
                        runId,
                        "SESSION_MISMATCH",
                        AgentReplayIssueSeverity.ERROR,
                        "One run references ${sessions.size} session ids."
                    )
                }
                values.zipWithNext().forEach { (before, after) ->
                    if (after.occurredAtEpochMillis < before.occurredAtEpochMillis) {
                        issues += issue(
                            runId,
                            "TIME_REVERSED",
                            AgentReplayIssueSeverity.ERROR,
                            "Event time moved backwards."
                        )
                    }
                }
                val starts = values.filterIsInstance<AgentEvent.RunStarted>()
                val finishes = values.filterIsInstance<AgentEvent.RunFinished>()
                if (starts.size > 1) {
                    issues += issue(
                        runId,
                        "MULTIPLE_STARTS",
                        AgentReplayIssueSeverity.ERROR,
                        "Run has ${starts.size} start events."
                    )
                }
                if (finishes.size > 1) {
                    issues += issue(
                        runId,
                        "MULTIPLE_TERMINALS",
                        AgentReplayIssueSeverity.ERROR,
                        "Run has ${finishes.size} terminal events."
                    )
                }
                if (starts.isEmpty()) {
                    issues += issue(
                        runId,
                        "PARTIAL_TRACE_START",
                        AgentReplayIssueSeverity.WARNING,
                        "The bounded journal no longer contains this run's start event."
                    )
                }
                if (finishes.isEmpty()) {
                    issues += issue(
                        runId,
                        "INCOMPLETE_RUN",
                        AgentReplayIssueSeverity.WARNING,
                        "No terminal event is present."
                    )
                }
                val terminalIndex = values.indexOfFirst { event -> event is AgentEvent.RunFinished }
                if (terminalIndex >= 0 && terminalIndex != values.lastIndex) {
                    issues += issue(
                        runId,
                        "EVENT_AFTER_TERMINAL",
                        AgentReplayIssueSeverity.ERROR,
                        "${values.size - terminalIndex - 1} events arrived after the terminal fence."
                    )
                }

                val providerStarts = values.filterIsInstance<AgentEvent.ProviderStarted>()
                    .associateBy(AgentEvent.ProviderStarted::step)
                values.filterIsInstance<AgentEvent.ProviderCompleted>().forEach { completed ->
                    if (providerStarts[completed.step] == null) {
                        issues += issue(
                            runId,
                            "PROVIDER_COMPLETE_WITHOUT_START",
                            AgentReplayIssueSeverity.ERROR,
                            "Provider step ${completed.step} completed without a start."
                        )
                    }
                }
                val toolRequests = values.filterIsInstance<AgentEvent.ToolRequested>()
                    .associateBy(AgentEvent.ToolRequested::callId)
                values.filterIsInstance<AgentEvent.ToolCompleted>().forEach { completed ->
                    val requested = toolRequests[completed.callId]
                    if (requested == null || requested.toolName != completed.toolName) {
                        issues += issue(
                            runId,
                            "TOOL_COMPLETION_MISMATCH",
                            AgentReplayIssueSeverity.ERROR,
                            "Tool completion '${completed.callId}' has no matching request."
                        )
                    }
                }
                val approvals = values.filterIsInstance<AgentEvent.ApprovalRequested>()
                    .associateBy(AgentEvent.ApprovalRequested::approvalId)
                values.filterIsInstance<AgentEvent.ApprovalResolved>().forEach { resolved ->
                    if (approvals[resolved.approvalId] == null) {
                        issues += issue(
                            runId,
                            "APPROVAL_RESOLUTION_MISMATCH",
                            AgentReplayIssueSeverity.ERROR,
                            "Approval '${resolved.approvalId}' resolved without a request."
                        )
                    }
                }
                val terminal = finishes.singleOrNull()?.state
                summaries += AgentReplayRunSummary(
                    runId = runId,
                    sessionId = sessions.singleOrNull().orEmpty(),
                    terminalState = terminal,
                    eventCount = values.size,
                    providerSteps = providerStarts.size,
                    toolCalls = toolRequests.size,
                    approvalRequests = approvals.size,
                    complete = starts.size == 1 && finishes.size == 1 &&
                        terminalIndex == values.lastIndex
                )
            }
        return AgentReplayReport(events.size, summaries, issues)
    }

    private fun issue(
        runId: String,
        code: String,
        severity: AgentReplayIssueSeverity,
        summary: String
    ) = AgentReplayIssue(runId, code, severity, summary)
}
