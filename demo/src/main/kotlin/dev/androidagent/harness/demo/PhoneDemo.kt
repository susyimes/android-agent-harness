// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.deviceloop.DeviceActTool
import dev.androidagent.harness.deviceloop.DeviceFinishTool
import dev.androidagent.harness.deviceloop.DeviceLoopProfile
import dev.androidagent.harness.deviceloop.DeviceNode
import dev.androidagent.harness.deviceloop.DeviceObserveTool
import dev.androidagent.harness.deviceloop.DeviceScreen
import dev.androidagent.harness.deviceloop.FakeDevice
import dev.androidagent.harness.deviceloop.RiskPolicy

/**
 * Fake payment flow on the device loop: observe -> act -> observe with one tool
 * call per provider step. The Pay node is configured high-risk, so the first
 * tap pauses instead of executing; only after the (simulated) user approval is
 * the tap re-issued with confirmed=true and allowed to reach the device.
 */
fun runPhoneDemo() {
    val checkout = DeviceScreen(
        id = "checkout",
        title = "Checkout",
        nodes = listOf(
            DeviceNode(
                id = "order_summary",
                role = "text",
                label = "Order summary",
                text = "1 x coffee beans, total 12.50"
            ),
            DeviceNode(id = "pay_button", role = "button", label = "Pay 12.50"),
            DeviceNode(id = "cancel_button", role = "button", label = "Cancel")
        )
    )
    val receipt = DeviceScreen(
        id = "receipt",
        title = "Receipt",
        nodes = listOf(
            DeviceNode(
                id = "receipt_status",
                role = "text",
                label = "Payment status",
                text = "Paid 12.50"
            )
        )
    )
    val device = FakeDevice(
        screens = listOf(checkout, receipt),
        startScreenId = "checkout",
        transitions = mapOf(("checkout" to "pay_button") to "receipt")
    )
    val riskPolicy = RiskPolicy(
        highRiskNodeIds = setOf("pay_button"),
        highRiskLabelPatterns = listOf(Regex("(?i)\\bpay\\b"))
    )

    println("PHONE: fake payment flow on the device loop (observe -> act -> observe)")
    println("SETUP: screens=[checkout, receipt]; riskPolicy marks 'pay_button' high-risk; maxToolCallsPerStep=1")

    val runner = AgentHarnessRunner(
        provider = PaymentFlowProvider(),
        tools = listOf(
            DeviceObserveTool(device),
            DeviceActTool(device, riskPolicy),
            DeviceFinishTool()
        ),
        clock = FixedAgentClock(1_700_000_000_000L),
        idGenerator = SequentialAgentIdGenerator("phone"),
        config = AgentHarnessConfig(maxProviderSteps = 8, maxToolCallsPerStep = 1),
        toolProfile = DeviceLoopProfile.profile()
    )
    val result = runner.run(
        AgentHarnessRequest(
            sessionId = "phone-session",
            userInput = "Pay for my order, but never pay without my explicit approval."
        )
    )

    println("OUTPUT=${result.output}")
    println("PROVIDER_STEPS=${result.providerSteps}")
    println("TRACE=${result.trace.joinToString(" -> ") { event -> event.label() }}")
    println("TRANSCRIPT:")
    result.session.messages.forEachIndexed { index, message ->
        val toolSuffix = message.toolName?.let { name -> "($name)" }.orEmpty()
        val content = message.content.replace("\n", " / ")
        println("  [${index + 1}] ${message.role}$toolSuffix: $content")
    }
    println("ACTION_LOG=${device.actionLog().joinToString()}")
    println(
        "OUTCOME: the unconfirmed high-risk tap paused instead of executing; the device " +
            "received exactly one tap, and only after the user approval."
    )
}

/**
 * Scripted driver for the payment flow. It decides its next move purely from
 * the last tool result, which makes the pause protocol explicit: seeing
 * PAUSED_HIGH_RISK, it surfaces the approval request and only then re-issues
 * the same action with confirmed=true.
 */
private class PaymentFlowProvider : AgentProvider {
    override val id: String = "scripted-payment-flow"

    override fun respond(request: AgentProviderRequest): AgentProviderResponse {
        val lastTool = request.session.messages.lastOrNull { message ->
            message.role == AgentRole.TOOL
        } ?: return call("phone-observe-1", "device_observe")
        return when {
            lastTool.toolName == "device_observe" && lastTool.content.contains("screen=checkout") ->
                call(
                    "phone-act-pay",
                    "device_act",
                    mapOf("action" to "tap", "node" to "pay_button")
                )
            lastTool.content.startsWith("PAUSED_HIGH_RISK") -> {
                println("USER_APPROVAL=simulated operator approved the high-risk tap on 'pay_button'")
                call(
                    "phone-act-pay-confirmed",
                    "device_act",
                    mapOf("action" to "tap", "node" to "pay_button", "confirmed" to "true")
                )
            }
            lastTool.toolName == "device_act" && lastTool.content.startsWith("OK:") ->
                call("phone-observe-2", "device_observe")
            lastTool.toolName == "device_observe" && lastTool.content.contains("screen=receipt") ->
                call(
                    "phone-finish",
                    "device_finish",
                    mapOf(
                        "summary" to "Paid 12.50 after explicit operator approval of the high-risk tap."
                    )
                )
            lastTool.content.startsWith("FINISHED:") ->
                AgentProviderResponse.FinalText(
                    "Payment done: paused on the high-risk Pay tap, resumed only after " +
                        "operator approval, and verified the receipt."
                )
            else ->
                AgentProviderResponse.FinalText("Unexpected device state: ${lastTool.content}")
        }
    }

    private fun call(
        callId: String,
        toolName: String,
        arguments: Map<String, String> = emptyMap()
    ): AgentProviderResponse {
        return AgentProviderResponse.ToolRequests(
            listOf(AgentToolCall(id = callId, toolName = toolName, arguments = arguments))
        )
    }
}
