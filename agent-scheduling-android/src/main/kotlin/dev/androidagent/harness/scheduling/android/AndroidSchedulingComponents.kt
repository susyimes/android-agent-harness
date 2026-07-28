// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.scheduling.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

object AndroidScheduleRescheduleRegistry {
    @Volatile
    var rescheduleEnabled: (() -> Unit)? = null
}

/**
 * Host-declared receiver. It performs no Agent work; it only invokes the
 * registered schedule re-enqueue callback.
 */
class ScheduleRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val callback = AndroidScheduleRescheduleRegistry.rescheduleEnabled ?: return
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                callback()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val EXECUTOR = Executors.newSingleThreadExecutor { task ->
            Thread(task, "agent-schedule-reschedule").apply { isDaemon = true }
        }
    }
}

object VisibleLongTaskRegistry {
    @Volatile
    var stop: ((jobId: String, reason: String) -> Boolean)? = null
}

class AgentNotificationController(
    context: Context
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun longTaskNotification(jobId: String, title: String, summary: String): Notification {
        ensureChannel()
        val stopIntent = Intent(appContext, VisibleLongTaskService::class.java)
            .setAction(VisibleLongTaskService.ACTION_STOP)
            .putExtra(VisibleLongTaskService.EXTRA_JOB_ID, jobId)
        val stopPending = PendingIntent.getService(
            appContext,
            jobId.hashCode(),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Agent long tasks",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "agent_long_tasks"
    }
}

/**
 * Optional host-declared foreground carrier. It never constructs or runs an
 * Agent; the host starts it only for an already-dispatched visible LongTask.
 */
class VisibleLongTaskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID)
        if (intent?.action == ACTION_STOP) {
            if (jobId != null) {
                VisibleLongTaskRegistry.stop?.invoke(jobId, "Stopped from notification.")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (jobId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Agent task" }
        val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty().ifBlank { "Working…" }
        startForeground(
            NOTIFICATION_ID_BASE + (jobId.hashCode().absoluteValue % 10_000),
            AgentNotificationController(this).longTaskNotification(jobId, title, summary)
        )
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_START = "dev.androidagent.harness.action.START_LONG_TASK"
        const val ACTION_STOP = "dev.androidagent.harness.action.STOP_LONG_TASK"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUMMARY = "summary"
        private const val NOTIFICATION_ID_BASE = 30_000
    }
}

private val Int.absoluteValue: Int
    get() = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
