// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import java.security.MessageDigest

/**
 * Runtime-enforced Phone Use lifecycle.
 *
 * A controller is scoped to one Agent run. It binds every action and finish
 * request to the latest semantic observation and invalidates that binding
 * after an action attempt. This makes observe -> one action -> observe/finish
 * a protocol property instead of prompt advice.
 */
enum class DeviceProtocolState {
    IDLE,
    NEEDS_OBSERVE,
    OBSERVED,
    WAITING_APPROVAL,
    FINISHED,
    STOPPED
}

enum class DeviceProtocolErrorCode {
    NO_OBSERVATION,
    MISSING_SNAPSHOT,
    STALE_SNAPSHOT,
    CONSECUTIVE_ACTION,
    FINISH_WITHOUT_EVIDENCE,
    TERMINAL_STATE
}

data class DeviceObservationBinding(
    val snapshotId: String,
    val screenId: String,
    val observedAtEpochMillis: Long
)

data class DeviceProtocolTransition(
    val from: DeviceProtocolState,
    val to: DeviceProtocolState,
    val reason: String,
    val occurredAtEpochMillis: Long
)

class DeviceProtocolException(
    val code: DeviceProtocolErrorCode,
    message: String
) : IllegalStateException(message)

class StrictDeviceProtocol(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var stateValue = DeviceProtocolState.IDLE
    private var latestValue: DeviceObservationBinding? = null
    private val transitionsValue = mutableListOf<DeviceProtocolTransition>()

    @Synchronized
    fun state(): DeviceProtocolState = stateValue

    @Synchronized
    fun latestObservation(): DeviceObservationBinding? = latestValue

    @Synchronized
    fun transitions(): List<DeviceProtocolTransition> = transitionsValue.toList()

    @Synchronized
    fun recordObservation(screen: DeviceScreen): DeviceObservationBinding {
        ensureNotTerminal()
        if (stateValue == DeviceProtocolState.IDLE) {
            transition(DeviceProtocolState.NEEDS_OBSERVE, "phone-use-started")
        }
        val binding = DeviceObservationBinding(
            snapshotId = snapshotBinding(screen),
            screenId = screen.id,
            observedAtEpochMillis = clock()
        )
        latestValue = binding
        transition(DeviceProtocolState.OBSERVED, "snapshot:${binding.snapshotId}")
        return binding
    }

    /**
     * Validates the latest observation immediately before an action.
     *
     * The caller must invoke [recordActionAttempt] after the validation
     * succeeds, even when the surface reports a failure: an uncertain action
     * attempt invalidates positional node ids and must be followed by observe.
     */
    @Synchronized
    fun requireAction(snapshotId: String?, currentScreen: DeviceScreen) {
        ensureNotTerminal()
        when (stateValue) {
            DeviceProtocolState.IDLE ->
                throw violation(
                    DeviceProtocolErrorCode.NO_OBSERVATION,
                    "Call device_observe before device_act."
                )
            DeviceProtocolState.NEEDS_OBSERVE ->
                throw violation(
                    DeviceProtocolErrorCode.CONSECUTIVE_ACTION,
                    "The previous action invalidated node ids; call device_observe again."
                )
            DeviceProtocolState.WAITING_APPROVAL ->
                throw violation(
                    DeviceProtocolErrorCode.CONSECUTIVE_ACTION,
                    "An exact action is awaiting approval; do not issue another action."
                )
            DeviceProtocolState.OBSERVED -> Unit
            DeviceProtocolState.FINISHED,
            DeviceProtocolState.STOPPED -> ensureNotTerminal()
        }
        val expected = requireSnapshot(snapshotId)
        val current = snapshotBinding(currentScreen)
        if (expected.snapshotId != current) {
            throw violation(
                DeviceProtocolErrorCode.STALE_SNAPSHOT,
                "Observed snapshot '${expected.snapshotId}' changed to '$current'; observe again."
            )
        }
    }

    @Synchronized
    fun recordActionAttempt(reason: String) {
        ensureNotTerminal()
        check(stateValue == DeviceProtocolState.OBSERVED) {
            "An action attempt can only follow a validated observation."
        }
        latestValue = null
        transition(DeviceProtocolState.NEEDS_OBSERVE, reason.ifBlank { "device-action" })
    }

    @Synchronized
    fun recordWaitingApproval(reason: String) {
        ensureNotTerminal()
        check(stateValue == DeviceProtocolState.OBSERVED) {
            "Approval can only be requested for an observed action."
        }
        transition(DeviceProtocolState.WAITING_APPROVAL, reason.ifBlank { "approval-required" })
    }

    @Synchronized
    fun resumeObservedAfterApprovalDecision() {
        ensureNotTerminal()
        check(stateValue == DeviceProtocolState.WAITING_APPROVAL) {
            "No Phone Use action is awaiting approval."
        }
        transition(DeviceProtocolState.OBSERVED, "approval-resolved-without-effect")
    }

    @Synchronized
    fun requireFinish(
        snapshotId: String?,
        currentScreen: DeviceScreen,
        evidenceVisible: Boolean
    ) {
        ensureNotTerminal()
        if (stateValue != DeviceProtocolState.OBSERVED) {
            val code = if (stateValue == DeviceProtocolState.IDLE) {
                DeviceProtocolErrorCode.NO_OBSERVATION
            } else {
                DeviceProtocolErrorCode.CONSECUTIVE_ACTION
            }
            throw violation(code, "Finish requires a fresh device_observe after the last action.")
        }
        val expected = requireSnapshot(snapshotId)
        val current = snapshotBinding(currentScreen)
        if (expected.snapshotId != current) {
            throw violation(
                DeviceProtocolErrorCode.STALE_SNAPSHOT,
                "Finish snapshot '${expected.snapshotId}' is stale; current snapshot is '$current'."
            )
        }
        if (!evidenceVisible) {
            throw violation(
                DeviceProtocolErrorCode.FINISH_WITHOUT_EVIDENCE,
                "Finish evidence is not visible in the referenced observation."
            )
        }
    }

    @Synchronized
    fun recordFinished() {
        ensureNotTerminal()
        transition(DeviceProtocolState.FINISHED, "finish-evidence-verified")
    }

    @Synchronized
    fun stop(reason: String = "host-stop") {
        if (stateValue == DeviceProtocolState.STOPPED) return
        if (stateValue == DeviceProtocolState.FINISHED) {
            throw violation(
                DeviceProtocolErrorCode.TERMINAL_STATE,
                "A finished Phone Use run cannot be stopped."
            )
        }
        latestValue = null
        transition(DeviceProtocolState.STOPPED, reason.ifBlank { "host-stop" })
    }

    private fun requireSnapshot(snapshotId: String?): DeviceObservationBinding {
        if (snapshotId.isNullOrBlank()) {
            throw violation(
                DeviceProtocolErrorCode.MISSING_SNAPSHOT,
                "Pass snapshot_id exactly as returned by the latest device_observe."
            )
        }
        val latest = latestValue ?: throw violation(
            DeviceProtocolErrorCode.NO_OBSERVATION,
            "No current observation is available; call device_observe."
        )
        if (snapshotId.trim() != latest.snapshotId) {
            throw violation(
                DeviceProtocolErrorCode.STALE_SNAPSHOT,
                "snapshot_id '${snapshotId.trim()}' does not match latest '${latest.snapshotId}'."
            )
        }
        return latest
    }

    private fun ensureNotTerminal() {
        if (stateValue == DeviceProtocolState.FINISHED ||
            stateValue == DeviceProtocolState.STOPPED
        ) {
            throw violation(
                DeviceProtocolErrorCode.TERMINAL_STATE,
                "Phone Use is already ${stateValue.name.lowercase()}."
            )
        }
    }

    private fun violation(
        code: DeviceProtocolErrorCode,
        message: String
    ): DeviceProtocolException = DeviceProtocolException(code, message)

    private fun transition(next: DeviceProtocolState, reason: String) {
        val previous = stateValue
        stateValue = next
        transitionsValue += DeviceProtocolTransition(
            from = previous,
            to = next,
            reason = reason,
            occurredAtEpochMillis = clock()
        )
    }
}

/** Stable content binding; a changed title, node, label or value invalidates ids. */
fun snapshotBinding(screen: DeviceScreen): String {
    val canonical = buildString {
        append(screen.id.length).append(':').append(screen.id)
        append('|').append(screen.title.length).append(':').append(screen.title)
        screen.nodes.forEach { node ->
            append('|').append(node.id.length).append(':').append(node.id)
            append('|').append(node.role.length).append(':').append(node.role)
            append('|').append(node.label.length).append(':').append(node.label)
            append('|').append(node.text.orEmpty().length).append(':').append(node.text.orEmpty())
            append('|').append(node.viewId.orEmpty().length).append(':').append(node.viewId.orEmpty())
            append('|').append(node.clickable)
            append('|').append(node.editable)
            append('|').append(node.enabled)
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(SNAPSHOT_ID_CHARS)
}

private const val SNAPSHOT_ID_CHARS = 24
