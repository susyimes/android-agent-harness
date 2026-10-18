// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import dev.androidagent.harness.deviceloop.DeviceSurfaceStopHandle
import dev.androidagent.harness.deviceloop.DeviceSurfaceStopOutcome
import dev.androidagent.harness.deviceloop.DeviceSurfaceStoppedException
import java.util.concurrent.CompletableFuture

/**
 * Linearizes Accessibility effect admission, main-task dispatch, and Stop.
 *
 * This class intentionally contains no Android types so its races can be
 * exercised by ordinary JVM tests. Android scheduling stays in
 * [AccessibilityDeviceSurface] and is registered here before Handler.post can
 * race a Stop request.
 */
internal class AccessibilityEffectScopeCoordinator(
    val scopeId: String,
    private val onQuiesced: (AccessibilityEffectScopeCoordinator) -> Unit = {}
) {
    private val lock = Any()
    private val activeEffects = linkedSetOf<Long>()
    private val pendingMainTasks = linkedMapOf<Long, PendingMainTask>()
    private val runningMainTasks = linkedSetOf<Long>()

    private var nextEffectId = 0L
    private var nextMainTaskId = 0L
    private var admittedEffects = 0L
    private var completedEffects = 0L
    private var cancelledQueuedTasks = 0L
    private var stoppedReason: String? = null
    private var stopHandle: StopHandle? = null

    @Volatile
    private var quiescent = false

    init {
        require(scopeId.isNotBlank()) { "Device surface effect scope id must not be blank." }
        require(scopeId.length <= MAX_SCOPE_ID_LENGTH) {
            "Device surface effect scope id must be at most $MAX_SCOPE_ID_LENGTH characters."
        }
    }

    fun isQuiescent(): Boolean = quiescent

    fun ensureOpen() {
        synchronized(lock) {
            stoppedExceptionLocked()?.let { failure -> throw failure }
        }
    }

    fun <T> runEffect(block: (EffectLease) -> T): T {
        val lease = synchronized(lock) {
            stoppedExceptionLocked()?.let { failure -> throw failure }
            nextEffectId = nextPositiveId(nextEffectId, "effect")
            check(activeEffects.add(nextEffectId)) {
                "Device surface effect id '$nextEffectId' was admitted twice."
            }
            admittedEffects += 1L
            EffectLease(nextEffectId)
        }
        return try {
            block(lease)
        } finally {
            finishEffect(lease)
        }
    }

    /**
     * Closes admission and cancels every queued main task without waiting for
     * an active effect or Android callback.
     */
    fun requestStop(reason: String): DeviceSurfaceStopHandle {
        requireStopReason(reason)
        val cancellations: List<PendingMainTask>
        val handle: StopHandle
        val completion: Completion?
        synchronized(lock) {
            stopHandle?.let { existing -> return existing }
            stoppedReason = reason
            handle = StopHandle(scopeId, reason)
            stopHandle = handle
            cancellations = pendingMainTasks.values.toList()
            pendingMainTasks.clear()
            cancelledQueuedTasks += cancellations.size.toLong()
            completion = completionIfReadyLocked()
        }

        val stopped = DeviceSurfaceStoppedException(scopeId, reason)
        cancellations.forEach { task -> task.cancel(stopped) }
        completeOutsideLock(completion)
        return handle
    }

    inner class EffectLease internal constructor(internal val id: Long) {
        /** Linearization point immediately before a synchronous Android effect. */
        fun beginEffectStep() {
            synchronized(lock) {
                requireActiveLocked(this)
                stoppedExceptionLocked()?.let { failure -> throw failure }
            }
        }

        /**
         * Registers and posts a main-thread task under the same lock as Stop.
         * Either posting wins and Stop can remove it, or Stop wins and posting
         * never occurs.
         */
        fun scheduleMainTask(
            post: (MainTaskTicket) -> Boolean,
            cancel: (MainTaskTicket, DeviceSurfaceStoppedException) -> Unit
        ): MainTaskTicket {
            synchronized(lock) {
                requireActiveLocked(this)
                stoppedExceptionLocked()?.let { failure -> throw failure }
                nextMainTaskId = nextPositiveId(nextMainTaskId, "main task")
                val ticket = MainTaskTicket(nextMainTaskId, id)
                val task = PendingMainTask(
                    ticket = ticket,
                    cancel = { failure ->
                        cancel(ticket, failure)
                    }
                )
                check(pendingMainTasks.put(task.ticket.id, task) == null) {
                    "Device surface main task '${task.ticket.id}' was queued twice."
                }
                val posted = try {
                    post(task.ticket)
                } catch (failure: Throwable) {
                    pendingMainTasks.remove(task.ticket.id)
                    throw failure
                }
                if (!posted) {
                    pendingMainTasks.remove(task.ticket.id)
                    throw IllegalStateException(
                        "The Android main thread rejected a device-surface task."
                    )
                }
                return task.ticket
            }
        }

        /** True only when this queued task beats Stop to its dispatch point. */
        fun beginMainTask(ticket: MainTaskTicket): Boolean {
            synchronized(lock) {
                require(ticket.effectId == id) {
                    "Device surface main task belongs to another effect."
                }
                val task = pendingMainTasks.remove(ticket.id) ?: return false
                check(task.ticket == ticket) {
                    "Device surface main task ticket changed while queued."
                }
                if (stoppedReason != null || id !in activeEffects) {
                    return false
                }
                check(runningMainTasks.add(ticket.id)) {
                    "Device surface main task '${ticket.id}' started twice."
                }
                return true
            }
        }

        fun finishMainTask(ticket: MainTaskTicket) {
            val completion = synchronized(lock) {
                check(ticket.effectId == id) {
                    "Device surface main task belongs to another effect."
                }
                check(runningMainTasks.remove(ticket.id)) {
                    "Device surface main task '${ticket.id}' completed without starting."
                }
                completionIfReadyLocked()
            }
            completeOutsideLock(completion)
        }

        /** Removes a task that timed out before it began. */
        fun abandonMainTask(ticket: MainTaskTicket): Boolean = synchronized(lock) {
            require(ticket.effectId == id) {
                "Device surface main task belongs to another effect."
            }
            pendingMainTasks.remove(ticket.id) != null
        }

        fun stoppedException(): DeviceSurfaceStoppedException? = synchronized(lock) {
            stoppedExceptionLocked()
        }
    }

    internal data class MainTaskTicket(
        val id: Long,
        val effectId: Long
    )

    private class PendingMainTask(
        val ticket: MainTaskTicket,
        val cancel: (DeviceSurfaceStoppedException) -> Unit
    )

    private class StopHandle(
        override val scopeId: String,
        override val reason: String,
        val future: CompletableFuture<DeviceSurfaceStopOutcome> = CompletableFuture()
    ) : DeviceSurfaceStopHandle {
        override val quiescence = future
    }

    private data class Completion(
        val handle: StopHandle,
        val outcome: DeviceSurfaceStopOutcome
    )

    private fun finishEffect(lease: EffectLease) {
        val completion = synchronized(lock) {
            check(activeEffects.remove(lease.id)) {
                "Device surface effect '${lease.id}' completed without admission."
            }
            completedEffects += 1L
            completionIfReadyLocked()
        }
        completeOutsideLock(completion)
    }

    private fun requireActiveLocked(lease: EffectLease) {
        check(lease.id in activeEffects) {
            "Device surface effect '${lease.id}' is no longer active."
        }
    }

    private fun stoppedExceptionLocked(): DeviceSurfaceStoppedException? {
        val reason = stoppedReason ?: return null
        return DeviceSurfaceStoppedException(scopeId, reason)
    }

    private fun completionIfReadyLocked(): Completion? {
        val handle = stopHandle ?: return null
        if (
            quiescent ||
            activeEffects.isNotEmpty() ||
            pendingMainTasks.isNotEmpty() ||
            runningMainTasks.isNotEmpty()
        ) {
            return null
        }
        quiescent = true
        return Completion(
            handle = handle,
            outcome = DeviceSurfaceStopOutcome(
                scopeId = scopeId,
                reason = handle.reason,
                admittedEffects = admittedEffects,
                completedEffects = completedEffects,
                cancelledQueuedTasks = cancelledQueuedTasks
            )
        )
    }

    private fun completeOutsideLock(completion: Completion?) {
        if (completion == null) return
        onQuiesced(this)
        completion.handle.future.complete(completion.outcome)
    }

    private fun nextPositiveId(current: Long, kind: String): Long {
        val next = current + 1L
        check(next > 0L) { "Device surface $kind id space was exhausted." }
        return next
    }

    private fun requireStopReason(reason: String) {
        require(STOP_REASON.matches(reason)) {
            "Device surface stop reason must be a lowercase opaque code of at most " +
                "$MAX_STOP_REASON_LENGTH characters."
        }
    }

    private companion object {
        const val MAX_SCOPE_ID_LENGTH = 128
        const val MAX_STOP_REASON_LENGTH = 128
        val STOP_REASON = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    }
}
