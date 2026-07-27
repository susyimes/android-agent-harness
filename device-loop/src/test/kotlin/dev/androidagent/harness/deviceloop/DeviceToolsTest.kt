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
    fun observeRendersViewIdsAndDisabledNodes() {
        val screen = DeviceScreen(
            id = "s1",
            title = "Transfer",
            nodes = listOf(
                DeviceNode(
                    id = "n1",
                    role = "edittext",
                    label = "Amount",
                    text = "0",
                    viewId = "amount_input",
                    editable = true
                ),
                DeviceNode(id = "n2", role = "button", label = "Send", enabled = false)
            )
        )
        val tool = DeviceObserveTool(StaticSurface(screen))

        assertEquals(
            "screen=s1 title=Transfer\n" +
                "[n1] edittext Amount (text=0) (view_id=amount_input)\n" +
                "[n2] button Send [disabled]",
            tool.execute(invocation(emptyMap())).content
        )
    }

    @Test
    fun observeReportsSurfaceFailuresAsStructuredFailures() {
        val tool = DeviceObserveTool(
            FailingSurface(
                DeviceActionException(
                    DeviceErrorType.PERMISSION_NOT_GRANTED,
                    "Accessibility service is not enabled."
                )
            )
        )

        val result = tool.execute(invocation(emptyMap()))

        assertTrue(result.isError)
        assertEquals(
            "PERMISSION_NOT_GRANTED: Accessibility service is not enabled.",
            result.content
        )
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
    fun actRejectsUnknownActionMissingNodeAndMissingText() {
        val device = newDevice()
        val tool = DeviceActTool(device, RiskPolicy())

        val badAction = act(tool, "action" to "fly", "node" to "pay_button")
        assertTrue(badAction.isError)
        assertEquals(
            "UNSUPPORTED_ACTION: Unknown action 'fly'. Use one of: tap | set_text | back | " +
                "swipe | scroll_to_text | launch_app | wait_stable.",
            badAction.content
        )

        val missingNode = act(tool, "action" to "tap")
        assertTrue(missingNode.isError)
        assertEquals(
            "INVALID_ARGUMENT: Action tap requires a 'node' argument naming an id from the " +
                "latest device_observe.",
            missingNode.content
        )

        val missingText = act(tool, "action" to "set_text", "node" to "amount_field")
        assertTrue(missingText.isError)
        assertEquals("INVALID_ARGUMENT: Action set_text requires a 'text' argument.", missingText.content)
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun unknownNodeFailureListsCandidatesFromTheCurrentScreen() {
        val device = newDevice()
        val tool = DeviceActTool(device, RiskPolicy())

        val result = act(tool, "action" to "tap", "node" to "missing")

        assertTrue(result.isError)
        assertEquals(
            "TARGET_NOT_FOUND: Unknown node 'missing' on screen 'home'.\n" +
                "candidates:\n" +
                "[amount_field] input Amount\n" +
                "[pay_button] button Pay",
            result.content
        )
    }

    @Test
    fun candidateListIsCappedAtFiveAndPrefersActionableNodes() {
        val nodes = buildList {
            add(DeviceNode("t1", "textview", "Heading", clickable = false))
            add(DeviceNode("t2", "textview", "Legal notice", clickable = false))
            repeat(6) { index -> add(DeviceNode("b$index", "button", "Button $index")) }
        }
        val tool = DeviceActTool(
            StaticSurface(DeviceScreen("long", "Long screen", nodes)),
            RiskPolicy()
        )

        val result = act(tool, "action" to "tap", "node" to "nope")

        assertEquals(
            listOf(
                "TARGET_NOT_FOUND: Unknown node 'nope' on screen 'long'.",
                "candidates:",
                "[b0] button Button 0",
                "[b1] button Button 1",
                "[b2] button Button 2",
                "[b3] button Button 3",
                "[b4] button Button 4"
            ),
            result.content.lines()
        )
    }

    @Test
    fun actRefusesDisabledNodesInsteadOfSilentlyDoingNothing() {
        val screen = DeviceScreen(
            id = "form",
            title = "Form",
            nodes = listOf(DeviceNode("submit", "button", "Submit", enabled = false))
        )
        val surface = RecordingSurface(screen)
        val tool = DeviceActTool(surface, RiskPolicy())

        val result = act(tool, "action" to "tap", "node" to "submit")

        assertTrue(result.isError)
        assertEquals(
            "ACTION_FAILED: Node 'submit' ('Submit') is disabled on screen 'form'; acting on " +
                "it would silently do nothing. Satisfy its precondition first.",
            result.content
        )
        assertEquals(emptyList<String>(), surface.calls)
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
    fun finishToolEchoesSummaryWithoutASurface() {
        val result = DeviceFinishTool().execute(invocation(mapOf("summary" to "Payment approved and sent.")))

        assertFalse(result.isError)
        assertEquals("FINISHED: Payment approved and sent.", result.content)
        assertEquals(setOf("summary"), DeviceFinishTool().spec.requiredArguments)
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

    @Test
    fun actSpecOnlyRequiresTheActionArgument() {
        val spec = DeviceActTool(newDevice(), RiskPolicy()).spec

        assertEquals(setOf("action"), spec.requiredArguments)
        assertTrue(spec.optionalArguments.contains("app"))
        assertTrue(spec.optionalArguments.contains("node"))
        assertTrue(spec.description.contains("launch_app=app"))
        assertFalse(spec.description.substringBefore(").").contains("home"))
    }

    @Test
    fun finishSpecExposesExpectedAppWithoutRequiringIt() {
        val spec = DeviceFinishTool(newDevice()).spec

        assertEquals(setOf("summary", "evidence"), spec.requiredArguments)
        assertEquals(setOf("expected_app"), spec.optionalArguments)
    }
}

/** Surface that always reports the same screen and refuses nothing. */
internal class StaticSurface(private val screen: DeviceScreen) : DeviceSurface {
    override fun snapshot(): DeviceScreen = screen

    override fun tap(nodeId: String) = Unit

    override fun setText(nodeId: String, text: String) = Unit
}

/** Surface whose observation always fails with the configured error. */
internal class FailingSurface(private val error: RuntimeException) : DeviceSurface {
    override fun snapshot(): DeviceScreen = throw error

    override fun tap(nodeId: String) = throw error

    override fun setText(nodeId: String, text: String) = throw error
}
