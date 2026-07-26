// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-device parity of [DeviceActTool]: the navigation and gesture actions,
 * the capability model (unsupported actions fail cleanly), the home refusal,
 * the implicit settle and the stale-target guard.
 */
class DeviceActionsTest {

    private val listScreen = DeviceScreen(
        id = "list",
        title = "Inbox",
        nodes = listOf(
            DeviceNode("n1", "button", "Compose"),
            DeviceNode("n2", "textview", "Older messages", clickable = false)
        )
    )

    private fun act(tool: DeviceActTool, vararg arguments: Pair<String, String>): AgentToolResult {
        return tool.execute(
            AgentToolInvocation(
                callId = "call-1",
                sessionId = "actions-session",
                arguments = mapOf(*arguments)
            )
        )
    }

    private fun toolOn(
        surface: DeviceSurface,
        allowHome: Boolean = false
    ): DeviceActTool {
        return DeviceActTool(surface = surface, riskPolicy = RiskPolicy(), allowHome = allowHome)
    }

    @Test
    fun backReachesTheSurfaceAndIsFollowedByAnImplicitSettle() {
        val surface = RecordingSurface(listScreen)

        val result = act(toolOn(surface), "action" to "back")

        assertFalse(result.isError)
        assertEquals("OK: back -> screen=list", result.content)
        assertEquals(listOf("back", "wait:2000"), surface.calls)
    }

    @Test
    fun homeIsRefusedByDefaultAndNeverReachesTheSurface() {
        val surface = RecordingSurface(listScreen)

        val result = act(toolOn(surface), "action" to "home")

        assertTrue(result.isError)
        assertEquals(
            "UNSUPPORTED_ACTION: Action 'home' is refused: leaving the app breaks the task " +
                "chain and invalidates every node id you observed; use back or in-app " +
                "navigation instead. Do not retry 'home'.",
            result.content
        )
        assertEquals(emptyList<String>(), surface.calls)
    }

    @Test
    fun homeIsPerformedOnlyWhenTheToolWasBuiltWithAllowHome() {
        val surface = RecordingSurface(listScreen)

        val result = act(toolOn(surface, allowHome = true), "action" to "home")

        assertFalse(result.isError)
        assertEquals("OK: home -> screen=list", result.content)
        assertEquals(listOf("home", "wait:2000"), surface.calls)
    }

    @Test
    fun swipeAppliesDefaultDistanceAndDuration() {
        val surface = RecordingSurface(listScreen)

        val result = act(toolOn(surface), "action" to "swipe", "direction" to "UP")

        assertFalse(result.isError)
        assertEquals("OK: swipe up 600px/300ms -> screen=list", result.content)
        assertEquals(listOf("swipe:up:600:300", "wait:2000"), surface.calls)
    }

    @Test
    fun swipeHonoursExplicitDistanceAndDuration() {
        val surface = RecordingSurface(listScreen)

        val result = act(
            toolOn(surface),
            "action" to "swipe",
            "direction" to "left",
            "distance_px" to "250",
            "duration_ms" to "80"
        )

        assertFalse(result.isError)
        assertEquals("OK: swipe left 250px/80ms -> screen=list", result.content)
        assertEquals(listOf("swipe:left:250:80", "wait:2000"), surface.calls)
    }

    @Test
    fun swipeRejectsMissingBadDirectionAndBadNumbers() {
        val surface = RecordingSurface(listScreen)
        val tool = toolOn(surface)

        assertEquals(
            "INVALID_ARGUMENT: Action swipe requires a 'direction' argument (up | down | left | right).",
            act(tool, "action" to "swipe").content
        )
        assertEquals(
            "INVALID_ARGUMENT: Unknown direction 'diagonal'. Use one of: up | down | left | right.",
            act(tool, "action" to "swipe", "direction" to "diagonal").content
        )
        assertEquals(
            "INVALID_ARGUMENT: Argument 'distance_px' must be a positive whole number of pixels; got '0'.",
            act(tool, "action" to "swipe", "direction" to "up", "distance_px" to "0").content
        )
        assertEquals(
            "INVALID_ARGUMENT: Argument 'duration_ms' must be a positive whole number of " +
                "milliseconds; got 'fast'.",
            act(tool, "action" to "swipe", "direction" to "up", "duration_ms" to "fast").content
        )
        assertEquals(emptyList<String>(), surface.calls)
    }

    @Test
    fun scrollToTextDefaultsToDownAndReportsSuccess() {
        val surface = RecordingSurface(listScreen)

        val result = act(toolOn(surface), "action" to "scroll_to_text", "text" to "Older messages")

        assertFalse(result.isError)
        assertEquals("OK: scroll_to_text 'Older messages' down -> screen=list", result.content)
        assertEquals(listOf("scroll:Older messages:down:8", "wait:2000"), surface.calls)
    }

    @Test
    fun scrollToTextThatFailsReportsTargetNotFoundWithCandidates() {
        val surface = RecordingSurface(listScreen, scrollFound = false)

        val result = act(
            toolOn(surface),
            "action" to "scroll_to_text",
            "text" to "Archived",
            "direction" to "up",
            "max_scrolls" to "3"
        )

        assertTrue(result.isError)
        assertEquals(
            "TARGET_NOT_FOUND: Did not find 'Archived' after 3 up scroll attempts; screen " +
                "'list' does not contain it.\n" +
                "candidates:\n" +
                "[n1] button Compose\n" +
                "[n2] textview Older messages",
            result.content
        )
    }

    @Test
    fun launchAppReportsThePackageActuallyReached() {
        val surface = RecordingSurface(listScreen, launchResult = "shop.example.app")

        val result = act(toolOn(surface), "action" to "launch_app", "app" to "Shop")

        assertFalse(result.isError)
        assertEquals("OK: launch_app Shop -> package=shop.example.app screen=list", result.content)
        assertEquals(listOf("launch:Shop", "wait:2000"), surface.calls)
    }

    @Test
    fun launchAppByPackageFailsWhenAnotherPackageEndsUpInFront() {
        val surface = RecordingSurface(listScreen, launchResult = "system.example.chooser")

        val result = act(toolOn(surface), "action" to "launch_app", "app" to "shop.example.app")

        assertTrue(result.isError)
        assertEquals(
            "FOREGROUND_TIMEOUT: launch_app 'shop.example.app' ended up in package " +
                "'system.example.chooser' on screen 'list'. Observe the screen before assuming " +
                "the app is open.",
            result.content
        )
    }

    @Test
    fun launchAppReportsAppNotFoundFromTheSurface() {
        val surface = object : DeviceSurface by RecordingSurface(listScreen) {
            override fun launchApp(nameOrPackage: String): String {
                throw DeviceActionException(
                    DeviceErrorType.APP_NOT_FOUND,
                    "No installed app matches 'Ledger'."
                )
            }
        }

        val result = act(toolOn(surface), "action" to "launch_app", "app" to "Ledger")

        assertTrue(result.isError)
        assertEquals("APP_NOT_FOUND: No installed app matches 'Ledger'.", result.content)
    }

    @Test
    fun waitStableReportsSuccessAndTimeout() {
        val stable = RecordingSurface(listScreen)
        val busy = RecordingSurface(listScreen, stable = false)

        val settled = act(toolOn(stable), "action" to "wait_stable", "timeout_ms" to "500")
        assertFalse(settled.isError)
        assertEquals("OK: wait_stable 500ms -> screen=list", settled.content)
        assertEquals(listOf("wait:500"), stable.calls)

        val timedOut = act(toolOn(busy), "action" to "wait_stable")
        assertTrue(timedOut.isError)
        assertEquals(
            "WAIT_TIMEOUT: Screen 'list' was still changing after 2000ms. " +
                "Observe again before acting on any node id.",
            timedOut.content
        )
    }

    @Test
    fun everyNewActionFailsCleanlyOnASurfaceThatDoesNotSupportIt() {
        val surface = StaticSurface(listScreen)
        val tool = toolOn(surface, allowHome = true)

        val unsupported = mapOf(
            "back" to arrayOf("action" to "back"),
            "home" to arrayOf("action" to "home"),
            "swipe" to arrayOf("action" to "swipe", "direction" to "down"),
            "scroll_to_text" to arrayOf("action" to "scroll_to_text", "text" to "Archived"),
            "launch_app" to arrayOf("action" to "launch_app", "app" to "Shop"),
            "wait_stable" to arrayOf("action" to "wait_stable")
        )

        unsupported.forEach { (action, arguments) ->
            val result = act(tool, *arguments)
            assertTrue("$action should fail", result.isError)
            assertTrue(
                "$action should be reported as unsupported, was: ${result.content}",
                result.content.startsWith("UNSUPPORTED_ACTION: This device surface does not support '$action'")
            )
        }
    }

    @Test
    fun implicitSettleIsBestEffortWhenTheSurfaceCannotWait() {
        // StaticSurface implements neither waitForStable nor anything else optional.
        val result = act(toolOn(StaticSurface(listScreen)), "action" to "tap", "node" to "n1")

        assertFalse(result.isError)
        assertEquals("OK: tap n1 -> screen=list", result.content)
    }

    @Test
    fun surfaceFailuresBecomeStructuredActionFailures() {
        val surface = object : DeviceSurface by RecordingSurface(listScreen) {
            override fun tap(nodeId: String) {
                throw IllegalStateException("The system refused ACTION_CLICK for node '$nodeId'.")
            }
        }

        val result = act(toolOn(surface), "action" to "tap", "node" to "n1")

        assertTrue(result.isError)
        assertEquals(
            "ACTION_FAILED: Action 'tap' failed: The system refused ACTION_CLICK for node 'n1'.",
            result.content
        )
    }

    @Test
    fun surfacePermissionFailuresKeepTheirOwnErrorType() {
        val surface = object : DeviceSurface by RecordingSurface(listScreen) {
            override fun back() {
                throw DeviceActionException(
                    DeviceErrorType.PERMISSION_NOT_GRANTED,
                    "Accessibility service is not connected."
                )
            }
        }

        val result = act(toolOn(surface), "action" to "back")

        assertTrue(result.isError)
        assertEquals(
            "PERMISSION_NOT_GRANTED: Accessibility service is not connected.",
            result.content
        )
    }

    @Test
    fun expectedLabelPassesWhenTheTargetIsStillTheObservedOne() {
        val surface = RecordingSurface(listScreen)

        val result = act(
            toolOn(surface),
            "action" to "tap",
            "node" to "n1",
            "expected_label" to "  compose "
        )

        assertFalse(result.isError)
        assertEquals("OK: tap n1 -> screen=list", result.content)
        assertEquals(listOf("tap:n1", "wait:2000"), surface.calls)
    }

    @Test
    fun expectedLabelToleratesDisplayTruncationInEitherDirection() {
        val screen = DeviceScreen(
            id = "transfer",
            title = "Transfer",
            nodes = listOf(
                DeviceNode("n1", "button", "Send 1,200.00 to Ada Lovelace savings account")
            )
        )
        val surface = RecordingSurface(screen)

        val result = act(
            toolOn(surface),
            "action" to "tap",
            "node" to "n1",
            "expected_label" to "Send 1,200.00 to Ada Love..."
        )

        assertFalse(result.isError)
        assertEquals("OK: tap n1 -> screen=transfer", result.content)
    }

    @Test
    fun expectedLabelMismatchBlocksTheActionWithStaleTarget() {
        // The same positional id points at a different node once the app moved on.
        val device = FakeDevice(
            screens = listOf(
                DeviceScreen(
                    id = "checkout",
                    title = "Checkout",
                    nodes = listOf(DeviceNode("n1", "button", "Pay 12.50"))
                ),
                DeviceScreen(
                    id = "receipt",
                    title = "Receipt",
                    nodes = listOf(DeviceNode("n1", "button", "Back to shop"))
                )
            ),
            startScreenId = "checkout",
            transitions = mapOf(("checkout" to "n1") to "receipt")
        )
        val tool = toolOn(device)

        assertFalse(act(tool, "action" to "tap", "node" to "n1", "expected_label" to "Pay 12.50").isError)

        val stale = act(tool, "action" to "tap", "node" to "n1", "expected_label" to "Pay 12.50")

        assertTrue(stale.isError)
        assertEquals(
            "STALE_TARGET: Node 'n1' is now labelled 'Back to shop' but the call expected " +
                "'Pay 12.50' on screen 'receipt'. The screen changed after your observation; " +
                "call device_observe again and re-target before acting.",
            stale.content
        )
        // Only the first, still-valid tap reached the device.
        assertEquals(listOf("tap:n1"), device.actionLog())
    }

    @Test
    fun expectedLabelIsCheckedBeforeTheApprovalGate() {
        val device = FakeDevice(
            screens = listOf(
                DeviceScreen(
                    id = "checkout",
                    title = "Checkout",
                    nodes = listOf(DeviceNode("n1", "button", "Pay 12.50"))
                )
            ),
            startScreenId = "checkout"
        )
        var gateConsulted = false
        val tool = DeviceActTool(
            surface = device,
            riskPolicy = RiskPolicy(highRiskNodeIds = setOf("n1")),
            approvalGate = ApprovalGate { _, _, _ ->
                gateConsulted = true
                ApprovalDecision.APPROVED
            }
        )

        val stale = act(tool, "action" to "tap", "node" to "n1", "expected_label" to "Cancel")

        assertTrue(stale.isError)
        assertTrue(stale.content.startsWith("STALE_TARGET: "))
        assertFalse(gateConsulted)
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun blankExpectedLabelIsAnInvalidArgument() {
        val surface = RecordingSurface(listScreen)

        val result = act(
            toolOn(surface),
            "action" to "tap",
            "node" to "n1",
            "expected_label" to "   "
        )

        assertTrue(result.isError)
        assertEquals(
            "INVALID_ARGUMENT: Argument 'expected_label' must not be blank; omit it or quote " +
                "the observed label.",
            result.content
        )
        assertEquals(emptyList<String>(), surface.calls)
    }
}

/**
 * Fully capable in-memory surface that records every call, including the
 * implicit settle, so tests can assert what the device was really asked to do.
 */
internal class RecordingSurface(
    private val screen: DeviceScreen,
    private val scrollFound: Boolean = true,
    private val stable: Boolean = true,
    private val launchResult: String = "app.example.demo",
    private val foreground: String? = "app.example.demo"
) : DeviceSurface {
    val calls = mutableListOf<String>()

    override fun snapshot(): DeviceScreen = screen

    override fun tap(nodeId: String) {
        requireKnown(nodeId)
        calls += "tap:$nodeId"
    }

    override fun setText(nodeId: String, text: String) {
        requireKnown(nodeId)
        calls += "set_text:$nodeId:$text"
    }

    override fun back() {
        calls += "back"
    }

    override fun home() {
        calls += "home"
    }

    override fun swipe(direction: String, distancePx: Int, durationMs: Int) {
        calls += "swipe:$direction:$distancePx:$durationMs"
    }

    override fun scrollToText(text: String, direction: String, maxScrolls: Int): Boolean {
        calls += "scroll:$text:$direction:$maxScrolls"
        return scrollFound
    }

    override fun launchApp(nameOrPackage: String): String {
        calls += "launch:$nameOrPackage"
        return launchResult
    }

    override fun waitForStable(timeoutMs: Long): Boolean {
        calls += "wait:$timeoutMs"
        return stable
    }

    override fun foregroundPackage(): String? {
        calls += "foreground"
        return foreground
    }

    private fun requireKnown(nodeId: String) {
        require(screen.nodes.any { node -> node.id == nodeId }) {
            "Unknown node '$nodeId' on screen '${screen.id}'."
        }
    }
}
