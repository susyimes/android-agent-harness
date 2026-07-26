// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Node ids must identify a CONTROL, not a position.
 *
 * The failure these tests exist for: with positional ids the model observes
 * "n7 = Delete", a banner loads or the list scrolls by a few pixels, and the
 * very next tap on "n7" lands on whatever slid into seventh place.
 */
class AccessibilityStableNodeIdTest {

    private fun idOf(node: UiNodeReader, pkg: String = "org.example.app"): String {
        val root = FakeUiNode(className = "android.widget.FrameLayout", childNodes = listOf(node))
        return AccessibilityScreenMapper.map(root, pkg, "Inbox").screen.nodes.single().id
    }

    private fun payButton(top: Int, viewId: String? = "org.example.app:id/pay_button") =
        FakeUiNode(
            className = "android.widget.Button",
            text = "Pay 12.50",
            viewIdResourceName = viewId,
            isClickable = true,
            boundsInScreen = NodeBounds(24, top, 336, top + 60)
        )

    @Test
    fun scrollingANodeByAFewPixelsKeepsItsId() {
        assertEquals(idOf(payButton(top = 100)), idOf(payButton(top = 103)))
        assertEquals(idOf(payButton(top = 100)), idOf(payButton(top = 97)))
    }

    @Test
    fun scrollingANodeWithoutAViewIdByAFewPixelsAlsoKeepsItsId() {
        assertEquals(
            idOf(payButton(top = 100, viewId = null)),
            idOf(payButton(top = 103, viewId = null))
        )
    }

    @Test
    fun aDifferentControlOnTheSameScreenGetsADifferentId() {
        val root = FakeUiNode(
            className = "android.widget.LinearLayout",
            childNodes = listOf(
                FakeUiNode(
                    className = "android.widget.Button",
                    text = "Pay 12.50",
                    viewIdResourceName = "org.example.app:id/pay_button",
                    isClickable = true,
                    boundsInScreen = NodeBounds(24, 100, 336, 160)
                ),
                FakeUiNode(
                    className = "android.widget.Button",
                    text = "Cancel",
                    viewIdResourceName = "org.example.app:id/cancel_button",
                    isClickable = true,
                    boundsInScreen = NodeBounds(24, 200, 336, 260)
                )
            )
        )

        val ids = AccessibilityScreenMapper.map(root, "org.example.app", "Checkout")
            .screen.nodes.map { node -> node.id }

        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun movingANodeAcrossThePageGivesItANewId() {
        assertNotEquals(idOf(payButton(top = 100)), idOf(payButton(top = 900)))
    }

    @Test
    fun insertingANodeAboveDoesNotRenameTheOneBelow() {
        val target = payButton(top = 400)
        val before = AccessibilityScreenMapper.map(
            FakeUiNode(className = "android.widget.LinearLayout", childNodes = listOf(target)),
            "org.example.app",
            "Checkout"
        )
        val banner = FakeUiNode(
            className = "android.widget.TextView",
            text = "Offer expires soon",
            boundsInScreen = NodeBounds(0, 0, 360, 80)
        )
        val after = AccessibilityScreenMapper.map(
            FakeUiNode(
                className = "android.widget.LinearLayout",
                childNodes = listOf(banner, target)
            ),
            "org.example.app",
            "Checkout"
        )

        assertEquals(1, before.screen.nodes.size)
        assertEquals(2, after.screen.nodes.size)
        // Positional ids would have moved the button from n1 to n2 here.
        assertEquals(before.screen.nodes.single().id, after.screen.nodes[1].id)
    }

    @Test
    fun idIsStableAcrossRepeatedMappingsOfTheSameTree() {
        val first = idOf(payButton(top = 100))
        val second = idOf(payButton(top = 100))

        assertEquals(first, second)
    }

    @Test
    fun theSameControlInAnotherAppGetsAnotherId() {
        assertNotEquals(
            idOf(payButton(top = 100), pkg = "org.example.app"),
            idOf(payButton(top = 100), pkg = "org.example.other")
        )
    }

    @Test
    fun aRelabelledControlGetsANewId() {
        val paid = FakeUiNode(
            className = "android.widget.Button",
            text = "Paid",
            viewIdResourceName = "org.example.app:id/pay_button",
            isClickable = true,
            boundsInScreen = NodeBounds(24, 100, 336, 160)
        )

        assertNotEquals(idOf(payButton(top = 100)), idOf(paid))
    }

    @Test
    fun idsAreShortHexTokens() {
        val id = idOf(payButton(top = 100))

        assertEquals(9, id.length)
        assertEquals("n", id.substring(0, 1))
        assertEquals(true, id.substring(1).all { character -> character in "0123456789abcdef" })
    }

    @Test
    fun nodesWithoutViewIdsAreDistinguishedByTheirTreePath() {
        val rows = (0..2).map {
            FakeUiNode(
                className = "android.widget.LinearLayout",
                childNodes = listOf(
                    FakeUiNode(
                        className = "android.widget.TextView",
                        text = "Remove",
                        isClickable = true,
                        boundsInScreen = NodeBounds(0, 0, 100, 40)
                    )
                )
            )
        }
        val root = FakeUiNode(className = "android.widget.ListView", childNodes = rows)

        val ids = AccessibilityScreenMapper.map(root, "org.example.app", "List")
            .screen.nodes.map { node -> node.id }

        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size)
    }
}
