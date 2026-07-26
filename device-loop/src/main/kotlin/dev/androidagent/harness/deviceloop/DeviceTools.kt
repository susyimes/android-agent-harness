// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec

/** Renders the current fake device screen as deterministic semantic text. */
class DeviceObserveTool(
    private val device: FakeDevice
) : AgentTool {
    override val spec = AgentToolSpec(
        name = "device_observe",
        description = "Returns a semantic text rendering of the current device screen."
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val screen = device.snapshot()
        val lines = buildList {
            add("screen=${screen.id} title=${screen.title}")
            screen.nodes.forEach { node ->
                val textSuffix = node.text?.let { text -> " (text=$text)" }.orEmpty()
                add("[${node.id}] ${node.role} ${node.label}$textSuffix")
            }
        }
        return AgentToolResult.success(lines.joinToString("\n"))
    }
}

/**
 * Executes exactly one device action: tap or set_text.
 *
 * High-risk nodes (per [RiskPolicy]) are never executed without confirmed=true;
 * instead the tool returns a PAUSED_HIGH_RISK message so the runner can surface
 * the pending action to the user for explicit approval.
 */
class DeviceActTool(
    private val device: FakeDevice,
    private val riskPolicy: RiskPolicy
) : AgentTool {
    override val spec = AgentToolSpec(
        name = "device_act",
        description = "Performs one device action (tap or set_text); high-risk nodes pause for user confirmation.",
        requiredArguments = setOf("action", "node")
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val action = invocation.arguments.getValue("action")
        val nodeId = invocation.arguments.getValue("node")
        if (action != ACTION_TAP && action != ACTION_SET_TEXT) {
            return AgentToolResult.failure(
                "Unsupported action '$action'. Use $ACTION_TAP or $ACTION_SET_TEXT."
            )
        }
        val node = device.snapshot().nodes.firstOrNull { candidate -> candidate.id == nodeId }
            ?: return AgentToolResult.failure(
                "Unknown node '$nodeId' on screen '${device.currentScreenId}'."
            )
        val text = invocation.arguments["text"]
        if (action == ACTION_SET_TEXT && text == null) {
            return AgentToolResult.failure("Action $ACTION_SET_TEXT requires a 'text' argument.")
        }
        val confirmed = invocation.arguments["confirmed"] == "true"
        if (riskPolicy.isHighRisk(node) && !confirmed) {
            return AgentToolResult.success(
                "PAUSED_HIGH_RISK: '${node.label}' requires explicit user confirmation. " +
                    "Re-invoke with confirmed=true after the user approves."
            )
        }
        when (action) {
            ACTION_TAP -> device.tap(nodeId)
            else -> device.setText(nodeId, requireNotNull(text))
        }
        return AgentToolResult.success("OK: $action $nodeId -> screen=${device.currentScreenId}")
    }

    private companion object {
        const val ACTION_TAP = "tap"
        const val ACTION_SET_TEXT = "set_text"
    }
}

/** Declares the device task complete with a short summary. */
class DeviceFinishTool : AgentTool {
    override val spec = AgentToolSpec(
        name = "device_finish",
        description = "Marks the device task as finished with a summary of what was done.",
        requiredArguments = setOf("summary")
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        return AgentToolResult.success("FINISHED: ${invocation.arguments.getValue("summary")}")
    }
}

/**
 * Tool profile for the minimal device-operation loop.
 *
 * Recommended runner configuration: maxToolCallsPerStep = 1, so each provider step is
 * observe, then ONE action, then observe again before deciding on the next action.
 * That keeps every state change attributable to a single reviewed step.
 */
object DeviceLoopProfile {
    fun profile(): AgentToolProfile = AgentToolProfile.only(
        id = "device-loop",
        toolNames = setOf("device_observe", "device_act", "device_finish")
    )
}
