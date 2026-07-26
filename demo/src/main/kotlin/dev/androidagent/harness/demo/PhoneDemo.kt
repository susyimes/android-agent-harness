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
import dev.androidagent.harness.deviceloop.ApprovalDecision
import dev.androidagent.harness.deviceloop.ApprovalGate
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
 * call per provider step, on screens that use positional node ids (n1, n2, ...)
 * the way a real accessibility snapshot does.
 *
 * It demonstrates the four guardrails the device loop exists for:
 * 1. A direct high-risk match ("Pay 12.50") pauses instead of executing.
 * 2. A generic confirm button ("OK") on a payment screen pauses too, because
 *    the risk lives in the surrounding screen rather than in the label.
 * 3. "home" is refused: leaving the app would invalidate every observed id.
 * 4. A replayed action whose target silently re-pointed is stopped by the
 *    expected_label guard, and finishing requires evidence that is really on
 *    the screen.
 */
fun runPhoneDemo() {
    val checkout = DeviceScreen(
        id = "checkout",
        title = "Checkout",
        nodes = listOf(
            DeviceNode(
                id = "n1",
                role = "textview",
                label = "Order summary",
                text = "1 x coffee beans, total 12.50",
                clickable = false
            ),
            DeviceNode(id = "n2", role = "button", label = "Pay 12.50", viewId = "btn_pay"),
            DeviceNode(id = "n3", role = "button", label = "Cancel"),
            DeviceNode(
                id = "n4",
                role = "textview",
                label = "Alipay wallet balance 40.00",
                clickable = false
            )
        )
    )
    val payConfirm = DeviceScreen(
        id = "pay_confirm",
        title = "Confirm payment",
        nodes = listOf(
            DeviceNode(
                id = "n1",
                role = "textview",
                label = "Charge 12.50 to card ending 4242",
                clickable = false
            ),
            DeviceNode(id = "n2", role = "button", label = "OK", viewId = "btn_dialog_ok"),
            DeviceNode(id = "n3", role = "button", label = "Cancel")
        )
    )
    val receipt = DeviceScreen(
        id = "receipt",
        title = "Receipt",
        nodes = listOf(
            DeviceNode(
                id = "n1",
                role = "textview",
                label = "Payment status",
                text = "Paid 12.50",
                clickable = false
            ),
            DeviceNode(id = "n2", role = "button", label = "Back to shop")
        )
    )
    val device = FakeDevice(
        screens = listOf(checkout, payConfirm, receipt),
        startScreenId = "checkout",
        transitions = mapOf(
            ("checkout" to "n2") to "pay_confirm",
            ("pay_confirm" to "n2") to "receipt"
        ),
        packageName = FOREGROUND_PACKAGE
    )
    // A deliberately loose pattern, the way operators usually start, plus the
    // allowlist that keeps it from firing on a brand name.
    val riskPolicy = RiskPolicy(
        highRiskLabelPatterns = listOf(Regex("(?i)pay"), Regex("(?i)charge")),
        exemptSubstrings = setOf("Alipay")
    )

    println("PHONE: fake payment flow on the device loop (observe -> act -> observe)")
    println(
        "SETUP: screens=[checkout, pay_confirm, receipt] with positional ids; " +
            "risk vocabulary=[pay, charge]; allowlist=[Alipay]; home refused; " +
            "device_finish requires on-screen evidence; maxToolCallsPerStep=1"
    )
    println(
        "ALLOWLIST: '${checkout.nodes[3].label}' contains 'pay' but is exempt, so it never " +
            "costs the operator a confirmation."
    )

    val runner = AgentHarnessRunner(
        provider = PaymentFlowProvider(),
        tools = listOf(
            DeviceObserveTool(device),
            DeviceActTool(device, riskPolicy),
            DeviceFinishTool(device)
        ),
        clock = FixedAgentClock(1_700_000_000_000L),
        idGenerator = SequentialAgentIdGenerator("phone"),
        config = AgentHarnessConfig(maxProviderSteps = 14, maxToolCallsPerStep = 1),
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

    // The scripted gate above invites a confirmed retry because the approval is
    // part of the script. A human-backed gate must never do that; these are the
    // messages it would return instead.
    val payButton = checkout.nodes[1]
    println("HUMAN_GATE_DENIED=${ApprovalGate.defaultPauseMessage(payButton, ApprovalDecision.DENIED)}")
    println("HUMAN_GATE_TIMEOUT=${ApprovalGate.defaultPauseMessage(payButton, ApprovalDecision.TIMEOUT)}")
    println(
        "OUTCOME: two high-risk taps paused (one matched 'Pay 12.50' directly, one was a " +
            "generic 'OK' escalated by the 'Confirm payment' screen around it); 'home' was " +
            "refused; a replayed tap was stopped by the stale-target guard; the device " +
            "received exactly two taps, each only after an approval, and device_finish had to " +
            "prove 'Paid 12.50' on screen in package $FOREGROUND_PACKAGE."
    )
}

private const val FOREGROUND_PACKAGE = "shop.example.coffee"

/**
 * Scripted driver for the payment flow. It decides its next move purely from
 * the last tool result, which makes every protocol explicit: seeing a pause it
 * surfaces the approval request and re-issues exactly the same action with
 * confirmed=true; seeing a refusal or a stale target it changes plan instead of
 * hammering the same call.
 */
private class PaymentFlowProvider : AgentProvider {
    override val id: String = "scripted-payment-flow"

    /** The last action this provider asked for, so a pause can be resumed verbatim. */
    private var lastActArguments: Map<String, String> = emptyMap()
    private var replayedStaleTarget = false

    override fun respond(request: AgentProviderRequest): AgentProviderResponse {
        val lastTool = request.session.messages.lastOrNull { message ->
            message.role == AgentRole.TOOL
        } ?: return act("phone-observe-1", "device_observe")

        val content = lastTool.content
        return when {
            content.startsWith("PAUSED_HIGH_RISK") -> {
                val label = content.substringAfter("'").substringBefore("'")
                println("USER_APPROVAL=simulated operator approved the high-risk tap on '$label'")
                act(
                    "phone-act-confirmed-${lastActArguments["node"]}-$label",
                    "device_act",
                    lastActArguments + ("confirmed" to "true")
                )
            }

            content.startsWith("UNSUPPORTED_ACTION") -> {
                println("GUARDRAIL=home was refused, so the flow stays inside the app")
                act(
                    "phone-act-pay",
                    "device_act",
                    mapOf("action" to "tap", "node" to "n2", "expected_label" to "Pay 12.50")
                )
            }

            content.startsWith("STALE_TARGET") -> {
                println("GUARDRAIL=stale target caught, re-observing before acting again")
                act("phone-observe-3", "device_observe")
            }

            lastTool.toolName == "device_observe" && content.contains("screen=checkout") ->
                // Try to bail out to the launcher first; the tool refuses it.
                act("phone-act-home", "device_act", mapOf("action" to "home"))

            lastTool.toolName == "device_observe" && content.contains("screen=pay_confirm") ->
                act(
                    "phone-act-ok",
                    "device_act",
                    mapOf("action" to "tap", "node" to "n2", "expected_label" to "OK")
                )

            lastTool.toolName == "device_observe" && content.contains("screen=receipt") ->
                act(
                    "phone-finish",
                    "device_finish",
                    mapOf(
                        "summary" to "Paid 12.50 after explicit operator approval of both " +
                            "high-risk taps.",
                        "evidence" to "Paid 12.50",
                        "expected_app" to FOREGROUND_PACKAGE
                    )
                )

            content.startsWith("OK:") && content.contains("screen=pay_confirm") ->
                act("phone-observe-2", "device_observe")

            content.startsWith("OK:") && content.contains("screen=receipt") && !replayedStaleTarget -> {
                replayedStaleTarget = true
                println("REPLAY=re-issuing the confirm tap without observing first (the mistake)")
                act(
                    "phone-act-stale",
                    "device_act",
                    mapOf("action" to "tap", "node" to "n2", "expected_label" to "OK")
                )
            }

            content.startsWith("FINISHED:") ->
                AgentProviderResponse.FinalText(
                    "Payment done: paused on both high-risk taps, resumed only after operator " +
                        "approval, and proved the receipt on screen."
                )

            else -> AgentProviderResponse.FinalText("Unexpected device state: $content")
        }
    }

    private fun act(
        callId: String,
        toolName: String,
        arguments: Map<String, String> = emptyMap()
    ): AgentProviderResponse {
        if (toolName == "device_act") {
            lastActArguments = arguments
        }
        return AgentProviderResponse.ToolRequests(
            listOf(AgentToolCall(id = callId, toolName = toolName, arguments = arguments))
        )
    }
}
