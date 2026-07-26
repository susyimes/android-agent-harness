// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import dev.androidagent.harness.deviceloop.ApprovalDecision
import dev.androidagent.harness.deviceloop.ApprovalGate
import dev.androidagent.harness.deviceloop.DeviceNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Human-backed [ApprovalGate]: posts a modal confirmation dialog to the main
 * thread and blocks the calling background turn until the user answers.
 *
 * Every non-approval path resolves conservatively: an explicit Deny, a dismissed
 * dialog, a destroyed activity, an interrupted worker, or a call that arrives on
 * the main thread (denied rather than deadlocking the UI) all return
 * [ApprovalDecision.DENIED]. Only the await expiring returns
 * [ApprovalDecision.TIMEOUT], which the tool layer reports differently — "the
 * user refused" and "the user never saw it" call for different follow-ups.
 *
 * [cancelPending] must be called when the hosting activity goes away, otherwise
 * a destroyed activity leaves the worker parked until the timeout elapses.
 *
 * SECURITY: the model-supplied tool-call arguments — including any `confirmed`
 * argument — are ignored entirely. The model authors those arguments, so
 * trusting them would let it approve its own high-risk action. Only a real tap
 * on Allow approves.
 */
class DialogApprovalGate(
    private val activity: Activity,
    private val mainHandler: Handler,
    private val onWaitingChanged: (Boolean) -> Unit = {},
    private val timeoutSeconds: Long = 90L
) : ApprovalGate {

    private class Prompt(
        val latch: CountDownLatch,
        val outcome: AtomicReference<ApprovalDecision>
    ) {
        val dialog = AtomicReference<AlertDialog?>(null)
    }

    private val pending = AtomicReference<Prompt?>(null)

    override fun decide(
        node: DeviceNode,
        action: String,
        arguments: Map<String, String>
    ): ApprovalDecision {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Blocking here would freeze the UI thread the dialog needs.
            return ApprovalDecision.DENIED
        }
        val prompt = Prompt(CountDownLatch(1), AtomicReference(ApprovalDecision.DENIED))
        pending.set(prompt)
        onWaitingChanged(true)
        try {
            mainHandler.post { showPrompt(prompt, node, action, arguments) }
            val answered = try {
                prompt.latch.await(timeoutSeconds, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return ApprovalDecision.DENIED
            }
            return if (answered) prompt.outcome.get() else ApprovalDecision.TIMEOUT
        } finally {
            pending.compareAndSet(prompt, null)
            mainHandler.post { dismiss(prompt) }
            onWaitingChanged(false)
        }
    }

    /**
     * Resolves any prompt still on screen as [ApprovalDecision.DENIED]. Safe to
     * call from the main thread at any time; a no-op when nothing is pending.
     */
    fun cancelPending() {
        val prompt = pending.getAndSet(null) ?: return
        prompt.outcome.set(ApprovalDecision.DENIED)
        prompt.latch.countDown()
        dismiss(prompt)
    }

    private fun showPrompt(
        prompt: Prompt,
        node: DeviceNode,
        action: String,
        arguments: Map<String, String>
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            prompt.latch.countDown()
            return
        }
        val textArgument = arguments["text"]
        val actionSummary = if (action == "set_text" && textArgument != null) {
            activity.getString(R.string.approval_action_set_text, textArgument)
        } else {
            activity.getString(R.string.approval_action_tap)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.approval_title)
            .setMessage(activity.getString(R.string.approval_message, actionSummary, node.label))
            .setCancelable(false)
            .setPositiveButton(R.string.btn_allow) { _, _ ->
                prompt.outcome.set(ApprovalDecision.APPROVED)
                prompt.latch.countDown()
            }
            .setNegativeButton(R.string.btn_deny) { _, _ ->
                prompt.outcome.set(ApprovalDecision.DENIED)
                prompt.latch.countDown()
            }
            .create()
        // Covers dismissal paths that never reach a button: activity teardown,
        // cancelPending, or the window being torn down under us.
        dialog.setOnDismissListener { prompt.latch.countDown() }
        dialog.show()
        prompt.dialog.set(dialog)
    }

    private fun dismiss(prompt: Prompt) {
        val dialog = prompt.dialog.getAndSet(null) ?: return
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }
}
