// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import java.util.concurrent.CompletionStage

/**
 * Optional extension for a [DeviceSurface] that can isolate device effects to a
 * host-owned lifecycle scope.
 *
 * The original synchronous [DeviceSurface] contract remains unchanged. Hosts
 * that require a truthful global Stop feature-detect this interface, open one
 * scope per run, and execute that run through the returned surface. A new scope
 * must not be opened until the previous scope's stop handle is quiescent.
 */
interface CancellableDeviceSurface : DeviceSurface {
    /** Opens an independently stoppable effect scope for one host run. */
    fun openEffectScope(scopeId: String): DeviceSurfaceEffectScope
}

/**
 * A run-scoped [DeviceSurface] whose effects can be fenced and drained.
 *
 * [requestStop] is safe to call from the Android main thread: it synchronously
 * closes effect admission and cancels queued work, but never waits for an
 * already-dispatched gesture, Accessibility callback, or cleanup task.
 */
interface DeviceSurfaceEffectScope : DeviceSurface, DeviceSurfaceStopController, AutoCloseable {
    val scopeId: String

    override fun close() {
        requestStop("scope.closed")
    }
}

/** Non-blocking Stop entry point for a scoped device surface. */
interface DeviceSurfaceStopController {
    /**
     * Fences new effects and returns the same handle on repeated calls.
     *
     * The first valid [reason] wins. The returned [DeviceSurfaceStopHandle]
     * reaches quiescence only after every effect admitted before this call has
     * completed or observed the fence and every required cleanup task has run.
     */
    fun requestStop(reason: String): DeviceSurfaceStopHandle
}

/** Awaitable result of a non-blocking device-surface Stop request. */
interface DeviceSurfaceStopHandle {
    val scopeId: String
    val reason: String
    val quiescence: CompletionStage<DeviceSurfaceStopOutcome>
}

/**
 * Immutable proof that a stopped effect scope has no admitted work remaining.
 *
 * [cancelledQueuedTasks] counts main-thread tasks that had been admitted but
 * had not begun when Stop won the race. Already-dispatched Android gestures are
 * instead awaited and are included in [completedEffects].
 */
data class DeviceSurfaceStopOutcome(
    val scopeId: String,
    val reason: String,
    val admittedEffects: Long,
    val completedEffects: Long,
    val cancelledQueuedTasks: Long
)

/** Stable failure raised when a stopped scope rejects work. */
class DeviceSurfaceStoppedException(
    val scopeId: String,
    val stopReason: String
) : IllegalStateException(
    "DEVICE_SURFACE_STOPPED: effect scope '$scopeId' was stopped ($stopReason)."
)
