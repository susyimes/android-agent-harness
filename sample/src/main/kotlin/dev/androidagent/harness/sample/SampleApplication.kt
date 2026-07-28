// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Application
import dev.androidagent.harness.scheduling.DispatchReceipt
import dev.androidagent.harness.scheduling.OccurrenceAuthorizationSnapshot
import dev.androidagent.harness.scheduling.ScheduleSpec
import dev.androidagent.harness.scheduling.android.AndroidJobLeaseStore
import dev.androidagent.harness.scheduling.android.AndroidOccurrenceHost
import dev.androidagent.harness.scheduling.android.AndroidOccurrenceHostRegistry
import dev.androidagent.harness.scheduling.android.AndroidScheduleRescheduleRegistry
import dev.androidagent.harness.scheduling.android.AndroidSchedulerBackend
import dev.androidagent.harness.scheduling.android.VisibleLongTaskRegistry

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val schedules = SampleRuntime.schedules(this)
        val backend = AndroidSchedulerBackend(this)
        val runner = SamplePeriodicRunner(this)
        AndroidOccurrenceHostRegistry.host = object : AndroidOccurrenceHost {
            override val schedules = schedules
            override val leases = AndroidJobLeaseStore(this@SampleApplication)
            override val runner = runner

            override fun authorizationSnapshot(
                spec: ScheduleSpec
            ): OccurrenceAuthorizationSnapshot =
                SampleRuntime.authorizationSnapshot(this@SampleApplication, spec)

            override fun record(receipt: DispatchReceipt) {
                SampleRuntime.recordOccurrence(receipt)
            }

            override fun reschedule(spec: ScheduleSpec, afterEpochMillis: Long) {
                backend.enqueueNext(spec, afterEpochMillis)
            }
        }
        AndroidScheduleRescheduleRegistry.rescheduleEnabled = {
            SampleRuntime.schedules(this).list()
                .filter(ScheduleSpec::enabled)
                .forEach(backend::schedule)
        }
        VisibleLongTaskRegistry.stop = { scheduleId, reason ->
            val stopped = SampleRuntime.stopLongTasksForSchedule(
                this@SampleApplication,
                scheduleId,
                reason
            )
            val disabled = SampleRuntime.disableScheduleForStop(
                this@SampleApplication,
                scheduleId
            )
            val cancelled = backend.cancel(scheduleId)
            stopped > 0 || disabled || cancelled
        }
    }

    override fun onTerminate() {
        AndroidOccurrenceHostRegistry.host = null
        AndroidScheduleRescheduleRegistry.rescheduleEnabled = null
        VisibleLongTaskRegistry.stop = null
        super.onTerminate()
    }
}
