// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service that gives the harness eyes and hands on a real
 * device. It performs no work of its own: [AccessibilityDeviceSurface] pulls
 * window content on demand through the connected instance, and
 * [OverlayApprovalGate] borrows its window token to show the approval overlay.
 *
 * The user must enable this service in the system accessibility settings
 * (see [AccessibilityAvailability]) before any device tool can operate.
 *
 * EVENTS. The service subscribes to exactly the four event types stability
 * detection needs — window state, window content, scroll and focus — and does
 * one thing with them: it records when the last one arrived. Subscribing to
 * typeAllMask instead would hand this process a copy of every text change on
 * the device (including keystrokes in other apps) for no benefit at all, and
 * the callback would run often enough to matter. [lastRelevantEventUptimeMillis]
 * is what makes [AccessibilityDeviceSurface.waitForStable] event-driven rather
 * than a fixed sleep.
 *
 * Only the service instance itself is retained (in a companion reference set
 * on connect and cleared on unbind/destroy); no Activity or other UI object
 * is ever stored here, so the service cannot leak screens.
 */
class HarnessAccessibilityService : AccessibilityService() {

    @Volatile
    private var lastEventUptimeMillis: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastEventUptimeMillis = 0L
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        if (eventType and RELEVANT_EVENT_TYPES != 0) {
            lastEventUptimeMillis = SystemClock.uptimeMillis()
        }
    }

    /**
     * [SystemClock.uptimeMillis] of the last screen-changing event, or 0 when
     * none has arrived since the service connected.
     *
     * Monotonic on purpose: a wall-clock timestamp would make a quiet-window
     * measurement jump whenever the system clock is adjusted.
     */
    fun lastRelevantEventUptimeMillis(): Long = lastEventUptimeMillis

    override fun onInterrupt() {
        // Intentionally empty: this service produces no ongoing feedback to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    companion object {
        /**
         * Event types that indicate the screen may have changed.
         *
         * Mirrors android:accessibilityEventTypes in the service configuration;
         * kept as a mask here too so a wider configuration (for example one
         * merged in by a host app) still cannot widen what this service reacts
         * to.
         */
        private const val RELEVANT_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED

        @Volatile
        private var instance: HarnessAccessibilityService? = null

        /**
         * The currently connected service instance, or null while the service
         * is not enabled or not yet bound by the system.
         */
        fun connectedInstance(): HarnessAccessibilityService? = instance
    }
}
