// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.eval

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.InMemoryAgentSessionStore
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.StaticAgentContextProvider

/**
 * Governed-evolution gate: candidate overlays over a markdown workspace are evaluated
 * against the baseline workspace on fixed cases before any promotion decision.
 *
 * Each case runs exactly one bounded harness turn with the workspace projected as context
 * items, a fresh provider from [providerFactory], a [FixedAgentClock], and a
 * [SequentialAgentIdGenerator] seeded with the case id, so a given (workspace, case) pair
 * always produces the same transcript.
 */
class EvalRunner(
    private val providerFactory: (MarkdownWorkspace) -> AgentProvider,
    private val tools: List<AgentTool> = emptyList(),
    private val toolProfile: AgentToolProfile? = null
) {

    fun runCases(workspace: MarkdownWorkspace, cases: List<EvalCase>): List<EvalCaseResult> {
        val duplicateCaseIds = cases.groupingBy { case -> case.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateCaseIds.isEmpty()) {
            "Eval case ids must be unique: ${duplicateCaseIds.sorted().joinToString()}."
        }
        val contextItems = workspace.toContextItems()
        return cases.map { case -> runCase(workspace, contextItems, case) }
    }

    fun compare(
        baselineWorkspace: MarkdownWorkspace,
        overlay: Map<String, String?>,
        cases: List<EvalCase>
    ): EvalComparison {
        return EvalComparison(
            baseline = runCases(baselineWorkspace, cases),
            candidate = runCases(baselineWorkspace.withOverlay(overlay), cases)
        )
    }

    private fun runCase(
        workspace: MarkdownWorkspace,
        contextItems: List<AgentContextItem>,
        case: EvalCase
    ): EvalCaseResult {
        val harness = AgentHarnessRunner(
            provider = providerFactory(workspace),
            contextProviders = listOf(StaticAgentContextProvider(contextItems)),
            tools = tools,
            sessionStore = InMemoryAgentSessionStore(),
            clock = FixedAgentClock(EVAL_EPOCH_MILLIS),
            idGenerator = SequentialAgentIdGenerator("eval-${case.id}"),
            toolProfile = toolProfile ?: AgentToolProfile.all()
        )
        val turn = harness.run(
            AgentHarnessRequest(sessionId = "eval-${case.id}", userInput = case.userInput)
        )
        val missing = case.expectedOutputContains.filterNot { expectation ->
            turn.output.contains(expectation)
        }
        return EvalCaseResult(
            caseId = case.id,
            output = turn.output,
            providerSteps = turn.providerSteps,
            passed = missing.isEmpty(),
            missingExpectations = missing
        )
    }

    private companion object {
        const val EVAL_EPOCH_MILLIS = 1_700_000_000_000L
    }
}
