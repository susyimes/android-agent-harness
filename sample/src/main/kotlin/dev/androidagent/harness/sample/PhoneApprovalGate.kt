// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.deviceloop.ApprovalDecision
import dev.androidagent.harness.deviceloop.ApprovalGate
import dev.androidagent.harness.deviceloop.DeviceNode
import dev.androidagent.harness.deviceloop.android.OverlayApprovalGate

/**
 * Routes a high-risk approval to whichever surface the user can actually see.
 *
 * A device turn drives OTHER apps, so this app's own window is usually behind
 * them: an approval posted there would be invisible and would stall until it
 * timed out. The accessibility overlay draws over whatever is in front, so it
 * is the primary path. The activity dialog is the fallback for the case where
 * the service is not connected — which mostly means phone mode never started.
 *
 * Both delegates fail closed, so an approval that cannot be shown is a denial,
 * never a silent yes.
 */
class PhoneApprovalGate(
    private val overlay: OverlayApprovalGate,
    private val dialog: DialogApprovalGate
) : ApprovalGate {

    override fun decide(
        node: DeviceNode,
        action: String,
        arguments: Map<String, String>
    ): ApprovalDecision {
        val delegate = if (overlay.isAvailable()) overlay else dialog
        return delegate.decide(node, action, arguments)
    }
}
