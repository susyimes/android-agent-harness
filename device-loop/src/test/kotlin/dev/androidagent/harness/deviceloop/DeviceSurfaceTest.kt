// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentToolInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device tools depend only on [DeviceSurface], so a completely different
 * implementation (not [FakeDevice]) must drive the same tools unchanged.
 */
class DeviceSurfaceTest {

    /** Minimal two-screen surface written directly against the interface. */
    private class TinySurface : DeviceSurface {
        private var onSecondScreen = false
        private var noteText: String? = null

        override fun snapshot(): DeviceScreen {
            return if (onSecondScreen) {
                DeviceScreen(
                    id = "second",
                    title = "Second",
                    nodes = listOf(DeviceNode("done_label", "label", "Done"))
                )
            } else {
                DeviceScreen(
                    id = "first",
                    title = "First",
                    nodes = listOf(
                        DeviceNode("note_field", "input", "Note", text = noteText),
                        DeviceNode("next_button", "button", "Next")
                    )
                )
            }
        }

        override fun tap(nodeId: String) {
            requireKnown(nodeId)
            if (nodeId == "next_button") {
                onSecondScreen = true
            }
        }

        override fun setText(nodeId: String, text: String) {
            requireKnown(nodeId)
            require(nodeId == "note_field") { "Node '$nodeId' does not accept text." }
            noteText = text
        }

        private fun requireKnown(nodeId: String) {
            require(snapshot().nodes.any { node -> node.id == nodeId }) {
                "Unknown node '$nodeId' on screen '${snapshot().id}'."
            }
        }
    }

    private fun invocation(arguments: Map<String, String>): AgentToolInvocation {
        return AgentToolInvocation(
            callId = "call-1",
            sessionId = "surface-session",
            arguments = arguments
        )
    }

    @Test
    fun observeToolRendersAnAlternativeSurface() {
        val observe = DeviceObserveTool(TinySurface())

        val result = observe.execute(invocation(emptyMap()))

        assertFalse(result.isError)
        assertEquals(
            "screen=first title=First\n" +
                "[note_field] input Note\n" +
                "[next_button] button Next",
            result.content
        )
    }

    @Test
    fun actToolDrivesAnAlternativeSurfaceThroughSetTextAndTap() {
        val surface = TinySurface()
        val act = DeviceActTool(surface, RiskPolicy())
        val observe = DeviceObserveTool(surface)

        val typed = act.execute(
            invocation(mapOf("action" to "set_text", "node" to "note_field", "text" to "hello"))
        )
        assertFalse(typed.isError)
        assertEquals("OK: set_text note_field -> screen=first", typed.content)
        assertTrue(observe.execute(invocation(emptyMap())).content.contains("(text=hello)"))

        val tapped = act.execute(invocation(mapOf("action" to "tap", "node" to "next_button")))
        assertFalse(tapped.isError)
        // The OK message reports the post-action screen id from snapshot(), not FakeDevice state.
        assertEquals("OK: tap next_button -> screen=second", tapped.content)
        assertEquals(
            "screen=second title=Second\n[done_label] label Done",
            observe.execute(invocation(emptyMap())).content
        )
    }

    @Test
    fun actToolPausesHighRiskNodesOnAnAlternativeSurface() {
        val surface = TinySurface()
        val act = DeviceActTool(surface, RiskPolicy(highRiskNodeIds = setOf("next_button")))

        val paused = act.execute(invocation(mapOf("action" to "tap", "node" to "next_button")))

        assertFalse(paused.isError)
        assertEquals(
            "PAUSED_HIGH_RISK: 'Next' requires explicit user confirmation. " +
                "Re-invoke with confirmed=true after the user approves.",
            paused.content
        )
        assertEquals("first", surface.snapshot().id)
    }

    @Test
    fun unknownNodeMessageUsesSnapshotScreenIdAndOffersCandidates() {
        val act = DeviceActTool(TinySurface(), RiskPolicy())

        val result = act.execute(invocation(mapOf("action" to "tap", "node" to "missing")))

        assertTrue(result.isError)
        assertEquals(
            "TARGET_NOT_FOUND: Unknown node 'missing' on screen 'first'.\n" +
                "candidates:\n" +
                "[note_field] input Note\n" +
                "[next_button] button Next",
            result.content
        )
    }

    /**
     * A surface that implements only the mandatory three methods must still be
     * fully usable: the optional actions fail cleanly instead of throwing, and
     * the implicit settle after a successful action is ignored.
     */
    @Test
    fun optionalActionsFailCleanlyOnAMinimalSurface() {
        val act = DeviceActTool(TinySurface(), RiskPolicy())

        val back = act.execute(invocation(mapOf("action" to "back")))

        assertTrue(back.isError)
        assertEquals(
            "UNSUPPORTED_ACTION: This device surface does not support 'back': " +
                "This device surface does not implement back().",
            back.content
        )
    }
}
