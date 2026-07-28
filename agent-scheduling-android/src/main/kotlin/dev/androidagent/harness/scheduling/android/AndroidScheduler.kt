// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.scheduling.android

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.androidagent.harness.scheduling.DispatchReceipt
import dev.androidagent.harness.scheduling.DispatchStatus
import dev.androidagent.harness.scheduling.DeliveryPolicy
import dev.androidagent.harness.scheduling.JobLeaseStore
import dev.androidagent.harness.scheduling.LeaseResult
import dev.androidagent.harness.scheduling.MutableRunControl
import dev.androidagent.harness.scheduling.OccurrenceAuthorizationSnapshot
import dev.androidagent.harness.scheduling.OccurrenceTrigger
import dev.androidagent.harness.scheduling.PeriodicRunner
import dev.androidagent.harness.scheduling.ScheduleCalculator
import dev.androidagent.harness.scheduling.ScheduleReceipt
import dev.androidagent.harness.scheduling.ScheduleRepository
import dev.androidagent.harness.scheduling.ScheduleSpec
import dev.androidagent.harness.scheduling.SchedulerBackend
import java.util.concurrent.TimeUnit

interface AndroidOccurrenceHost {
    val schedules: ScheduleRepository
    val leases: JobLeaseStore
    val runner: PeriodicRunner

    fun authorizationSnapshot(spec: ScheduleSpec): OccurrenceAuthorizationSnapshot

    fun constraintsSatisfied(spec: ScheduleSpec): Boolean = true

    fun record(receipt: DispatchReceipt)

    fun reschedule(spec: ScheduleSpec, afterEpochMillis: Long)
}

object AndroidOccurrenceHostRegistry {
    @Volatile
    var host: AndroidOccurrenceHost? = null
}

class AndroidSchedulerBackend(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : SchedulerBackend {
    override fun schedule(spec: ScheduleSpec): ScheduleReceipt {
        workManager.cancelAllWorkByTag(scheduleTag(spec.id))
        if (!spec.enabled) {
            return ScheduleReceipt(
                spec.id,
                spec.revision,
                true,
                null,
                "Schedule is disabled; existing work was cancelled."
            )
        }
        return enqueueNext(spec, nowEpochMillis())
    }

    override fun cancel(scheduleId: String): Boolean {
        workManager.cancelAllWorkByTag(scheduleTag(scheduleId))
        return true
    }

    fun enqueueNext(spec: ScheduleSpec, afterEpochMillis: Long): ScheduleReceipt {
        val planned = ScheduleCalculator.nextRunAt(spec, afterEpochMillis)
            ?: return ScheduleReceipt(
                spec.id,
                spec.revision,
                true,
                null,
                "Schedule has no remaining occurrence."
            )
        val occurrenceId = ScheduleCalculator.occurrenceId(spec, planned)
        val request = OneTimeWorkRequestBuilder<AgentOccurrenceWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_SCHEDULE_ID, spec.id)
                    .putLong(KEY_SCHEDULE_REVISION, spec.revision)
                    .putString(KEY_OCCURRENCE_ID, occurrenceId)
                    .putLong(KEY_PLANNED_AT, planned)
                    .build()
            )
            .setInitialDelay((planned - nowEpochMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(spec.toWorkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(scheduleTag(spec.id))
            .addTag(occurrenceTag(occurrenceId))
            .build()
        workManager.enqueueUniqueWork(
            occurrenceWorkName(occurrenceId),
            ExistingWorkPolicy.KEEP,
            request
        )
        return ScheduleReceipt(
            spec.id,
            spec.revision,
            true,
            planned,
            "Enqueued occurrence '$occurrenceId'."
        )
    }

    private fun ScheduleSpec.toWorkConstraints(): Constraints {
        val networkType = when {
            constraints.requiresUnmeteredNetwork -> NetworkType.UNMETERED
            constraints.requiresNetwork -> NetworkType.CONNECTED
            else -> NetworkType.NOT_REQUIRED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(constraints.requiresCharging)
            .setRequiresDeviceIdle(constraints.requiresDeviceIdle)
            .build()
    }

    companion object {
        const val KEY_SCHEDULE_ID = "agent_schedule_id"
        const val KEY_SCHEDULE_REVISION = "agent_schedule_revision"
        const val KEY_OCCURRENCE_ID = "agent_occurrence_id"
        const val KEY_PLANNED_AT = "agent_planned_at"

        fun scheduleTag(id: String) = "agent-schedule:$id"
        fun occurrenceTag(id: String) = "agent-occurrence:$id"
        fun occurrenceWorkName(id: String) = "agent-occurrence:$id"
    }
}

class AgentOccurrenceWorker(
    appContext: Context,
    parameters: WorkerParameters
) : Worker(appContext, parameters) {
    @Volatile
    private var control: MutableRunControl? = null

    override fun doWork(): Result {
        val host = AndroidOccurrenceHostRegistry.host ?: return Result.failure(
            Data.Builder().putString("error", "occurrence_host_unavailable").build()
        )
        val scheduleId = inputData.getString(AndroidSchedulerBackend.KEY_SCHEDULE_ID)
            ?: return Result.failure()
        val expectedRevision = inputData.getLong(
            AndroidSchedulerBackend.KEY_SCHEDULE_REVISION,
            -1L
        )
        val occurrenceId = inputData.getString(AndroidSchedulerBackend.KEY_OCCURRENCE_ID)
            ?: return Result.failure()
        val plannedAt = inputData.getLong(AndroidSchedulerBackend.KEY_PLANNED_AT, -1L)
        val now = System.currentTimeMillis()
        val spec = host.schedules.get(scheduleId)
        val preflight = when {
            spec == null || !spec.enabled -> DispatchReceipt(
                occurrenceId,
                DispatchStatus.SKIPPED_DISABLED,
                "Schedule is missing or disabled."
            )
            spec.revision != expectedRevision -> DispatchReceipt(
                occurrenceId,
                DispatchStatus.SKIPPED_REVISION_CHANGED,
                "Schedule revision changed before dispatch."
            )
            now > plannedAt + spec.executionWindowMillis -> DispatchReceipt(
                occurrenceId,
                DispatchStatus.EXPIRED,
                "Occurrence execution window expired."
            )
            !host.constraintsSatisfied(spec) -> DispatchReceipt(
                occurrenceId,
                DispatchStatus.SKIPPED_CONSTRAINT,
                "Runtime constraint check failed."
            )
            else -> null
        }
        if (preflight != null) {
            host.record(preflight)
            spec?.takeIf(ScheduleSpec::enabled)?.let { value -> host.reschedule(value, now) }
            return Result.success()
        }
        val liveSpec = requireNotNull(spec)
        val leaseExpiry = now + liveSpec.executionWindowMillis
        when (host.leases.tryAcquire(scheduleId, occurrenceId, leaseExpiry)) {
            is LeaseResult.Busy,
            LeaseResult.DuplicateCompleted -> {
                val receipt = DispatchReceipt(
                    occurrenceId,
                    DispatchStatus.SKIPPED_DUPLICATE,
                    "Occurrence already has a lease or completed."
                )
                host.record(receipt)
                return Result.success()
            }
            is LeaseResult.Acquired -> Unit
        }
        val runControl = MutableRunControl()
        control = runControl
        val visibleCarrier = liveSpec.deliveryPolicy == DeliveryPolicy.VISIBLE_LONG_TASK
        if (visibleCarrier) {
            val carrierError = runCatching {
                val intent = Intent(applicationContext, VisibleLongTaskService::class.java)
                    .setAction(VisibleLongTaskService.ACTION_START)
                    .putExtra(VisibleLongTaskService.EXTRA_JOB_ID, scheduleId)
                    .putExtra(VisibleLongTaskService.EXTRA_TITLE, "Agent long task")
                    .putExtra(
                        VisibleLongTaskService.EXTRA_SUMMARY,
                        liveSpec.reason.take(120)
                    )
                if (Build.VERSION.SDK_INT >= 26) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            }.exceptionOrNull()
            if (carrierError != null) {
                control = null
                host.leases.release(scheduleId, occurrenceId, completed = true)
                val receipt = DispatchReceipt(
                    occurrenceId,
                    DispatchStatus.FAILED,
                    carrierError.message ?: "Visible LongTask carrier is unavailable.",
                    retryable = false
                )
                host.record(receipt)
                host.reschedule(liveSpec, System.currentTimeMillis())
                return Result.success()
            }
        }
        val receipt = try {
            host.runner.dispatch(
                OccurrenceTrigger(
                    scheduleId = scheduleId,
                    scheduleRevision = expectedRevision,
                    targetType = liveSpec.targetType,
                    occurrenceId = occurrenceId,
                    plannedAtEpochMillis = plannedAt,
                    actualAtEpochMillis = now,
                    attempt = runAttemptCount + 1,
                    reason = liveSpec.reason,
                    authorization = host.authorizationSnapshot(liveSpec)
                ),
                runControl
            )
        } catch (error: RuntimeException) {
            DispatchReceipt(
                occurrenceId,
                DispatchStatus.FAILED,
                error.message ?: "Occurrence dispatch failed.",
                retryable = true
            )
        } finally {
            control = null
            if (visibleCarrier) {
                applicationContext.stopService(
                    Intent(applicationContext, VisibleLongTaskService::class.java)
                )
            }
        }
        val terminal = !receipt.retryable || receipt.status != DispatchStatus.FAILED
        host.leases.release(scheduleId, occurrenceId, completed = terminal)
        host.record(receipt)
        return if (
            receipt.status == DispatchStatus.FAILED &&
            receipt.retryable &&
            System.currentTimeMillis() <= plannedAt + liveSpec.executionWindowMillis
        ) {
            Result.retry()
        } else {
            host.reschedule(liveSpec, System.currentTimeMillis())
            Result.success()
        }
    }

    override fun onStopped() {
        control?.cancel("WorkManager stopped the occurrence.")
        super.onStopped()
    }
}
