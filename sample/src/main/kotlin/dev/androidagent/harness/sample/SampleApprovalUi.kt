// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalRequest
import dev.androidagent.harness.permission.android.AndroidApprovalSurfaceListener

/** Exact, human-driven approval surface for generic SDK effects. */
class SampleApprovalUi(
    private val activity: Activity,
    private val onChanged: () -> Unit = {}
) : AndroidApprovalSurfaceListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentApprovalId: String? = null
    private var dialog: AlertDialog? = null

    fun attach() {
        SampleRuntime.approvalBridge().addListener(this)
    }

    fun detach() {
        SampleRuntime.approvalBridge().removeListener(this)
        dialog?.dismiss()
        dialog = null
        currentApprovalId = null
    }

    override fun onPendingApprovalsChanged(pending: List<AgentApprovalRequest>) {
        mainHandler.post {
            if (activity.isDestroyed || activity.isFinishing) return@post
            onChanged()
            val request = pending.firstOrNull()
            if (request == null) {
                dialog?.dismiss()
                dialog = null
                currentApprovalId = null
                return@post
            }
            if (currentApprovalId == request.id && dialog?.isShowing == true) return@post
            dialog?.dismiss()
            currentApprovalId = request.id
            dialog = AlertDialog.Builder(activity)
                .setTitle("需要你的批准")
                .setMessage(
                    buildString {
                        appendLine(request.effectSummary)
                        appendLine()
                        appendLine("能力：${request.capabilityId}")
                        appendLine("风险：${request.risk}")
                        appendLine("目标：${request.targetRef ?: "未指定"}")
                        append("参数绑定：${request.argumentHash.take(16)}…")
                    }
                )
                .setPositiveButton("批准一次") { _, _ ->
                    SampleRuntime.approvalBridge().resolve(
                        request.id,
                        AgentApprovalDecision.APPROVED
                    )
                }
                .setNegativeButton("拒绝") { _, _ ->
                    SampleRuntime.approvalBridge().resolve(
                        request.id,
                        AgentApprovalDecision.DENIED
                    )
                }
                .setOnCancelListener {
                    SampleRuntime.approvalBridge().resolve(
                        request.id,
                        AgentApprovalDecision.DENIED
                    )
                }
                .show()
        }
    }
}
