// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalGateTest {

    private fun newDevice(): FakeDevice {
        return FakeDevice(
            screens = listOf(
                DeviceScreen(
                    id = "home",
                    title = "Home",
                    nodes = listOf(
                        DeviceNode("amount_field", "input", "Amount", text = "42"),
                        DeviceNode("pay_button", "button", "Pay")
                    )
                ),
                DeviceScreen(
                    id = "pay_confirm",
                    title = "Confirm payment",
                    nodes = listOf(DeviceNode("confirm_button", "button", "Confirm"))
                )
            ),
            startScreenId = "home",
            transitions = mapOf(("home" to "pay_button") to "pay_confirm")
        )
    }

    private fun act(tool: DeviceActTool, vararg arguments: Pair<String, String>): AgentToolResult {
        return tool.execute(
            AgentToolInvocation(
                callId = "call-1",
                sessionId = "gate-session",
                arguments = mapOf(*arguments)
            )
        )
    }

    private fun toolWith(device: FakeDevice, gate: ApprovalGate): DeviceActTool {
        return DeviceActTool(
            surface = device,
            riskPolicy = RiskPolicy(highRiskNodeIds = setOf("pay_button")),
            approvalGate = gate
        )
    }

    @Test
    fun argumentGateApprovesOnlyOnConfirmedTrue() {
        val node = DeviceNode("pay_button", "button", "Pay")

        assertEquals(
            ApprovalDecision.APPROVED,
            ArgumentApprovalGate.decide(node, "tap", mapOf("confirmed" to "true"))
        )
        assertEquals(ApprovalDecision.DENIED, ArgumentApprovalGate.decide(node, "tap", emptyMap()))
        assertEquals(
            ApprovalDecision.DENIED,
            ArgumentApprovalGate.decide(node, "tap", mapOf("confirmed" to "TRUE"))
        )
        assertEquals(
            ApprovalDecision.DENIED,
            ArgumentApprovalGate.decide(node, "tap", mapOf("confirmed" to "yes"))
        )
    }

    @Test
    fun scriptedGateKeepsThePausedHighRiskWordingThatInvitesAConfirmedRetry() {
        val device = newDevice()

        val paused = act(toolWith(device, ArgumentApprovalGate), "action" to "tap", "node" to "pay_button")

        assertFalse(paused.isError)
        assertEquals(
            "PAUSED_HIGH_RISK: 'Pay' requires explicit user confirmation. " +
                "Re-invoke with confirmed=true after the user approves.",
            paused.content
        )
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun humanBackedDenialTellsTheModelNotToRetry() {
        val device = newDevice()
        val gate = ApprovalGate { _, _, _ -> ApprovalDecision.DENIED }

        val denied = act(toolWith(device, gate), "action" to "tap", "node" to "pay_button")

        assertFalse(denied.isError)
        assertEquals(
            "DENIED_BY_USER: 'Pay' was refused on screen. Do not retry this action; " +
                "choose another approach or call device_finish.",
            denied.content
        )
        assertFalse(denied.content.contains("confirmed=true"))
        assertEquals("home", device.currentScreenId)
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun humanBackedTimeoutTellsTheModelToReportInsteadOfRetrying() {
        val device = newDevice()
        val gate = ApprovalGate { _, _, _ -> ApprovalDecision.TIMEOUT }

        val timedOut = act(toolWith(device, gate), "action" to "tap", "node" to "pay_button")

        assertFalse(timedOut.isError)
        assertEquals(
            "APPROVAL_TIMEOUT: 'Pay' was not approved in time. " +
                "Do not retry immediately; report the situation to the user.",
            timedOut.content
        )
        assertFalse(timedOut.content.contains("confirmed=true"))
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun humanBackedDecisionsAreNotBypassableByAConfirmedArgument() {
        val device = newDevice()
        val gate = ApprovalGate { _, _, _ -> ApprovalDecision.DENIED }

        val denied = act(
            toolWith(device, gate),
            "action" to "tap",
            "node" to "pay_button",
            "confirmed" to "true"
        )

        assertTrue(denied.content.startsWith("DENIED_BY_USER: "))
        // The model-supplied confirmed=true must not bypass the gate: nothing executed.
        assertEquals("home", device.currentScreenId)
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun aGateMayContributeItsOwnMessage() {
        val device = newDevice()
        val gate = object : ApprovalGate {
            override fun decide(
                node: DeviceNode,
                action: String,
                arguments: Map<String, String>
            ): ApprovalDecision = ApprovalDecision.DENIED

            override fun pauseMessage(
                node: DeviceNode,
                action: String,
                decision: ApprovalDecision
            ): String = "DENIED_BY_USER: policy 'no-payments' blocked $action on '${node.label}'."
        }

        val denied = act(toolWith(device, gate), "action" to "tap", "node" to "pay_button")

        assertEquals(
            "DENIED_BY_USER: policy 'no-payments' blocked tap on 'Pay'.",
            denied.content
        )
    }

    @Test
    fun externalGateApprovesWithoutAnyConfirmedArgument() {
        val device = newDevice()
        val recorded = mutableListOf<String>()
        // Stands in for a human decision made outside the model's tool-call arguments.
        val humanBackedGate = ApprovalGate { node, action, arguments ->
            recorded += "$action:${node.id}:confirmed=${arguments["confirmed"]}"
            ApprovalDecision.APPROVED
        }

        val executed = act(toolWith(device, humanBackedGate), "action" to "tap", "node" to "pay_button")

        assertFalse(executed.isError)
        assertEquals("OK: tap pay_button -> screen=pay_confirm", executed.content)
        assertEquals("pay_confirm", device.currentScreenId)
        assertEquals(listOf("tap:pay_button"), device.actionLog())
        assertEquals(listOf("tap:pay_button:confirmed=null"), recorded)
    }

    @Test
    fun gateIsNotConsultedForLowRiskActions() {
        val device = newDevice()
        var consulted = false
        val tool = toolWith(device) { _, _, _ ->
            consulted = true
            ApprovalDecision.DENIED
        }

        val typed = act(tool, "action" to "set_text", "node" to "amount_field", "text" to "7")

        assertFalse(typed.isError)
        assertEquals("OK: set_text amount_field -> screen=home", typed.content)
        assertFalse(consulted)
    }

    @Test
    fun contextInferredRiskAlsoReachesTheGate() {
        // "OK" says nothing; the screen it sits on says "Confirm payment".
        val device = FakeDevice(
            screens = listOf(
                DeviceScreen(
                    id = "pay_confirm",
                    title = "Confirm payment",
                    nodes = listOf(DeviceNode("n1", "button", "OK"))
                )
            ),
            startScreenId = "pay_confirm"
        )
        val tool = DeviceActTool(
            surface = device,
            riskPolicy = RiskPolicy(highRiskLabelPatterns = listOf(Regex("(?i)\\bpayment\\b"))),
            approvalGate = ApprovalGate { _, _, _ -> ApprovalDecision.DENIED }
        )

        val denied = act(tool, "action" to "tap", "node" to "n1")

        assertEquals(
            "DENIED_BY_USER: 'OK' was refused on screen. Do not retry this action; " +
                "choose another approach or call device_finish.",
            denied.content
        )
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun everyNonApprovedDecisionClassifiesAsNeedsConfirmation() {
        assertNull(ApprovalDecision.APPROVED.errorType())
        assertEquals(DeviceErrorType.NEEDS_CONFIRMATION, ApprovalDecision.DENIED.errorType())
        assertEquals(DeviceErrorType.NEEDS_CONFIRMATION, ApprovalDecision.TIMEOUT.errorType())
    }
}
