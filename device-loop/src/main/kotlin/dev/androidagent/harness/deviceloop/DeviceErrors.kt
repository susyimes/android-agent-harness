// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentToolResult

/**
 * Structured reason a device tool could not do what it was asked.
 *
 * Every failure result of [DeviceActTool], [DeviceObserveTool] and
 * [DeviceFinishTool] renders as text whose FIRST token is the constant name,
 * followed by ": " and a human/model readable explanation. Models are far
 * better at recovering from a stable token than from prose, and the token also
 * makes failures groupable in traces and evals.
 *
 * - [PERMISSION_NOT_GRANTED]: the surface cannot act at all until the user
 *   grants something (for example an accessibility service that is not enabled).
 * - [TARGET_NOT_FOUND]: the addressed node (or scrolled-for text) is not on the
 *   current screen. These failures append candidate nodes so the model can
 *   re-target instead of retrying the same id.
 * - [ACTION_FAILED]: the target existed but the action did not go through.
 * - [WAIT_TIMEOUT]: the screen never settled within the requested budget.
 * - [APP_NOT_FOUND]: no installed app matches the requested name or package.
 * - [FOREGROUND_TIMEOUT]: an app was launched or expected, but a different
 *   package owns the foreground.
 * - [NEEDS_CONFIRMATION]: the action stopped at the approval boundary. The act
 *   tool reports this outcome as a NON-error result whose token comes from the
 *   [ApprovalGate] (DENIED_BY_USER, APPROVAL_TIMEOUT or PAUSED_HIGH_RISK), so
 *   the guidance can be decision-specific; see [ApprovalDecision.errorType].
 * - [UNSUPPORTED_ACTION]: this surface (or this tool configuration) does not
 *   offer the requested action.
 * - [INVALID_ARGUMENT]: the tool-call arguments are missing or malformed.
 * - [STALE_TARGET]: the node id still resolves, but it no longer points at what
 *   the caller expected; the screen changed underneath the agent.
 */
enum class DeviceErrorType {
    PERMISSION_NOT_GRANTED,
    TARGET_NOT_FOUND,
    ACTION_FAILED,
    WAIT_TIMEOUT,
    APP_NOT_FOUND,
    FOREGROUND_TIMEOUT,
    NEEDS_CONFIRMATION,
    UNSUPPORTED_ACTION,
    INVALID_ARGUMENT,
    STALE_TARGET,
    PROTOCOL_VIOLATION
}

/**
 * A device-side failure that already knows its [DeviceErrorType].
 *
 * [DeviceSurface] implementations throw this when they can classify a failure
 * better than the tool layer could guess: a missing runtime permission, an app
 * that is not installed, a foreground that never changed. The tools translate
 * it into a failure result with the carried type, so a surface never has to
 * know anything about tool results or prompt wording.
 *
 * Surfaces that do not use it keep the documented plain-exception contract:
 * [IllegalArgumentException] means "unknown node id" ([DeviceErrorType.TARGET_NOT_FOUND])
 * and any other [RuntimeException] means [DeviceErrorType.ACTION_FAILED].
 */
class DeviceActionException(
    val errorType: DeviceErrorType,
    message: String
) : RuntimeException(message)

/** Shared, deterministic rendering of tool failures and screen text. */
internal object DeviceText {

    /** Maximum number of re-targeting candidates appended to a not-found failure. */
    const val MAX_CANDIDATES = 5

    /** "TYPE: message" plus one line per detail. */
    fun failure(
        type: DeviceErrorType,
        message: String,
        details: List<String> = emptyList()
    ): AgentToolResult {
        val body = buildString {
            append(type.name)
            append(": ")
            append(message)
            details.forEach { detail ->
                append("\n")
                append(detail)
            }
        }
        return AgentToolResult.failure(body)
    }

    fun protocolFailure(error: DeviceProtocolException): AgentToolResult {
        return failure(
            DeviceErrorType.PROTOCOL_VIOLATION,
            "[${error.code.name}] ${error.message.orEmpty()}"
        )
    }

    /**
     * TARGET_NOT_FOUND with up to [MAX_CANDIDATES] re-targeting candidates.
     *
     * Candidates are the nodes the model could actually act on (clickable or
     * editable) in screen order, topped up with the remaining nodes in screen
     * order when fewer than [MAX_CANDIDATES] are interactive. Retrying a dead
     * node id is the most common agent loop; naming live alternatives is what
     * breaks it.
     */
    fun targetNotFound(message: String, screen: DeviceScreen?): AgentToolResult {
        val candidates = screen?.let(::candidateLines).orEmpty()
        val details = if (candidates.isEmpty()) emptyList() else listOf("candidates:") + candidates
        return failure(DeviceErrorType.TARGET_NOT_FOUND, message, details)
    }

    fun candidateLines(screen: DeviceScreen): List<String> {
        val interactive = screen.nodes.filter { node -> node.clickable || node.editable }
        val rest = screen.nodes.filterNot { node -> node.clickable || node.editable }
        return (interactive + rest)
            .take(MAX_CANDIDATES)
            .map { node -> "[${node.id}] ${node.role} ${node.label}" }
    }

    /** Deterministic semantic rendering of one screen, one node per line. */
    fun renderScreen(screen: DeviceScreen): String {
        val lines = buildList {
            add("screen=${screen.id} title=${screen.title}")
            screen.nodes.forEach { node -> add(renderNode(node)) }
        }
        return lines.joinToString("\n")
    }

    /** "[id] role label" plus the optional signals the model can act on. */
    fun renderNode(node: DeviceNode): String {
        return buildString {
            append("[${node.id}] ${node.role} ${node.label}")
            node.text?.let { text -> append(" (text=$text)") }
            node.viewId?.let { viewId -> append(" (view_id=$viewId)") }
            if (!node.enabled) {
                append(" [disabled]")
            }
        }
    }
}
