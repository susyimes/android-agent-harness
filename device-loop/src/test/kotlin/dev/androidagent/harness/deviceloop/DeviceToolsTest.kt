// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToolsTest {

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

    private fun invocation(arguments: Map<String, String>): AgentToolInvocation {
        return AgentToolInvocation(
            callId = "call-1",
            sessionId = "tools-session",
            arguments = arguments
        )
    }

    private fun act(tool: DeviceActTool, vararg arguments: Pair<String, String>): AgentToolResult {
        return tool.execute(invocation(mapOf(*arguments)))
    }

    @Test
    fun observeRenderingIsDeterministicAndInDeclarationOrder() {
        val device = newDevice()
        val tool = DeviceObserveTool(device)

        val first = tool.execute(invocation(emptyMap()))
        val second = tool.execute(invocation(emptyMap()))

        val expected = "screen=home title=Home\n" +
            "[amount_field] input Amount (text=42)\n" +
            "[pay_button] button Pay"
        assertFalse(first.isError)
        assertEquals(expected, first.content)
        assertEquals(first, second)
    }

    @Test
    fun observeReflectsEnteredTextAndScreenChanges() {
        val device = newDevice()
        val tool = DeviceObserveTool(device)

        device.setText("amount_field", "99")
        assertEquals(
            "screen=home title=Home\n" +
                "[amount_field] input Amount (text=99)\n" +
                "[pay_button] button Pay",
            tool.execute(invocation(emptyMap())).content
        )

        device.tap("pay_button")
        assertEquals(
            "screen=pay_confirm title=Confirm payment\n[confirm_button] button Confirm",
            tool.execute(invocation(emptyMap())).content
        )
    }

    @Test
    fun actExecutesTapAndSetTextOnLowRiskNodes() {
        val device = newDevice()
        val tool = DeviceActTool(device, RiskPolicy())

        val typed = act(tool, "action" to "set_text", "node" to "amount_field", "text" to "7")
        assertFalse(typed.isError)
        assertEquals("OK: set_text amount_field -> screen=home", typed.content)

        val tapped = act(tool, "action" to "tap", "node" to "pay_button")
        assertFalse(tapped.isError)
        assertEquals("OK: tap pay_button -> screen=pay_confirm", tapped.content)
        assertEquals(listOf("set_text:amount_field:7", "tap:pay_button"), device.actionLog())
    }

    @Test
    fun actRejectsBadActionUnknownNodeAndMissingText() {
        val device = newDevice()
        val tool = DeviceActTool(device, RiskPolicy())

        val badAction = act(tool, "action" to "swipe", "node" to "pay_button")
        assertTrue(badAction.isError)
        assertEquals("Unsupported action 'swipe'. Use tap or set_text.", badAction.content)

        val unknownNode = act(tool, "action" to "tap", "node" to "missing")
        assertTrue(unknownNode.isError)
        assertEquals("Unknown node 'missing' on screen 'home'.", unknownNode.content)

        val missingText = act(tool, "action" to "set_text", "node" to "amount_field")
        assertTrue(missingText.isError)
        assertEquals("Action set_text requires a 'text' argument.", missingText.content)
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun actPausesOnHighRiskNodeWithoutConfirmation() {
        val device = newDevice()
        val tool = DeviceActTool(device, RiskPolicy(highRiskNodeIds = setOf("pay_button")))

        val paused = act(tool, "action" to "tap", "node" to "pay_button")

        assertFalse(paused.isError)
        assertEquals(
            "PAUSED_HIGH_RISK: 'Pay' requires explicit user confirmation. " +
                "Re-invoke with confirmed=true after the user approves.",
            paused.content
        )
        assertEquals("home", device.currentScreenId)
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun actPausesOnHighRiskLabelPatternWithoutConfirmation() {
        val device = newDevice()
        val tool = DeviceActTool(device, RiskPolicy(highRiskLabelPatterns = listOf(Regex("(?i)pay"))))

        val paused = act(tool, "action" to "tap", "node" to "pay_button")

        assertFalse(paused.isError)
        assertTrue(paused.content.startsWith("PAUSED_HIGH_RISK: 'Pay'"))
        assertEquals("home", device.currentScreenId)
    }

    @Test
    fun actExecutesHighRiskNodeWhenConfirmed() {
        val device = newDevice()
        val tool = DeviceActTool(device, RiskPolicy(highRiskNodeIds = setOf("pay_button")))

        val executed = act(tool, "action" to "tap", "node" to "pay_button", "confirmed" to "true")

        assertFalse(executed.isError)
        assertEquals("OK: tap pay_button -> screen=pay_confirm", executed.content)
        assertEquals("pay_confirm", device.currentScreenId)
        assertEquals(listOf("tap:pay_button"), device.actionLog())
    }

    @Test
    fun finishToolEchoesSummary() {
        val result = DeviceFinishTool().execute(invocation(mapOf("summary" to "Payment approved and sent.")))

        assertFalse(result.isError)
        assertEquals("FINISHED: Payment approved and sent.", result.content)
    }

    @Test
    fun profileExposesExactlyTheThreeDeviceTools() {
        val profile = DeviceLoopProfile.profile()

        assertEquals("device-loop", profile.id)
        assertEquals(
            setOf("device_act", "device_finish", "device_observe"),
            profile.allowedToolNames
        )
    }
}
