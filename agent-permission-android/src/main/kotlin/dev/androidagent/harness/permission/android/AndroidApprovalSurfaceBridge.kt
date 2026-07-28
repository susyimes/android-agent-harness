// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.permission.android

import android.os.Looper
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import dev.androidagent.harness.approval.AgentApprovalRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun interface AndroidApprovalSurfaceListener {
    fun onPendingApprovalsChanged(pending: List<AgentApprovalRequest>)
}

/**
 * Lifecycle-neutral bridge between a worker-thread approval gate and a visible
 * Activity/notification surface. It never auto-approves and does not persist
 * one-shot decisions across process death.
 */
class AndroidApprovalSurfaceBridge(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : AgentApprovalGate {
    private val pending = ConcurrentHashMap<String, PendingDecision>()
    private val listeners = CopyOnWriteArraySet<AndroidApprovalSurfaceListener>()

    override fun decide(request: AgentApprovalRequest): AgentApprovalDecision {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return AgentApprovalDecision.UNAVAILABLE
        }
        val remaining = request.expiresAtEpochMillis - nowEpochMillis()
        if (remaining <= 0) return AgentApprovalDecision.TIMEOUT
        val decision = PendingDecision(request)
        val previous = pending.putIfAbsent(request.id, decision)
        if (previous != null) return AgentApprovalDecision.UNAVAILABLE
        notifyChanged()
        return try {
            if (!decision.latch.await(remaining, TimeUnit.MILLISECONDS)) {
                AgentApprovalDecision.TIMEOUT
            } else {
                decision.result.get() ?: AgentApprovalDecision.UNAVAILABLE
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            AgentApprovalDecision.UNAVAILABLE
        } finally {
            pending.remove(request.id, decision)
            notifyChanged()
        }
    }

    fun resolve(
        approvalId: String,
        decision: AgentApprovalDecision
    ): Boolean {
        require(decision in USER_DECISIONS) {
            "Visible approval surface may resolve only APPROVED or DENIED."
        }
        val value = pending[approvalId] ?: return false
        if (nowEpochMillis() > value.request.expiresAtEpochMillis) return false
        if (!value.result.compareAndSet(null, decision)) return false
        value.latch.countDown()
        return true
    }

    fun pending(): List<AgentApprovalRequest> =
        pending.values.map(PendingDecision::request)
            .filter { request -> request.expiresAtEpochMillis >= nowEpochMillis() }
            .sortedWith(
                compareBy<AgentApprovalRequest> { request -> request.expiresAtEpochMillis }
                    .thenBy { request -> request.id }
            )

    fun addListener(listener: AndroidApprovalSurfaceListener) {
        listeners += listener
        runCatching { listener.onPendingApprovalsChanged(pending()) }
    }

    fun removeListener(listener: AndroidApprovalSurfaceListener) {
        listeners -= listener
    }

    fun cancelAll() {
        pending.values.forEach { value ->
            if (value.result.compareAndSet(null, AgentApprovalDecision.UNAVAILABLE)) {
                value.latch.countDown()
            }
        }
    }

    private fun notifyChanged() {
        val snapshot = pending()
        listeners.forEach { listener ->
            runCatching { listener.onPendingApprovalsChanged(snapshot) }
        }
    }

    private data class PendingDecision(
        val request: AgentApprovalRequest,
        val result: AtomicReference<AgentApprovalDecision?> = AtomicReference(null),
        val latch: CountDownLatch = CountDownLatch(1)
    )

    private companion object {
        val USER_DECISIONS = setOf(
            AgentApprovalDecision.APPROVED,
            AgentApprovalDecision.DENIED
        )
    }
}
