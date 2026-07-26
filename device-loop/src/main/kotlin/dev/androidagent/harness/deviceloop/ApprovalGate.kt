// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

/**
 * Outcome of the approval boundary for one high-risk action.
 *
 * A boolean cannot express the difference that matters most to the model: a
 * refusal is a decision it must respect, while a timeout is an absence of a
 * decision it must report rather than route around. Both must stop the action;
 * only the follow-up guidance differs.
 */
enum class ApprovalDecision {
    /** A decision outside the model's control allowed exactly this action. */
    APPROVED,

    /** A human refused. The action must not be retried. */
    DENIED,

    /** Nobody answered in time. The situation must be reported, not retried. */
    TIMEOUT
}

/** Non-approved decisions are the [DeviceErrorType.NEEDS_CONFIRMATION] outcome. */
fun ApprovalDecision.errorType(): DeviceErrorType? {
    return if (this == ApprovalDecision.APPROVED) null else DeviceErrorType.NEEDS_CONFIRMATION
}

/**
 * SECURITY BOUNDARY: decides whether a high-risk device action may execute.
 *
 * When [RiskPolicy] flags a target, [DeviceActTool] consults this gate before
 * touching the device. Unless the gate answers [ApprovalDecision.APPROVED] the
 * action does not execute and the tool returns the gate's [pauseMessage]
 * instead — a non-error result, because a pause is a governed outcome and not a
 * malfunction.
 *
 * In any real deployment the gate MUST be backed by a human decision (for
 * example a confirmation dialog), never by a model-supplied argument: the model
 * authors the tool-call arguments, so a gate that trusts an argument lets the
 * model approve its own dangerous action. [ArgumentApprovalGate] does trust an
 * argument and is therefore suitable ONLY for scripted demos and tests.
 *
 * Implementations must be conservative: return [ApprovalDecision.APPROVED] only
 * when a decision genuinely outside the model's control approved exactly this
 * action, and prefer [ApprovalDecision.DENIED] for every ambiguous path.
 */
fun interface ApprovalGate {
    /**
     * Decides the pending [action] ("tap" or "set_text") on [node]; the
     * tool-call [arguments] are provided for display only.
     */
    fun decide(node: DeviceNode, action: String, arguments: Map<String, String>): ApprovalDecision

    /**
     * Message handed back to the model when [decision] is not
     * [ApprovalDecision.APPROVED].
     *
     * The gate contributes it because only the gate knows what a retry would
     * mean. A scripted gate may legitimately invite a confirmed retry; a
     * human-backed gate must never do that, since re-asking a person who
     * already said no (or who did not answer) is how agents nag their way to an
     * approval. The default implementation is the human-backed wording.
     */
    fun pauseMessage(node: DeviceNode, action: String, decision: ApprovalDecision): String {
        return defaultPauseMessage(node, decision)
    }

    companion object {
        /** Decision-specific guidance that never encourages a retry. */
        fun defaultPauseMessage(node: DeviceNode, decision: ApprovalDecision): String {
            return when (decision) {
                ApprovalDecision.DENIED ->
                    "DENIED_BY_USER: '${node.label}' was refused on screen. " +
                        "Do not retry this action; choose another approach or call device_finish."

                ApprovalDecision.TIMEOUT ->
                    "APPROVAL_TIMEOUT: '${node.label}' was not approved in time. " +
                        "Do not retry immediately; report the situation to the user."

                ApprovalDecision.APPROVED ->
                    "APPROVED: '${node.label}' was approved; no pause message applies."
            }
        }
    }
}

/**
 * Demo-only gate: [ApprovalDecision.APPROVED] when the tool-call argument
 * confirmed=true is present, [ApprovalDecision.DENIED] otherwise.
 *
 * SECURITY WARNING: the model itself supplies that argument, so this gate does
 * NOT represent a human decision — a model can trivially approve its own
 * high-risk action by adding confirmed=true to the call. Suitable ONLY for
 * scripted demos and deterministic tests where the "approval" is part of the
 * script. Real deployments must replace it with a gate backed by an actual user
 * decision, such as a blocking confirmation dialog.
 *
 * Because a confirmed retry is exactly the scripted protocol here, this gate
 * overrides [pauseMessage] with the PAUSED_HIGH_RISK wording that invites one.
 * That wording belongs to scripted gates only; human-backed gates keep the
 * default decision-specific messages.
 */
object ArgumentApprovalGate : ApprovalGate {
    override fun decide(
        node: DeviceNode,
        action: String,
        arguments: Map<String, String>
    ): ApprovalDecision {
        return if (arguments["confirmed"] == "true") {
            ApprovalDecision.APPROVED
        } else {
            ApprovalDecision.DENIED
        }
    }

    override fun pauseMessage(
        node: DeviceNode,
        action: String,
        decision: ApprovalDecision
    ): String {
        if (decision == ApprovalDecision.APPROVED) {
            return ApprovalGate.defaultPauseMessage(node, decision)
        }
        return "PAUSED_HIGH_RISK: '${node.label}' requires explicit user confirmation. " +
            "Re-invoke with confirmed=true after the user approves."
    }
}
