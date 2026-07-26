// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

/**
 * The contract the device tools need from any UI surface: observe the current
 * screen as semantic nodes and drive it.
 *
 * Implementations may be an in-memory fake ([FakeDevice]) or an adapter over a
 * real device (for example an accessibility-service bridge). The tools only
 * ever see this interface, so swapping the surface never changes tool behavior.
 *
 * CAPABILITY MODEL: only [snapshot], [tap] and [setText] are mandatory. Every
 * other action has a default implementation that throws
 * [UnsupportedOperationException], so a surface implements exactly what it can
 * really do and [DeviceActTool] turns the rest into a clean UNSUPPORTED_ACTION
 * failure naming the action. Returning a fake success instead would be far
 * worse: the model would believe it scrolled and keep acting on a screen that
 * never moved.
 *
 * Failure contract (the tool layer maps these to structured failures):
 * - [DeviceActionException] carries its own [DeviceErrorType]; use it whenever
 *   the surface can classify the failure (permission missing, app not
 *   installed, foreground never changed).
 * - [IllegalArgumentException] means the node id is unknown on the current
 *   screen (rendered as TARGET_NOT_FOUND with candidates).
 * - Any other [RuntimeException] is reported as ACTION_FAILED.
 *
 * Threading: every method is synchronous and blocking; the harness calls them
 * from the turn thread.
 */
interface DeviceSurface {
    /**
     * Current screen rendered as semantic nodes.
     *
     * Repeated calls without intervening actions (and without the app changing
     * itself) must describe the same UI state.
     */
    fun snapshot(): DeviceScreen

    /** Taps the node with [nodeId] on the current screen. */
    fun tap(nodeId: String)

    /** Replaces the text of the node with [nodeId] on the current screen. */
    fun setText(nodeId: String, text: String)

    /** Presses the system back button. */
    fun back(): Unit = throw UnsupportedOperationException(
        "This device surface does not implement back()."
    )

    /**
     * Presses the system home button.
     *
     * Leaving the app almost always breaks a task chain, so [DeviceActTool]
     * refuses this action unless it was explicitly constructed with
     * allowHome = true.
     */
    fun home(): Unit = throw UnsupportedOperationException(
        "This device surface does not implement home()."
    )

    /**
     * Swipes [distancePx] pixels in [direction] (up, down, left or right) over
     * [durationMs] milliseconds. "up" means the content moves up, i.e. the
     * gesture reveals what is below the fold.
     */
    fun swipe(direction: String, distancePx: Int, durationMs: Int): Unit =
        throw UnsupportedOperationException(
            "This device surface does not implement swipe()."
        )

    /**
     * Scrolls at most [maxScrolls] times in [direction] looking for [text],
     * returning true when it became visible.
     *
     * Implementations should stop early when the screen stops changing, so a
     * short list does not burn the whole scroll budget.
     */
    fun scrollToText(text: String, direction: String, maxScrolls: Int): Boolean =
        throw UnsupportedOperationException(
            "This device surface does not implement scrollToText()."
        )

    /**
     * Brings the app identified by [nameOrPackage] to the foreground and
     * returns the package that actually ended up in front.
     *
     * The return value matters: launching by display name is fuzzy, and a
     * launcher may land on a disambiguation dialog or on a different app
     * entirely. Reporting the real package lets the caller notice.
     *
     * Should throw [DeviceActionException] with [DeviceErrorType.APP_NOT_FOUND]
     * when nothing matches.
     */
    fun launchApp(nameOrPackage: String): String = throw UnsupportedOperationException(
        "This device surface does not implement launchApp()."
    )

    /**
     * Blocks until the UI stops changing, returning true when it settled within
     * [timeoutMs] and false when it was still moving.
     *
     * [DeviceActTool] calls this after every successful action (best effort),
     * because acting on a screen mid-animation is the second most common cause
     * of stale-target failures.
     */
    fun waitForStable(timeoutMs: Long): Boolean = throw UnsupportedOperationException(
        "This device surface does not implement waitForStable()."
    )

    /** Package name of the app currently in the foreground, or null if unknown. */
    fun foregroundPackage(): String? = throw UnsupportedOperationException(
        "This device surface does not implement foregroundPackage()."
    )
}
