// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.scheduling.android

import dev.androidagent.harness.scheduling.DeliveryPolicy
import dev.androidagent.harness.scheduling.DispatchReceipt
import dev.androidagent.harness.scheduling.DispatchStatus
import dev.androidagent.harness.scheduling.LongTaskCheckpoint
import dev.androidagent.harness.scheduling.LongTaskStatus
import dev.androidagent.harness.scheduling.ScheduleCadence
import dev.androidagent.harness.scheduling.ScheduleSpec
import dev.androidagent.harness.scheduling.ScheduleTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidSchedulingStoresTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun schedulesPersistByRevisionWithoutRegisteringWork() {
        val root = temporaryFolder.newFolder("schedules")
        val repository = FileScheduleRepository(root)
        val schedule = ScheduleSpec(
            id = "heartbeat",
            targetType = ScheduleTargetType.HEARTBEAT,
            cadence = ScheduleCadence.interval(60_000L),
            timezone = "UTC",
            validFromEpochMillis = 100L,
            revision = 1,
            enabled = true,
            deliveryPolicy = DeliveryPolicy.IN_APP,
            reason = "User enabled.",
            toolProfileId = "read-only",
            contextPolicyId = "heartbeat"
        )
        repository.put(schedule, expectedRevision = null)

        val reopened = FileScheduleRepository(root)

        assertEquals(schedule, reopened.get(schedule.id))
        assertTrue(
            runCatching {
                reopened.put(schedule.copy(revision = 3), expectedRevision = 1)
            }.isFailure
        )
    }

    @Test
    fun longTaskCheckpointPersistsCancellationAndLateReceipt() {
        val root = temporaryFolder.newFolder("checkpoints")
        val store = AndroidRunCheckpointStore(root)
        val checkpoint = LongTaskCheckpoint(
            jobId = "job",
            sessionId = "session",
            revision = 1L,
            status = LongTaskStatus.CANCELLED,
            burst = 2,
            authorizationScopeHash = "scope",
            nextAction = null,
            evidenceRefs = listOf("evidence"),
            effectRefs = listOf("effect"),
            repeatedFailureKey = null,
            repeatedFailureCount = 0,
            lastReceipt = DispatchReceipt(
                "job-burst-2",
                DispatchStatus.CANCELLED,
                "Stopped by user."
            ),
            updatedAtEpochMillis = 100L
        )
        store.put(checkpoint, expectedRevision = null)

        assertEquals(
            LongTaskStatus.CANCELLED,
            AndroidRunCheckpointStore(root).get("job")!!.status
        )
    }

    @Test
    fun scheduleAndCheckpointMaintenancePersistEmptyState() {
        val scheduleRoot = temporaryFolder.newFolder("schedule-clear")
        val schedules = FileScheduleRepository(scheduleRoot)
        val schedule = ScheduleSpec(
            id = "clear-me",
            targetType = ScheduleTargetType.CRON,
            cadence = ScheduleCadence.interval(60_000L),
            timezone = "UTC",
            validFromEpochMillis = 100L,
            revision = 1,
            enabled = false,
            deliveryPolicy = DeliveryPolicy.IN_APP,
            reason = "test",
            toolProfileId = "read-only",
            contextPolicyId = "test"
        )
        schedules.put(schedule, null)
        assertTrue(schedules.removeAll(mapOf(schedule.id to schedule.revision)))
        assertTrue(FileScheduleRepository(scheduleRoot).list().isEmpty())

        val checkpointRoot = temporaryFolder.newFolder("checkpoint-clear")
        val checkpoints = AndroidRunCheckpointStore(checkpointRoot)
        checkpoints.put(
            LongTaskCheckpoint(
                jobId = "job-clear",
                sessionId = "session",
                revision = 1L,
                status = LongTaskStatus.READY,
                burst = 0,
                authorizationScopeHash = "scope",
                nextAction = "continue",
                evidenceRefs = emptyList(),
                effectRefs = emptyList(),
                repeatedFailureKey = null,
                repeatedFailureCount = 0,
                lastReceipt = null,
                updatedAtEpochMillis = 100L
            ),
            null
        )
        assertEquals(1, checkpoints.clear())
        assertTrue(AndroidRunCheckpointStore(checkpointRoot).list().isEmpty())
    }
}
