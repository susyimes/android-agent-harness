// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.scheduling

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.sdk.AgentEvent
import dev.androidagent.harness.sdk.AgentRunBudget
import dev.androidagent.harness.sdk.TraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTaskTest {
    @Test
    fun repeatedFailureStopsAtThresholdAndCheckpointIsDurable() {
        val clock = AgentClock { 100L }
        val store = InMemoryLongTaskCheckpointStore()
        val runner = PeriodicRunner { trigger, _ ->
            DispatchReceipt(
                trigger.occurrenceId,
                DispatchStatus.FAILED,
                "same provider failure",
                retryable = true
            )
        }
        val events = mutableListOf<AgentEvent>()
        val coordinator = LongTaskCoordinator(
            runner,
            store,
            clock,
            TraceSink(events::add)
        )
        val spec = LongTaskSpec(
            id = "research",
            sessionId = "session",
            goal = "Complete research",
            authorizationScopeHash = "scope",
            deadlineEpochMillis = 1_000L,
            resumable = true,
            maxRepeatedFailures = 2
        )

        val first = coordinator.dispatchBurst(spec, auth())
        val second = coordinator.dispatchBurst(spec, auth())

        assertEquals(LongTaskStatus.READY, first.status)
        assertEquals(LongTaskStatus.FAILED, second.status)
        assertEquals(2, store.get(spec.id)!!.burst)
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L),
            events.filterIsInstance<AgentEvent.CheckpointSaved>().map { it.revision }
        )
    }

    @Test
    fun stopCancelsActiveControlAndLateCompletionCannotRestoreTask() {
        val store = InMemoryLongTaskCheckpointStore()
        lateinit var coordinator: LongTaskCoordinator
        val runner = PeriodicRunner { trigger, control ->
            assertTrue(coordinator.stop("job"))
            assertTrue(control.isCancellationRequested)
            DispatchReceipt(trigger.occurrenceId, DispatchStatus.COMPLETED, "late success")
        }
        coordinator = LongTaskCoordinator(runner, store, AgentClock { 100L })
        val spec = LongTaskSpec(
            id = "job",
            sessionId = "session",
            goal = "Long job",
            authorizationScopeHash = "scope",
            deadlineEpochMillis = 1_000L,
            resumable = true
        )

        val result = coordinator.dispatchBurst(spec, auth())

        assertEquals(LongTaskStatus.CANCELLED, result.status)
        assertEquals(DispatchStatus.CANCELLED, result.lastReceipt!!.status)
    }

    @Test
    fun pauseCancelsActiveBurstAndRequiresExactScopeResume() {
        val store = InMemoryLongTaskCheckpointStore()
        lateinit var coordinator: LongTaskCoordinator
        var dispatchCount = 0
        val runner = PeriodicRunner { trigger, control ->
            dispatchCount += 1
            if (dispatchCount == 1) {
                assertTrue(coordinator.pause("job", "User paused."))
                assertTrue(control.isCancellationRequested)
                DispatchReceipt(
                    trigger.occurrenceId,
                    DispatchStatus.COMPLETED,
                    "late completion after pause"
                )
            } else {
                DispatchReceipt(trigger.occurrenceId, DispatchStatus.COMPLETED, "finished")
            }
        }
        coordinator = LongTaskCoordinator(runner, store, AgentClock { 100L })
        val spec = LongTaskSpec(
            id = "job",
            sessionId = "session",
            goal = "Long job",
            authorizationScopeHash = "scope",
            deadlineEpochMillis = 1_000L,
            resumable = true
        )

        val paused = coordinator.dispatchBurst(spec, auth())
        val stillPaused = coordinator.dispatchBurst(spec, auth())

        assertEquals(LongTaskStatus.PAUSED, paused.status)
        assertEquals(LongTaskStatus.PAUSED, stillPaused.status)
        assertEquals(1, dispatchCount)
        assertTrue(!coordinator.resume("job", "wrong-scope"))
        assertTrue(coordinator.resume("job", "scope"))

        val completed = coordinator.dispatchBurst(spec, auth())

        assertEquals(LongTaskStatus.COMPLETED, completed.status)
        assertEquals(2, dispatchCount)
    }

    @Test
    fun burstBudgetAndRunReferencesReachDurableCheckpoint() {
        val store = InMemoryLongTaskCheckpointStore()
        val budget = AgentRunBudget(
            maxProviderSteps = 7,
            maxToolCalls = 2,
            maxWallClockMillis = 10_000L,
            maxRepeatedFailures = 2
        )
        var observedBudget: AgentRunBudget? = null
        val runner = LongTaskPeriodicRunner { trigger, _, receivedBudget ->
            observedBudget = receivedBudget
            LongTaskBurstResult(
                receipt = DispatchReceipt(
                    occurrenceId = trigger.occurrenceId,
                    status = DispatchStatus.ACCEPTED,
                    summary = "continue",
                    checkpointRef = "next"
                ),
                evidenceRefs = listOf("evidence:one", "evidence:two"),
                effectRefs = listOf("effect:one")
            )
        }
        val coordinator = LongTaskCoordinator(runner, store, AgentClock { 100L })
        val spec = LongTaskSpec(
            id = "budgeted",
            sessionId = "session",
            goal = "Continue safely",
            authorizationScopeHash = "scope",
            deadlineEpochMillis = 1_000L,
            resumable = true,
            burstBudget = budget
        )

        val checkpoint = coordinator.dispatchBurst(spec, auth())

        assertEquals(budget, observedBudget)
        assertEquals(listOf("evidence:one", "evidence:two"), checkpoint.evidenceRefs)
        assertEquals(listOf("effect:one"), checkpoint.effectRefs)
        assertEquals("next", checkpoint.nextAction)
    }

    private fun auth() = OccurrenceAuthorizationSnapshot(
        grantedCapabilityIds = emptySet(),
        credentialRevision = "credential-1",
        policyRevision = "policy-1",
        capturedAtEpochMillis = 100L
    )
}
