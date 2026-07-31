// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.androidagent.harness.deviceloop.CancellableDeviceSurface
import dev.androidagent.harness.deviceloop.DeviceActionException
import dev.androidagent.harness.deviceloop.DeviceErrorType
import dev.androidagent.harness.deviceloop.DeviceScreen
import dev.androidagent.harness.deviceloop.DeviceSurface
import dev.androidagent.harness.deviceloop.DeviceSurfaceEffectScope
import dev.androidagent.harness.deviceloop.DeviceSurfaceStopHandle
import dev.androidagent.harness.deviceloop.DeviceSurfaceStoppedException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [DeviceSurface] over a real Android accessibility tree.
 *
 * [snapshot] reads the connected service's rootInActiveWindow, maps it with
 * [AccessibilityScreenMapper] and remembers the id-to-node mapping of that
 * snapshot, so the stable ids the model sees resolve back to live nodes.
 * Everything else in the contract is implemented against the real tree:
 * global actions for back and home, dispatched gestures for tap and swipe,
 * PackageManager plus a foreground poll for launchApp, and the service's
 * accessibility-event stream for waitForStable.
 *
 * THREADING. Every method blocks and must be called from a background thread —
 * the harness turn thread. That is not a limitation but the point: a gesture is
 * only complete when the system says it is, and the only honest way to report
 * that from a synchronous contract is to wait for the callback. Calls that
 * would have to block are refused on the main thread instead of deadlocking it.
 *
 * FAILURE CONTRACT (the tool layer converts these into structured failures):
 * - [DeviceActionException] with [DeviceErrorType.PERMISSION_NOT_GRANTED] when
 *   the service is not connected, [DeviceErrorType.APP_NOT_FOUND] when no
 *   installed app matches, [DeviceErrorType.FOREGROUND_TIMEOUT] when a launched
 *   app never reached the foreground, [DeviceErrorType.INVALID_ARGUMENT] for a
 *   malformed direction.
 * - IllegalArgumentException when the node id is unknown on the last snapshot,
 *   per the [DeviceSurface] contract.
 * - IllegalStateException (rendered as ACTION_FAILED) when there is no active
 *   window, no snapshot has been taken yet, the node went stale, or the system
 *   refused an action or gesture.
 *
 * The [serviceProvider] indirection keeps this class free of static service
 * coupling and easy to construct before the user has enabled the service.
 * Nodes retained for the last snapshot are recycled when the next snapshot
 * replaces them (recycle is a no-op on modern API levels).
 */
class AccessibilityDeviceSurface(
    private val serviceProvider: () -> HarnessAccessibilityService? = {
        HarnessAccessibilityService.connectedInstance()
    },
    private val quietWindowMs: Long = DEFAULT_QUIET_WINDOW_MS
) : DeviceSurface, CancellableDeviceSurface {

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val effectScopeLock = Any()
    private val currentEffectLease =
        ThreadLocal<AccessibilityEffectScopeCoordinator.EffectLease?>()

    private var activeEffectScope: AccessibilityEffectScopeCoordinator? = null

    private var lastScreen: DeviceScreen? = null
    private var nodeById: Map<String, AccessibilityNodeInfo> = emptyMap()
    private var retained: List<AccessibilityNodeInfo> = emptyList()

    // -------------------------------------------------------- effect scopes

    override fun openEffectScope(scopeId: String): DeviceSurfaceEffectScope {
        lateinit var created: AccessibilityEffectScopeCoordinator
        synchronized(effectScopeLock) {
            val current = activeEffectScope
            check(current == null || current.isQuiescent()) {
                "The previous device-surface effect scope has not reached quiescence."
            }
            created = AccessibilityEffectScopeCoordinator(scopeId) { completed ->
                synchronized(effectScopeLock) {
                    if (activeEffectScope === completed) {
                        activeEffectScope = null
                    }
                }
            }
            activeEffectScope = created
        }
        return ScopedDeviceSurface(created)
    }

    private inner class ScopedDeviceSurface(
        private val coordinator: AccessibilityEffectScopeCoordinator
    ) : DeviceSurfaceEffectScope {
        override val scopeId: String = coordinator.scopeId

        override fun snapshot(): DeviceScreen = observeScoped { this@AccessibilityDeviceSurface.snapshot() }

        override fun tap(nodeId: String) = effectScoped {
            this@AccessibilityDeviceSurface.tap(nodeId)
        }

        override fun setText(nodeId: String, text: String) = effectScoped {
            this@AccessibilityDeviceSurface.setText(nodeId, text)
        }

        override fun back() = effectScoped { this@AccessibilityDeviceSurface.back() }

        override fun home() = effectScoped { this@AccessibilityDeviceSurface.home() }

        override fun swipe(direction: String, distancePx: Int, durationMs: Int) = effectScoped {
            this@AccessibilityDeviceSurface.swipe(direction, distancePx, durationMs)
        }

        override fun scrollToText(
            text: String,
            direction: String,
            maxScrolls: Int
        ): Boolean = effectScoped {
            this@AccessibilityDeviceSurface.scrollToText(text, direction, maxScrolls)
        }

        override fun launchApp(nameOrPackage: String): String = effectScoped {
            this@AccessibilityDeviceSurface.launchApp(nameOrPackage)
        }

        override fun waitForStable(timeoutMs: Long): Boolean = effectScoped {
            this@AccessibilityDeviceSurface.waitForStable(timeoutMs)
        }

        override fun foregroundPackage(): String? = observeScoped {
            this@AccessibilityDeviceSurface.foregroundPackage()
        }

        override fun requestStop(reason: String): DeviceSurfaceStopHandle =
            coordinator.requestStop(reason)

        private fun <T> observeScoped(block: () -> T): T {
            coordinator.ensureOpen()
            return block()
        }

        private fun <T> effectScoped(block: () -> T): T {
            return coordinator.runEffect { lease ->
                check(currentEffectLease.get() == null) {
                    "A scoped device effect cannot recursively enter another scope."
                }
                currentEffectLease.set(lease)
                try {
                    block()
                } finally {
                    currentEffectLease.remove()
                }
            }
        }
    }

    // ------------------------------------------------------------- observing

    override fun snapshot(): DeviceScreen {
        val service = requireService()
        val root = service.rootInActiveWindow
            ?: throw IllegalStateException(
                "The accessibility service has no active window to observe."
            )
        releaseSnapshot()
        val obtained = mutableListOf(root)
        val mapped = mapOrRelease(root, obtained)
        nodeById = mapped.readerByNodeId.mapValues { entry ->
            (entry.value as AccessibilityNodeInfoReader).node
        }
        retained = obtained
        lastScreen = mapped.screen
        return mapped.screen
    }

    /**
     * Maps the tree, releasing every obtained node when the mapping does not
     * produce a screen.
     *
     * The guard is a finally rather than a catch (RuntimeException): a
     * pathological tree used to answer with StackOverflowError, and an Error
     * walks straight through a RuntimeException catch, leaking the whole
     * obtained batch on its way out.
     */
    private fun mapOrRelease(
        root: AccessibilityNodeInfo,
        obtained: MutableList<AccessibilityNodeInfo>
    ): MappedScreen {
        var mapped: MappedScreen? = null
        try {
            val result = AccessibilityScreenMapper.map(
                root = AccessibilityNodeInfoReader(root, obtained),
                packageName = root.packageName?.toString(),
                windowTitle = windowTitleOf(root)
            )
            mapped = result
            return result
        } finally {
            if (mapped == null) {
                obtained.forEach(::recycleQuietly)
            }
        }
    }

    override fun foregroundPackage(): String? {
        val service = requireService()
        val root = service.rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()?.takeIf { value -> value.isNotBlank() }
        } finally {
            recycleQuietly(root)
        }
    }

    // --------------------------------------------------------------- acting

    override fun tap(nodeId: String) {
        val service = requireService()
        val node = resolve(nodeId)
        refreshOrFail(node, nodeId)
        if (node.isClickable) {
            performClick(node, nodeId)
            return
        }
        val ancestor = findClickableAncestor(node)
        if (ancestor != null) {
            try {
                performClick(ancestor, nodeId)
            } finally {
                recycleQuietly(ancestor)
            }
            return
        }
        dispatchTapGesture(service, node, nodeId)
    }

    /**
     * Sets the text of a node, preferring ACTION_SET_TEXT and falling back to a
     * clipboard paste.
     *
     * The node is focused first: several input implementations (search bars,
     * WebView inputs, custom IME-driven fields) accept neither ACTION_SET_TEXT
     * nor ACTION_PASTE on an unfocused field, and silently return false.
     *
     * The clipboard fallback borrows the user's clipboard, so it RESTORES the
     * previous primary clip afterwards, in a finally. Leaving the typed value
     * behind would quietly hand it to the next app the user pastes into, and
     * that value is frequently the most sensitive string in the whole session.
     */
    override fun setText(nodeId: String, text: String) {
        val service = requireService()
        val node = resolve(nodeId)
        refreshOrFail(node, nodeId)
        focusQuietly(node)

        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        val direct = try {
            beginEffectStepIfScoped()
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (stopped: DeviceSurfaceStoppedException) {
            throw stopped
        } catch (_: RuntimeException) {
            false
        }
        if (direct) {
            return
        }
        pasteThroughClipboard(service, node, nodeId, text)
    }

    override fun back() {
        performGlobal(AccessibilityService.GLOBAL_ACTION_BACK, "back")
    }

    override fun home() {
        performGlobal(AccessibilityService.GLOBAL_ACTION_HOME, "home")
    }

    /**
     * Swipes from the centre of the display, clamped into the visible area.
     *
     * "up" reveals what is below the fold, so the finger travels upwards. The
     * call waits for the gesture callback: returning as soon as the gesture was
     * accepted would report success for a swipe the system later cancelled, and
     * the caller would act on a screen that never moved.
     */
    override fun swipe(direction: String, distancePx: Int, durationMs: Int) {
        val service = requireService()
        val normalized = normalizeDirection(direction)
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            throw IllegalStateException(
                "The display reports no usable size ($width x $height); cannot swipe."
            )
        }
        val half = distancePx.coerceAtLeast(1) / 2f
        val stepX = when (normalized) {
            "left" -> -1f
            "right" -> 1f
            else -> 0f
        }
        val stepY = when (normalized) {
            "up" -> -1f
            "down" -> 1f
            else -> 0f
        }
        val centreX = width / 2f
        val centreY = height / 2f
        val startX = clampToDisplay(centreX - stepX * half, width)
        val startY = clampToDisplay(centreY - stepY * half, height)
        val endX = clampToDisplay(centreX + stepX * half, width)
        val endY = clampToDisplay(centreY + stepY * half, height)
        if (startX == endX && startY == endY) {
            throw IllegalStateException(
                "A $normalized swipe of ${distancePx}px collapses to a single point on a " +
                    "$width x $height display; ask for a shorter distance or another direction."
            )
        }
        val duration = durationMs.toLong().coerceIn(MIN_GESTURE_MILLIS, MAX_GESTURE_MILLIS)
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        dispatchAndAwait(service, strokeGesture(path, duration), duration, "$normalized swipe")
    }

    /**
     * Looks for [text], scrolling at most [maxScrolls] times.
     *
     * Checks visibility BEFORE scrolling (the cheapest correct answer is often
     * "it is already on screen") and stops early once a scroll no longer
     * changes the screen, so the end of a short list does not burn the whole
     * budget on gestures that do nothing.
     */
    override fun scrollToText(text: String, direction: String, maxScrolls: Int): Boolean {
        requireService()
        val needle = compact(text)
        if (needle.isEmpty()) {
            return false
        }
        var screen = snapshot()
        if (containsText(screen, needle)) {
            return true
        }
        var previousSignature = signatureOf(screen)
        repeat(maxScrolls.coerceAtLeast(1)) {
            beginEffectStepIfScoped()
            swipe(direction, SCROLL_DISTANCE_PX, SCROLL_DURATION_MILLIS)
            waitForStable(SCROLL_SETTLE_MILLIS)
            screen = snapshot()
            if (containsText(screen, needle)) {
                return true
            }
            val signature = signatureOf(screen)
            if (signature == previousSignature) {
                return false
            }
            previousSignature = signature
        }
        return false
    }

    /**
     * Blocks until no relevant accessibility event has arrived for a quiet
     * window, or until [timeoutMs] elapses.
     *
     * Event-driven rather than "sleep and hope": the service records when the
     * last window-state, window-content, scroll or focus event arrived, and the
     * screen counts as settled once that timestamp is [quietWindowMs] old. When
     * no event ever arrives — the common case for an already-idle screen — the
     * quiet window is measured from the start of the wait, so this returns true
     * after one quiet window instead of blocking for the whole timeout.
     */
    override fun waitForStable(timeoutMs: Long): Boolean {
        val service = requireService()
        val budget = timeoutMs.coerceAtLeast(0L)
        val quiet = quietWindowMs.coerceIn(MIN_QUIET_WINDOW_MS, MAX_QUIET_WINDOW_MS)
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + budget
        while (true) {
            beginEffectStepIfScoped()
            val now = SystemClock.uptimeMillis()
            val lastEvent = service.lastRelevantEventUptimeMillis()
            val reference = if (lastEvent in 1..now) lastEvent else startedAt
            val quietFor = now - reference
            if (quietFor >= quiet) {
                return true
            }
            if (now >= deadline) {
                return false
            }
            val sleepFor = minOf(POLL_INTERVAL_MILLIS, deadline - now, quiet - quietFor)
            if (!sleepQuietly(sleepFor.coerceAtLeast(1L))) {
                return false
            }
        }
    }

    /**
     * Brings an app to the foreground by package or display name and returns the
     * package that actually ended up in front.
     *
     * Matching is scored (exact package, exact label, label contains, package
     * contains) over the launchable packages, and the launch is CONFIRMED by
     * polling the foreground: startActivity succeeds long before the app is
     * visible, and a launch that lands on a permission screen, a disambiguation
     * dialog or nothing at all must not read as success.
     */
    override fun launchApp(nameOrPackage: String): String {
        val service = requireService()
        val query = nameOrPackage.trim()
        if (query.isEmpty()) {
            throw DeviceActionException(
                DeviceErrorType.APP_NOT_FOUND,
                "No app name or package was given to launch."
            )
        }
        val manager = service.packageManager
        val launchable = manager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )
        val best = launchable
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = try {
                    info.loadLabel(manager)?.toString().orEmpty()
                } catch (_: RuntimeException) {
                    ""
                }
                val score = scoreMatch(query, packageName, label)
                if (score <= 0) null else Match(packageName, score)
            }
            .sortedWith(
                compareByDescending<Match> { match -> match.score }
                    .thenBy { match -> match.packageName }
            )
            .firstOrNull()
            ?: throw DeviceActionException(
                DeviceErrorType.APP_NOT_FOUND,
                "No installed launchable app matches '$query'."
            )

        val intent = manager.getLaunchIntentForPackage(best.packageName)
            ?: throw DeviceActionException(
                DeviceErrorType.APP_NOT_FOUND,
                "App '${best.packageName}' matches '$query' but has no launch intent, " +
                    "so it cannot be started."
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        beginEffectStepIfScoped()
        try {
            service.startActivity(intent)
        } catch (failed: RuntimeException) {
            throw DeviceActionException(
                DeviceErrorType.ACTION_FAILED,
                "Starting '${best.packageName}' failed: ${failed.message ?: failed.javaClass.simpleName}"
            )
        }

        val deadline = SystemClock.uptimeMillis() + LAUNCH_TIMEOUT_MILLIS
        while (true) {
            beginEffectStepIfScoped()
            val front = foregroundPackageQuietly()
            if (front == best.packageName) {
                return best.packageName
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                throw DeviceActionException(
                    DeviceErrorType.FOREGROUND_TIMEOUT,
                    "'${best.packageName}' was launched for '$query' but '${front ?: "unknown"}' " +
                        "is still in the foreground after ${LAUNCH_TIMEOUT_MILLIS}ms."
                )
            }
            if (!sleepQuietly(LAUNCH_POLL_INTERVAL_MILLIS)) {
                throw DeviceActionException(
                    DeviceErrorType.FOREGROUND_TIMEOUT,
                    "Waiting for '${best.packageName}' was interrupted; the foreground is " +
                        "'${front ?: "unknown"}'."
                )
            }
        }
    }

    // ------------------------------------------------------------- internals

    private class Match(val packageName: String, val score: Int)

    /** exact package > exact label > label contains > package contains. */
    private fun scoreMatch(query: String, packageName: String, label: String): Int {
        val wanted = query.lowercase(Locale.ROOT)
        val pkg = packageName.lowercase(Locale.ROOT)
        val name = label.lowercase(Locale.ROOT)
        return when {
            pkg == wanted -> 4
            name.isNotEmpty() && name == wanted -> 3
            name.isNotEmpty() && name.contains(wanted) -> 2
            pkg.contains(wanted) -> 1
            else -> 0
        }
    }

    private fun requireService(): HarnessAccessibilityService {
        return serviceProvider()
            ?: throw DeviceActionException(
                DeviceErrorType.PERMISSION_NOT_GRANTED,
                "The accessibility service is not connected. Enable it in the system " +
                    "accessibility settings before running device actions."
            )
    }

    private fun resolve(nodeId: String): AccessibilityNodeInfo {
        val screen = lastScreen
            ?: throw IllegalStateException(
                "No snapshot has been taken yet; call snapshot() before acting."
            )
        return nodeById[nodeId]
            ?: throw IllegalArgumentException(
                "Unknown node '$nodeId' on screen '${screen.id}'."
            )
    }

    private fun refreshOrFail(node: AccessibilityNodeInfo, nodeId: String) {
        val fresh = try {
            node.refresh()
        } catch (_: IllegalStateException) {
            false
        }
        if (!fresh) {
            throw IllegalStateException(
                "Node '$nodeId' is stale; take a new snapshot before acting on it."
            )
        }
    }

    private fun performClick(target: AccessibilityNodeInfo, nodeId: String) {
        beginEffectStepIfScoped()
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            throw IllegalStateException(
                "The system refused ACTION_CLICK for node '$nodeId'."
            )
        }
    }

    private fun focusQuietly(node: AccessibilityNodeInfo) {
        try {
            beginEffectStepIfScoped()
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            beginEffectStepIfScoped()
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (stopped: DeviceSurfaceStoppedException) {
            throw stopped
        } catch (_: RuntimeException) {
            // Focus is an optimization; the write attempt below is the real test.
        }
    }

    private fun pasteThroughClipboard(
        service: HarnessAccessibilityService,
        node: AccessibilityNodeInfo,
        nodeId: String,
        text: String
    ) {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException(
                "Node '$nodeId' refused ACTION_SET_TEXT and no clipboard is available to " +
                    "fall back to."
            )
        val previous = onMainEffectThread {
            try {
                clipboard.primaryClip
            } catch (_: RuntimeException) {
                // Reading the clipboard is restricted for background apps; treat as empty.
                null
            }
        }
        try {
            onMainEffectThread {
                clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            }
            val pasted = try {
                beginEffectStepIfScoped()
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (stopped: DeviceSurfaceStoppedException) {
                throw stopped
            } catch (_: RuntimeException) {
                false
            }
            if (!pasted) {
                throw IllegalStateException(
                    "Node '$nodeId' refused both ACTION_SET_TEXT and ACTION_PASTE; it may not " +
                        "be an editable field, or it may be disabled."
                )
            }
        } finally {
            onMainCleanupThread { restoreClipboard(clipboard, previous) }
        }
    }

    private fun restoreClipboard(clipboard: ClipboardManager, previous: ClipData?) {
        try {
            when {
                previous != null -> clipboard.setPrimaryClip(previous)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> clipboard.clearPrimaryClip()
                // Before API 28 there is no clear; an empty clip is the closest
                // available state and still removes the value we wrote.
                else -> clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, ""))
            }
        } catch (_: RuntimeException) {
            // Nothing better to do: the write already happened either way.
        }
    }

    /** Nearest clickable ancestor within [MAX_ANCESTOR_DEPTH], or null. Caller recycles. */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isClickable) {
                return current
            }
            val next = current.parent
            recycleQuietly(current)
            current = next
            depth += 1
        }
        if (current != null) {
            recycleQuietly(current)
        }
        return null
    }

    private fun dispatchTapGesture(
        service: HarnessAccessibilityService,
        node: AccessibilityNodeInfo,
        nodeId: String
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            throw IllegalStateException(
                "Node '$nodeId' has empty screen bounds; cannot tap it by gesture."
            )
        }
        val path = Path()
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY())
        dispatchAndAwait(
            service,
            strokeGesture(path, TAP_DURATION_MILLIS),
            TAP_DURATION_MILLIS,
            "tap on node '$nodeId'"
        )
    }

    private fun strokeGesture(path: Path, durationMs: Long): GestureDescription {
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
    }

    /**
     * Dispatches [gesture] and blocks until the system reports the outcome.
     *
     * The callback is delivered on the main looper, so blocking the main thread
     * here would deadlock; that call is refused with a clear message instead.
     */
    private fun dispatchAndAwait(
        service: HarnessAccessibilityService,
        gesture: GestureDescription,
        durationMs: Long,
        what: String
    ) {
        requireBackgroundThread(what)
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        beginEffectStepIfScoped()
        if (!service.dispatchGesture(gesture, callback, mainHandler)) {
            throw IllegalStateException("The system refused the $what gesture.")
        }
        val deadline = SystemClock.elapsedRealtime() + durationMs + GESTURE_GRACE_MILLIS
        var interrupted = false
        var reported = false
        while (!reported) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            try {
                reported = latch.await(remaining, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // AgentRunHandle.cancel interrupts the worker immediately after
                // the scope fence. Once dispatch won, keep the admitted effect
                // alive until Android reports its terminal callback (or the
                // bounded gesture window expires), then restore interruption.
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        if (!reported) {
            throw IllegalStateException(
                "The $what gesture never reported completion within " +
                    "${durationMs + GESTURE_GRACE_MILLIS}ms."
            )
        }
        if (!completed.get()) {
            throw IllegalStateException("The system cancelled the $what gesture.")
        }
    }

    private fun performGlobal(action: Int, name: String) {
        val service = requireService()
        beginEffectStepIfScoped()
        if (!service.performGlobalAction(action)) {
            throw IllegalStateException("The system refused the global '$name' action.")
        }
    }

    /**
     * Runs a cancellable, scope-owned task on main. Cleanup deliberately uses
     * [onMainThread] instead: once the clipboard was borrowed its restoration
     * must run even after Stop, and quiescence waits for the enclosing effect.
     */
    private fun <T> onMainEffectThread(block: () -> T): T {
        val lease = currentEffectLease.get() ?: return onMainThread(block)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lease.beginEffectStep()
            return block()
        }

        val call = ScopedMainThreadCall(lease, block)
        val ticket = lease.scheduleMainTask(
            post = { admitted ->
                call.ticket = admitted
                mainHandler.post(call)
            },
            cancel = { _, stopped ->
                mainHandler.removeCallbacks(call)
                call.cancel(stopped)
            }
        )
        return call.await(mainHandler, ticket)
    }

    /**
     * Completes an already-required cleanup on main without allowing worker
     * interruption or the ordinary main-task timeout to publish quiescence
     * ahead of the posted callback. Stop itself remains non-blocking because
     * only the admitted effect worker waits here.
     */
    private fun <T> onMainCleanupThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val value = AtomicReference<Any?>(null)
        val failure = AtomicReference<RuntimeException?>(null)
        val latch = CountDownLatch(1)
        check(
            mainHandler.post {
                try {
                    value.set(block())
                } catch (error: RuntimeException) {
                    failure.set(error)
                } finally {
                    latch.countDown()
                }
            }
        ) { "The Android main thread rejected a device-surface cleanup task." }

        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        failure.get()?.let { error -> throw error }
        @Suppress("UNCHECKED_CAST")
        return value.get() as T
    }

    /** Runs [block] on the main looper and blocks until it finished. */
    private fun <T> onMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val value = AtomicReference<Any?>(null)
        val failure = AtomicReference<RuntimeException?>(null)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                value.set(block())
            } catch (error: RuntimeException) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        val finished = try {
            latch.await(MAIN_THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            throw IllegalStateException(
                "The main thread did not run a device-surface task within " +
                    "${MAIN_THREAD_TIMEOUT_MILLIS}ms."
            )
        }
        failure.get()?.let { error -> throw error }
        @Suppress("UNCHECKED_CAST")
        return value.get() as T
    }

    private class ScopedMainThreadCall<T>(
        private val lease: AccessibilityEffectScopeCoordinator.EffectLease,
        private val block: () -> T
    ) : Runnable {
        lateinit var ticket: AccessibilityEffectScopeCoordinator.MainTaskTicket

        private val terminal = AtomicBoolean(false)
        private val value = AtomicReference<Any?>(null)
        private val failure = AtomicReference<RuntimeException?>(null)
        private val latch = CountDownLatch(1)

        override fun run() {
            if (!lease.beginMainTask(ticket)) {
                cancel(
                    lease.stoppedException() ?: IllegalStateException(
                        "A stale device-surface main task was discarded."
                    )
                )
                return
            }
            try {
                value.set(block())
            } catch (error: RuntimeException) {
                failure.set(error)
            } finally {
                lease.finishMainTask(ticket)
                finish()
            }
        }

        fun cancel(error: RuntimeException) {
            failure.compareAndSet(null, error)
            finish()
        }

        fun await(
            handler: Handler,
            admittedTicket: AccessibilityEffectScopeCoordinator.MainTaskTicket
        ): T {
            val finished = try {
                latch.await(MAIN_THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished) {
                handler.removeCallbacks(this)
                lease.abandonMainTask(admittedTicket)
                cancel(
                    IllegalStateException(
                        "The main thread did not run a device-surface task within " +
                            "${MAIN_THREAD_TIMEOUT_MILLIS}ms."
                    )
                )
            }
            failure.get()?.let { error -> throw error }
            @Suppress("UNCHECKED_CAST")
            return value.get() as T
        }

        private fun finish() {
            if (terminal.compareAndSet(false, true)) {
                latch.countDown()
            }
        }
    }

    private fun beginEffectStepIfScoped() {
        currentEffectLease.get()?.beginEffectStep()
    }

    private fun requireBackgroundThread(what: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException(
                "Device actions block until the system reports the outcome, so '$what' cannot " +
                    "run on the main thread; call the harness from a background thread."
            )
        }
    }

    private fun normalizeDirection(direction: String): String {
        val normalized = direction.trim().lowercase(Locale.ROOT)
        if (normalized !in DIRECTIONS) {
            throw DeviceActionException(
                DeviceErrorType.INVALID_ARGUMENT,
                "Unknown swipe direction '$direction'. Use one of: ${DIRECTIONS.joinToString(" | ")}."
            )
        }
        return normalized
    }

    private fun clampToDisplay(value: Float, size: Int): Float {
        val margin = EDGE_MARGIN_PX.toFloat()
        val upper = (size - 1 - EDGE_MARGIN_PX).toFloat()
        if (upper <= margin) {
            return (size / 2).toFloat()
        }
        return value.coerceIn(margin, upper)
    }

    private fun sleepQuietly(millis: Long): Boolean {
        return try {
            Thread.sleep(millis)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun foregroundPackageQuietly(): String? {
        return try {
            foregroundPackage()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun containsText(screen: DeviceScreen, needle: String): Boolean {
        if (compact(screen.title).contains(needle)) {
            return true
        }
        return screen.nodes.any { node ->
            compact(node.label).contains(needle) ||
                node.text?.let { value -> compact(value).contains(needle) } == true ||
                node.viewId?.let { value -> compact(value).contains(needle) } == true
        }
    }

    /** Cheap "did the screen actually move" fingerprint. */
    private fun signatureOf(screen: DeviceScreen): String {
        return buildString {
            append(screen.id)
            screen.nodes.forEach { node ->
                append('|')
                append(node.id)
            }
        }
    }

    private fun compact(value: String): String {
        return value.filterNot { character -> character.isWhitespace() }.lowercase(Locale.ROOT)
    }

    private fun releaseSnapshot() {
        nodeById = emptyMap()
        lastScreen = null
        retained.forEach(::recycleQuietly)
        retained = emptyList()
    }

    @Suppress("DEPRECATION")
    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: RuntimeException) {
            // Already recycled or sealed elsewhere; nothing to release.
        }
    }

    private fun windowTitleOf(root: AccessibilityNodeInfo): String? {
        val window = root.window ?: return null
        return try {
            window.title?.toString()
        } finally {
            recycleWindowQuietly(window)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleWindowQuietly(window: AccessibilityWindowInfo) {
        try {
            window.recycle()
        } catch (_: RuntimeException) {
            // Already recycled; nothing to release.
        }
    }

    companion object {
        /**
         * Quiet period with no accessibility event that counts as "settled".
         *
         * 500ms is long enough to bridge the gap between two frames of a normal
         * transition and short enough that a settled screen is reported quickly.
         * Clamped into [MIN_QUIET_WINDOW_MS]..[MAX_QUIET_WINDOW_MS] so a caller
         * cannot configure a value that either never settles or settles instantly.
         */
        const val DEFAULT_QUIET_WINDOW_MS = 500L

        const val MIN_QUIET_WINDOW_MS = 100L
        const val MAX_QUIET_WINDOW_MS = 5_000L

        private const val TAP_DURATION_MILLIS = 50L
        private const val MIN_GESTURE_MILLIS = 20L
        private const val MAX_GESTURE_MILLIS = 30_000L
        private const val GESTURE_GRACE_MILLIS = 3_000L
        private const val MAIN_THREAD_TIMEOUT_MILLIS = 5_000L
        private const val POLL_INTERVAL_MILLIS = 50L
        private const val MAX_ANCESTOR_DEPTH = 25
        private const val EDGE_MARGIN_PX = 8
        private const val SCROLL_DISTANCE_PX = 600
        private const val SCROLL_DURATION_MILLIS = 300
        private const val SCROLL_SETTLE_MILLIS = 2_000L
        private const val LAUNCH_TIMEOUT_MILLIS = 8_000L
        private const val LAUNCH_POLL_INTERVAL_MILLIS = 150L
        private const val CLIP_LABEL = "agent-harness"

        private val DIRECTIONS = listOf("up", "down", "left", "right")
    }
}
