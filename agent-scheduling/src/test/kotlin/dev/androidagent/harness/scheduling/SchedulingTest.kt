// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.scheduling

import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulingTest {
    @Test
    fun dailyAndWeeklySchedulesAreTimezoneAwareAndRevisionStable() {
        val zone = ZoneId.of("Asia/Shanghai")
        val after = ZonedDateTime.of(
            2026,
            7,
            28,
            9,
            0,
            0,
            0,
            zone
        ).toInstant().toEpochMilli()
        val daily = spec(
            cadence = ScheduleCadence.daily(LocalTime.of(8, 30)),
            timezone = zone.id
        )
        val weekly = spec(
            cadence = ScheduleCadence.weekly(
                LocalTime.of(10, 0),
                setOf(DayOfWeek.WEDNESDAY)
            ),
            timezone = zone.id
        )

        val dailyNext = ScheduleCalculator.nextRunAt(daily, after)!!
        val weeklyNext = ScheduleCalculator.nextRunAt(weekly, after)!!

        assertEquals(
            "2026-07-29T08:30+08:00[Asia/Shanghai]",
            ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(dailyNext),
                zone
            ).toString()
        )
        assertEquals(
            DayOfWeek.WEDNESDAY,
            ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(weeklyNext),
                zone
            ).dayOfWeek
        )
        assertEquals(
            ScheduleCalculator.occurrenceId(daily, dailyNext),
            ScheduleCalculator.occurrenceId(daily, dailyNext)
        )
        assertNotEquals(
            ScheduleCalculator.occurrenceId(daily, dailyNext),
            ScheduleCalculator.occurrenceId(daily.copy(revision = 2), dailyNext)
        )
    }

    @Test
    fun missedRunPoliciesChooseSkipCatchUpAndNextWindow() {
        val interval = spec(
            cadence = ScheduleCadence.interval(100L)
        ).copy(validFromEpochMillis = 100L)

        val skipped = ScheduleCalculator.recoveryPlan(
            interval.copy(missedRunPolicy = MissedRunPolicy.SKIP),
            450L
        )!!
        val recovered = ScheduleCalculator.recoveryPlan(
            interval.copy(missedRunPolicy = MissedRunPolicy.RUN_ONCE),
            450L
        )!!

        assertEquals(500L, skipped.occurrencePlannedAtEpochMillis)
        assertEquals(500L, skipped.enqueueAtEpochMillis)
        assertFalse(skipped.recoveredMissedRun)
        assertEquals(400L, recovered.occurrencePlannedAtEpochMillis)
        assertEquals(450L, recovered.enqueueAtEpochMillis)
        assertTrue(recovered.recoveredMissedRun)

        val zone = ZoneId.of("UTC")
        val insideWindow = ZonedDateTime.of(
            2026,
            7,
            28,
            9,
            30,
            0,
            0,
            zone
        ).toInstant().toEpochMilli()
        val window = spec(
            cadence = ScheduleCadence(
                type = ScheduleCadenceType.WINDOW,
                intervalMillis = 15 * 60_000L,
                windowStartLocalTime = "09:00",
                windowEndLocalTime = "10:00"
            )
        ).copy(
            validFromEpochMillis = ZonedDateTime.of(
                2026,
                7,
                27,
                0,
                0,
                0,
                0,
                zone
            ).toInstant().toEpochMilli(),
            missedRunPolicy = MissedRunPolicy.NEXT_WINDOW
        )

        val nextWindow = ScheduleCalculator.recoveryPlan(window, insideWindow)!!

        assertEquals(
            "2026-07-29T09:00Z[UTC]",
            ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(
                    nextWindow.occurrencePlannedAtEpochMillis
                ),
                zone
            ).toString()
        )
    }

    @Test
    fun expiredScheduleDoesNotRecoverMissedRun() {
        val expired = spec(
            cadence = ScheduleCadence.interval(100L)
        ).copy(
            validFromEpochMillis = 100L,
            validUntilEpochMillis = 300L,
            missedRunPolicy = MissedRunPolicy.RUN_ONCE
        )

        assertEquals(null, ScheduleCalculator.recoveryPlan(expired, 450L))
    }

    @Test
    fun leasePreventsConcurrentAndCompletedDuplicateOccurrence() {
        var now = 100L
        val store = InMemoryJobLeaseStore(dev.androidagent.harness.AgentClock { now })

        assertTrue(store.tryAcquire("job", "occ", 200L) is LeaseResult.Acquired)
        assertTrue(store.tryAcquire("job", "occ", 250L) is LeaseResult.Busy)
        store.release("job", "occ", completed = true)
        assertTrue(store.tryAcquire("job", "occ", 250L) is LeaseResult.DuplicateCompleted)
        assertTrue(store.shouldContinueSchedule("job", "occ") == true)

        assertTrue(store.tryAcquire("job", "stopped", 250L) is LeaseResult.Acquired)
        store.releaseWithDisposition(
            "job",
            "stopped",
            completed = true,
            continueSchedule = false
        )
        assertTrue(
            store.tryAcquire("job", "stopped", 250L) is LeaseResult.DuplicateCompleted
        )
        assertFalse(store.shouldContinueSchedule("job", "stopped")!!)

        assertTrue(store.tryAcquire("job", "new", 150L) is LeaseResult.Acquired)
        now = 151L
        assertTrue(store.tryAcquire("job", "new", 250L) is LeaseResult.Acquired)
    }

    @Test
    fun realScheduleChangeRequiresApproval() {
        val repository = InMemoryScheduleRepository()
        val backend = RecordingBackend()
        val denied = GovernedScheduleService(
            repository,
            backend,
            approvals(AgentApprovalDecision.DENIED)
        )

        val rejected = denied.apply(spec(), "run", "session")
        assertFalse(rejected.accepted)
        assertTrue(repository.list().isEmpty())

        val approved = GovernedScheduleService(
            repository,
            backend,
            approvals(AgentApprovalDecision.APPROVED)
        ).apply(spec(), "run", "session")
        assertTrue(approved.accepted)
        assertEquals(1, backend.scheduled.size)
    }

    @Test
    fun bulkScheduleDeletionIsExactApprovedAndAtomic() {
        val repository = InMemoryScheduleRepository()
        val backend = RecordingBackend()
        repository.put(spec(), null)

        val denied = GovernedScheduleService(
            repository,
            backend,
            approvals(AgentApprovalDecision.DENIED)
        ).deleteAll("run-denied", "session")
        assertFalse(denied.applied)
        assertEquals(1, repository.list().size)

        val approved = GovernedScheduleService(
            repository,
            backend,
            approvals(AgentApprovalDecision.APPROVED)
        ).deleteAll("run-approved", "session")
        assertTrue(approved.applied)
        assertEquals(1, approved.deletedSchedules)
        assertTrue(repository.list().isEmpty())
    }

    private fun spec(
        cadence: ScheduleCadence = ScheduleCadence.interval(60_000L),
        timezone: String = "UTC"
    ) = ScheduleSpec(
        id = "heartbeat",
        targetType = ScheduleTargetType.HEARTBEAT,
        cadence = cadence,
        timezone = timezone,
        validFromEpochMillis = 0L,
        revision = 1L,
        enabled = true,
        deliveryPolicy = DeliveryPolicy.IN_APP,
        reason = "User enabled a heartbeat.",
        toolProfileId = "heartbeat-readonly",
        contextPolicyId = "heartbeat-context"
    )

    private fun approvals(decision: AgentApprovalDecision) =
        AgentApprovalCoordinator(
            gate = AgentApprovalGate { decision },
            clock = FixedAgentClock(100L),
            idGenerator = SequentialAgentIdGenerator("approval")
        )

    private class RecordingBackend : SchedulerBackend {
        val scheduled = mutableListOf<ScheduleSpec>()

        override fun schedule(spec: ScheduleSpec): ScheduleReceipt {
            scheduled += spec
            return ScheduleReceipt(spec.id, spec.revision, true, 100L, "scheduled")
        }

        override fun cancel(scheduleId: String): Boolean = true
    }
}
