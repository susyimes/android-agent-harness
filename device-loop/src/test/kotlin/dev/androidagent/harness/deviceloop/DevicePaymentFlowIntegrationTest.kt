// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentHarnessTraceEvent
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end fake payment flow under the one-action-per-step device loop:
 * observe -> act(tap pay) pauses -> act(tap pay, confirmed=true) -> observe -> finish.
 */
class DevicePaymentFlowIntegrationTest {

    @Test
    fun paymentFlowPausesOnHighRiskTapAndResumesAfterConfirmation() {
        val device = FakeDevice(
            screens = listOf(
                DeviceScreen(
                    id = "home",
                    title = "Wallet home",
                    nodes = listOf(
                        DeviceNode("balance_label", "label", "Balance", text = "100"),
                        DeviceNode("pay_button", "button", "Pay")
                    )
                ),
                DeviceScreen(
                    id = "pay_confirm",
                    title = "Confirm payment",
                    nodes = listOf(
                        DeviceNode("confirm_button", "button", "Confirm"),
                        DeviceNode("cancel_button", "button", "Cancel")
                    )
                ),
                DeviceScreen(
                    id = "receipt",
                    title = "Receipt",
                    nodes = listOf(DeviceNode("receipt_label", "label", "Payment complete"))
                )
            ),
            startScreenId = "home",
            transitions = mapOf(
                ("home" to "pay_button") to "pay_confirm",
                ("pay_confirm" to "confirm_button") to "receipt"
            )
        )
        val runner = AgentHarnessRunner(
            provider = ScriptedPaymentProvider(),
            tools = listOf(
                DeviceObserveTool(device),
                DeviceActTool(device, RiskPolicy(highRiskNodeIds = setOf("pay_button"))),
                DeviceFinishTool()
            ),
            clock = FixedAgentClock(1_700_000_000_000L),
            idGenerator = SequentialAgentIdGenerator("device"),
            config = AgentHarnessConfig(maxProviderSteps = 8, maxToolCallsPerStep = 1),
            toolProfile = DeviceLoopProfile.profile()
        )

        val result = runner.run(
            AgentHarnessRequest(sessionId = "payment-session", userInput = "Pay the pending invoice.")
        )

        assertEquals(6, result.providerSteps)
        assertEquals(
            listOf(
                "ContextLoaded",
                "ProviderInvoked(1)",
                "ToolExecuted(device_observe)",
                "ProviderInvoked(2)",
                "ToolExecuted(device_act)",
                "ProviderInvoked(3)",
                "ToolExecuted(device_act)",
                "ProviderInvoked(4)",
                "ToolExecuted(device_observe)",
                "ProviderInvoked(5)",
                "ToolExecuted(device_finish)",
                "ProviderInvoked(6)",
                "Completed(6)"
            ),
            result.trace.map { event -> event.label() }
        )

        val toolExecutions = result.trace.filterIsInstance<AgentHarnessTraceEvent.ToolExecuted>()
        val pausedExecution = toolExecutions[1]
        assertTrue(pausedExecution.succeeded)
        assertEquals(
            "PAUSED_HIGH_RISK: 'Pay' requires explicit user confirmation. " +
                "Re-invoke with confirmed=true after the user approves.",
            pausedExecution.content
        )
        assertEquals("OK: tap pay_button -> screen=pay_confirm", toolExecutions[2].content)
        assertTrue(toolExecutions[3].content.startsWith("screen=pay_confirm title=Confirm payment"))
        assertTrue(toolExecutions[4].content.startsWith("FINISHED: "))

        // The pause is part of the durable transcript, not only the trace.
        val pausedTranscriptMessages = result.session.messages.filter { message ->
            message.role == AgentRole.TOOL && message.content.startsWith("PAUSED_HIGH_RISK: ")
        }
        assertEquals(1, pausedTranscriptMessages.size)
        assertEquals("device_act", pausedTranscriptMessages.single().toolName)

        // Only the confirmed tap reached the device; the paused attempt executed nothing.
        assertEquals(listOf("tap:pay_button"), device.actionLog())
        assertEquals("pay_confirm", device.currentScreenId)
        assertEquals(
            "Device task done: FINISHED: Reached the payment confirmation screen after user approval.",
            result.output
        )
    }

    private fun AgentHarnessTraceEvent.label(): String {
        return when (this) {
            is AgentHarnessTraceEvent.ContextLoaded -> "ContextLoaded"
            is AgentHarnessTraceEvent.ProviderInvoked -> "ProviderInvoked($step)"
            is AgentHarnessTraceEvent.ToolExecuted -> "ToolExecuted($toolName)"
            is AgentHarnessTraceEvent.Completed -> "Completed($step)"
        }
    }

    /** Replays the governed payment script one tool call per provider step. */
    private class ScriptedPaymentProvider : AgentProvider {
        override val id: String = "scripted-payment-provider"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            return when (request.providerStep) {
                1 -> toolCall("observe-home", "device_observe")
                2 -> toolCall(
                    "tap-pay-unconfirmed",
                    "device_act",
                    "action" to "tap",
                    "node" to "pay_button"
                )
                3 -> toolCall(
                    "tap-pay-confirmed",
                    "device_act",
                    "action" to "tap",
                    "node" to "pay_button",
                    "confirmed" to "true"
                )
                4 -> toolCall("observe-confirm", "device_observe")
                5 -> toolCall(
                    "finish-task",
                    "device_finish",
                    "summary" to "Reached the payment confirmation screen after user approval."
                )
                else -> {
                    val lastToolContent = request.session.messages
                        .last { message -> message.role == AgentRole.TOOL }
                        .content
                    AgentProviderResponse.FinalText("Device task done: $lastToolContent")
                }
            }
        }

        private fun toolCall(
            callId: String,
            toolName: String,
            vararg arguments: Pair<String, String>
        ): AgentProviderResponse.ToolRequests {
            return AgentProviderResponse.ToolRequests(
                listOf(AgentToolCall(id = callId, toolName = toolName, arguments = mapOf(*arguments)))
            )
        }
    }
}
