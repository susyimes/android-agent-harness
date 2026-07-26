// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Completion must be proven against the screen, not asserted by the model. */
class DeviceFinishToolTest {

    private val receipt = DeviceScreen(
        id = "receipt",
        title = "Receipt",
        nodes = listOf(
            DeviceNode("n1", "textview", "Payment status", text = "Paid  12.50", clickable = false),
            DeviceNode("n2", "button", "Back to shop", viewId = "back_to_shop")
        )
    )

    private fun finish(tool: DeviceFinishTool, vararg arguments: Pair<String, String>): AgentToolResult {
        return tool.execute(
            AgentToolInvocation(
                callId = "call-1",
                sessionId = "finish-session",
                arguments = mapOf(*arguments)
            )
        )
    }

    @Test
    fun evidenceIsRequiredOnlyWhenASurfaceIsAttached() {
        assertEquals(setOf("summary"), DeviceFinishTool().spec.requiredArguments)
        assertEquals(
            setOf("summary", "evidence"),
            DeviceFinishTool(StaticSurface(receipt)).spec.requiredArguments
        )
    }

    @Test
    fun evidenceVisibleOnScreenFinishesTheTask() {
        val tool = DeviceFinishTool(StaticSurface(receipt))

        val result = finish(
            tool,
            "summary" to "Paid the coffee order.",
            "evidence" to "paid 12.50"
        )

        assertFalse(result.isError)
        assertEquals(
            "FINISHED: Paid the coffee order. (evidence 'paid 12.50' verified on screen 'receipt')",
            result.content
        )
    }

    @Test
    fun evidenceMayComeFromTheTitleOrAViewId() {
        val tool = DeviceFinishTool(StaticSurface(receipt))

        assertFalse(finish(tool, "summary" to "Done.", "evidence" to "Receipt").isError)
        assertFalse(finish(tool, "summary" to "Done.", "evidence" to "back_to_shop").isError)
    }

    @Test
    fun missingEvidenceFailsAndAttachesTheCurrentScreen() {
        val tool = DeviceFinishTool(StaticSurface(receipt))

        val result = finish(
            tool,
            "summary" to "Paid the coffee order.",
            "evidence" to "Order shipped"
        )

        assertTrue(result.isError)
        assertEquals(
            listOf(
                "ACTION_FAILED: Evidence 'Order shipped' is not visible on screen 'receipt', " +
                    "so the task is not proven complete. Keep working, or finish with evidence " +
                    "that is actually on screen.",
                "current screen:",
                "screen=receipt title=Receipt",
                "[n1] textview Payment status (text=Paid  12.50)",
                "[n2] button Back to shop (view_id=back_to_shop)"
            ),
            result.content.lines()
        )
    }

    @Test
    fun blankEvidenceIsAnInvalidArgument() {
        val tool = DeviceFinishTool(StaticSurface(receipt))

        val result = finish(tool, "summary" to "Done.", "evidence" to "  ")

        assertTrue(result.isError)
        assertTrue(result.content.startsWith("INVALID_ARGUMENT: Argument 'evidence' must not be blank"))
    }

    @Test
    fun expectedAppIsVerifiedAgainstTheForegroundPackage() {
        val surface = RecordingSurface(receipt, foreground = "shop.example.app")
        val tool = DeviceFinishTool(surface)

        val matching = finish(
            tool,
            "summary" to "Paid.",
            "evidence" to "Paid 12.50",
            "expected_app" to "shop.example.app"
        )
        assertFalse(matching.isError)

        val mismatched = finish(
            tool,
            "summary" to "Paid.",
            "evidence" to "Paid 12.50",
            "expected_app" to "bank.example.app"
        )
        assertTrue(mismatched.isError)
        assertEquals(
            "FOREGROUND_TIMEOUT: Expected app 'bank.example.app' is not in the foreground " +
                "(foreground=shop.example.app) on screen 'receipt'. Evidence read from another " +
                "app does not prove this task.",
            mismatched.content
        )
    }

    @Test
    fun expectedAppOnASurfaceThatCannotReportItFailsAsUnsupported() {
        val tool = DeviceFinishTool(StaticSurface(receipt))

        val result = finish(
            tool,
            "summary" to "Paid.",
            "evidence" to "Paid 12.50",
            "expected_app" to "shop.example.app"
        )

        assertTrue(result.isError)
        assertTrue(
            result.content.startsWith(
                "UNSUPPORTED_ACTION: This device surface cannot report the foreground package"
            )
        )
    }

    @Test
    fun observationFailuresKeepTheirStructuredType() {
        val tool = DeviceFinishTool(
            FailingSurface(
                DeviceActionException(
                    DeviceErrorType.PERMISSION_NOT_GRANTED,
                    "Accessibility service is not enabled."
                )
            )
        )

        val result = finish(tool, "summary" to "Paid.", "evidence" to "Paid 12.50")

        assertTrue(result.isError)
        assertEquals(
            "PERMISSION_NOT_GRANTED: Accessibility service is not enabled.",
            result.content
        )
    }
}
