// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceReplayTest {
    @Test
    fun validLifecycleReplaysAsCompleteAndHealthy() {
        val events = listOf(
            AgentEvent.RunStarted("run", "session", 1, AgentRunTrigger.USER, "fake", budget()),
            AgentEvent.ProviderStarted("run", "session", 2, 1, "fake", emptyList()),
            AgentEvent.ProviderCompleted("run", "session", 3, 1, "final"),
            AgentEvent.RunFinished("run", "session", 4, AgentRunState.COMPLETED, "done")
        )

        val report = AgentTraceReplayEvaluator().evaluate(events)

        assertTrue(report.healthy)
        assertEquals(1, report.completeRunCount)
        assertEquals(1, report.runs.single().providerSteps)
    }

    @Test
    fun lateEventAfterTerminalIsAnError() {
        val events = listOf(
            AgentEvent.RunStarted("run", "session", 1, AgentRunTrigger.USER, "fake", budget()),
            AgentEvent.RunFinished("run", "session", 2, AgentRunState.CANCELLED, "stop"),
            AgentEvent.ProviderDelta("run", "session", 3, 1, "late")
        )

        val report = AgentTraceReplayEvaluator().evaluate(events)

        assertFalse(report.healthy)
        assertTrue(report.issues.any { issue -> issue.code == "EVENT_AFTER_TERMINAL" })
    }

    private fun budget() = AgentRunBudget(
        maxProviderSteps = 8,
        maxToolCalls = 8,
        maxWallClockMillis = 60_000,
        maxRepeatedFailures = 3
    )
}
