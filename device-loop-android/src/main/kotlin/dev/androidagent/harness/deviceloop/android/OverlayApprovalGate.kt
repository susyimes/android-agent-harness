// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.harness.deviceloop.ApprovalDecision
import dev.androidagent.harness.deviceloop.ApprovalGate
import dev.androidagent.harness.deviceloop.DeviceNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Human-backed [ApprovalGate] that asks on a SYSTEM overlay owned by the
 * accessibility service, not on the host app's screen.
 *
 * WHY IT CANNOT LIVE ON AN ACTIVITY. The whole point of the device loop is that
 * the agent operates OTHER apps. An approval dialog hosted by the harness app's
 * Activity is behind the app being driven the moment the loop leaves home: the
 * agent is about to tap "Transfer" in a banking app, the confirmation is posted
 * to an Activity nobody can see, and the operator either never answers (the
 * action stalls) or blind-taps whatever is on screen. An overlay added through
 * the service's own WindowManager with TYPE_ACCESSIBILITY_OVERLAY is drawn over
 * whatever app is in front, needs no SYSTEM_ALERT_WINDOW permission, and is
 * tied to the same service that is performing the actions — if the service is
 * gone, so is the ability to act.
 *
 * DECISIONS. [ApprovalDecision.APPROVED] only ever comes from a real tap on
 * Allow. Every other path is conservative:
 * - Deny tapped                        -> [ApprovalDecision.DENIED]
 * - nobody answered within the timeout -> [ApprovalDecision.TIMEOUT]
 * - service not connected              -> [ApprovalDecision.DENIED]
 * - the overlay could not be added     -> [ApprovalDecision.DENIED]
 * - called on the main thread          -> [ApprovalDecision.DENIED]
 * The last one matters: this call blocks, and blocking the main thread would
 * freeze the very UI the decision is displayed on, so it refuses instead of
 * deadlocking. The overlay is always removed in a finally, including on
 * timeout and on interruption.
 *
 * SECURITY: the model-supplied tool-call arguments (including any `confirmed`
 * argument) are never consulted. The model authors those arguments, so trusting
 * them would let it approve its own high-risk action; the arguments are shown
 * to the human and nothing else.
 *
 * [pauseMessage] is deliberately NOT overridden: the default human-backed
 * wording distinguishes "the user refused" from "nobody answered" and never
 * invites a retry, which is exactly right for a gate backed by a person.
 */
class OverlayApprovalGate(
    private val serviceProvider: () -> HarnessAccessibilityService? = {
        HarnessAccessibilityService.connectedInstance()
    },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onWaitingChanged: (Boolean) -> Unit = {}
) : ApprovalGate {

    /**
     * True when an approval can actually be shown right now.
     *
     * A host app should check this before starting a device session: a gate
     * that cannot ask denies everything, which looks like a broken agent rather
     * than a missing permission.
     */
    fun isAvailable(): Boolean = serviceProvider() != null

    override fun decide(
        node: DeviceNode,
        action: String,
        arguments: Map<String, String>
    ): ApprovalDecision {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ApprovalDecision.DENIED
        }
        val service = serviceProvider() ?: return ApprovalDecision.DENIED
        val budget = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val handler = Handler(Looper.getMainLooper())
        val decision = AtomicReference<ApprovalDecision?>(null)
        val overlay = AtomicReference<View?>(null)
        val ticker = AtomicReference<Runnable?>(null)
        val latch = CountDownLatch(1)

        onWaitingChanged(true)
        try {
            handler.post {
                try {
                    val windowManager =
                        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val view = buildOverlay(
                        service = service,
                        node = node,
                        action = action,
                        arguments = arguments,
                        deadlineUptimeMs = SystemClock.uptimeMillis() + budget,
                        handler = handler,
                        ticker = ticker,
                        decision = decision,
                        latch = latch
                    )
                    windowManager.addView(view, overlayLayoutParams())
                    overlay.set(view)
                } catch (_: RuntimeException) {
                    // No overlay means no informed human: fail closed.
                    latch.countDown()
                }
            }
            val answered = try {
                latch.await(budget, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!answered) {
                decision.compareAndSet(null, ApprovalDecision.TIMEOUT)
            }
            return decision.get() ?: ApprovalDecision.DENIED
        } finally {
            handler.post {
                ticker.getAndSet(null)?.let { pending -> handler.removeCallbacks(pending) }
                overlay.getAndSet(null)?.let { view -> removeQuietly(service, view) }
            }
            onWaitingChanged(false)
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildOverlay(
        service: HarnessAccessibilityService,
        node: DeviceNode,
        action: String,
        arguments: Map<String, String>,
        deadlineUptimeMs: Long,
        handler: Handler,
        ticker: AtomicReference<Runnable?>,
        decision: AtomicReference<ApprovalDecision?>,
        latch: CountDownLatch
    ): View {
        val density = service.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND_COLOR)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        root.addView(
            TextView(service).apply {
                text = service.getString(R.string.harness_overlay_title)
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        root.addView(
            TextView(service).apply {
                text = describeAction(service, node, action)
                textSize = 15f
                setTextColor(TARGET_COLOR)
                setPadding(0, dp(8), 0, 0)
            }
        )
        arguments["text"]?.let { value ->
            root.addView(
                TextView(service).apply {
                    text = service.getString(
                        R.string.harness_overlay_value,
                        abbreviate(value)
                    )
                    textSize = 13f
                    setTextColor(DETAIL_COLOR)
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
        node.viewId?.let { viewId ->
            root.addView(
                TextView(service).apply {
                    text = service.getString(R.string.harness_overlay_view_id, viewId)
                    textSize = 12f
                    setTextColor(DETAIL_COLOR)
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }

        val countdown = TextView(service).apply {
            textSize = 13f
            setTextColor(COUNTDOWN_COLOR)
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(countdown)

        val buttons = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            Button(service).apply {
                text = service.getString(R.string.harness_overlay_deny)
                setOnClickListener {
                    decision.compareAndSet(null, ApprovalDecision.DENIED)
                    latch.countDown()
                }
            },
            weightedParams()
        )
        buttons.addView(
            Button(service).apply {
                text = service.getString(R.string.harness_overlay_allow)
                setOnClickListener {
                    decision.compareAndSet(null, ApprovalDecision.APPROVED)
                    latch.countDown()
                }
            },
            weightedParams()
        )
        root.addView(buttons)

        startCountdown(service, countdown, deadlineUptimeMs, handler, ticker)
        return root
    }

    /** Visible countdown, so "nobody answered" is never a surprise. */
    private fun startCountdown(
        service: HarnessAccessibilityService,
        view: TextView,
        deadlineUptimeMs: Long,
        handler: Handler,
        ticker: AtomicReference<Runnable?>
    ) {
        val tick = object : Runnable {
            override fun run() {
                val remaining = deadlineUptimeMs - SystemClock.uptimeMillis()
                val seconds = ((remaining + 999L) / 1_000L).coerceAtLeast(0L)
                view.text = service.getString(
                    R.string.harness_overlay_countdown,
                    seconds.toInt()
                )
                if (remaining > 0L) {
                    handler.postDelayed(this, TICK_INTERVAL_MS)
                }
            }
        }
        ticker.set(tick)
        tick.run()
    }

    private fun describeAction(
        service: HarnessAccessibilityService,
        node: DeviceNode,
        action: String
    ): String {
        val target = abbreviate(node.label)
        return when (action) {
            "tap" -> service.getString(R.string.harness_overlay_action_tap, target)
            "set_text" -> service.getString(R.string.harness_overlay_action_set_text, target)
            else -> service.getString(R.string.harness_overlay_action_other, action, target)
        }
    }

    private fun weightedParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun overlayLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable so the overlay never steals keyboard focus from the
            // app being driven; not touch-modal so only taps on the panel itself
            // are consumed. Buttons still receive their touches.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
    }

    private fun removeQuietly(service: HarnessAccessibilityService, view: View) {
        try {
            val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeViewImmediate(view)
        } catch (_: RuntimeException) {
            // Already gone (service torn down under us); nothing left to remove.
        }
    }

    private fun abbreviate(value: String): String {
        val singleLine = value.replace(WHITESPACE, " ").trim()
        return if (singleLine.length <= MAX_DISPLAY_CHARS) {
            singleLine
        } else {
            singleLine.take(MAX_DISPLAY_CHARS - 3) + "..."
        }
    }

    companion object {
        /** Default time a human gets to answer before the action counts as unapproved. */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** Clamp bounds: too short is not a real question, too long stalls the session. */
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 300_000L

        private const val TICK_INTERVAL_MS = 1_000L
        private const val MAX_DISPLAY_CHARS = 120

        private val BACKGROUND_COLOR: Int = 0xF01B1B1B.toInt()
        private val TARGET_COLOR: Int = 0xFFFFFFFF.toInt()
        private val DETAIL_COLOR: Int = 0xFFBDBDBD.toInt()
        private val COUNTDOWN_COLOR: Int = 0xFFFFB74D.toInt()

        private val WHITESPACE = Regex("\\s+")
    }
}
