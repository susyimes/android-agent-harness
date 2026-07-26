// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The traversal must terminate on any tree a third-party app can produce.
 *
 * A recursive walk answers a self-referencing virtual tree with
 * StackOverflowError, which is an Error: it goes straight through every
 * `catch (RuntimeException)` in the surface and the tool layer and takes the
 * hosting app with it. Every bound below is therefore load-bearing, and every
 * bound that fires has to be visible in the screen title — a silently cut tree
 * is a model confidently reporting that a control is not on screen.
 */
class AccessibilityTraversalLimitsTest {

    private fun map(root: UiNodeReader?) =
        AccessibilityScreenMapper.map(root, "org.example.app", "Feed")

    @Test
    fun selfReferencingTreeTerminatesAndIsNoted() {
        val looping = FakeUiNode(className = "android.widget.LinearLayout", text = "Loop")
        looping.children += looping
        val root = FakeUiNode(
            className = "android.widget.FrameLayout",
            childNodes = listOf(looping)
        )

        val screen = map(root).screen

        assertEquals(1, screen.nodes.size)
        assertEquals("Loop", screen.nodes.single().label)
        assertTrue(screen.title.contains("[repeated tree nodes skipped]"))
    }

    @Test
    fun mutuallyReferencingNodesTerminate() {
        val first = FakeUiNode(className = "android.widget.TextView", text = "First")
        val second = FakeUiNode(className = "android.widget.TextView", text = "Second")
        first.children += second
        second.children += first

        val screen = map(first).screen

        assertEquals(listOf("First", "Second"), screen.nodes.map { node -> node.label })
        assertTrue(screen.title.contains("[repeated tree nodes skipped]"))
    }

    @Test
    fun deepTreeStopsAtTheDepthCap() {
        var deepest = FakeUiNode(className = "android.widget.TextView", text = "Bottom")
        repeat(AccessibilityScreenMapper.MAX_DEPTH + 40) { index ->
            deepest = FakeUiNode(
                className = "android.widget.LinearLayout",
                text = "Level $index",
                childNodes = listOf(deepest)
            )
        }

        val screen = map(deepest).screen

        assertTrue(
            screen.title,
            screen.title.contains("[tree truncated below depth ${AccessibilityScreenMapper.MAX_DEPTH}]")
        )
        assertTrue(screen.nodes.size <= AccessibilityScreenMapper.MAX_NODES)
    }

    @Test
    fun shallowTreeIsNotReportedAsTruncated() {
        val root = FakeUiNode(
            className = "android.widget.FrameLayout",
            childNodes = listOf(FakeUiNode(className = "android.widget.TextView", text = "Only"))
        )

        assertEquals("Feed", map(root).screen.title)
    }

    @Test
    fun absurdlyWideNodeIsTruncatedAndNoted() {
        val children = (1..AccessibilityScreenMapper.MAX_CHILDREN_PER_NODE + 25).map { index ->
            FakeUiNode(
                className = "android.widget.TextView",
                text = "Row $index",
                boundsInScreen = NodeBounds(0, index * 30, 300, index * 30 + 28)
            )
        }
        val root = FakeUiNode(className = "android.widget.ListView", childNodes = children)

        val screen = map(root).screen

        assertTrue(
            screen.title,
            screen.title.contains(
                "[nodes wider than ${AccessibilityScreenMapper.MAX_CHILDREN_PER_NODE} children truncated]"
            )
        )
        assertEquals(AccessibilityScreenMapper.MAX_NODES, screen.nodes.size)
    }

    @Test
    fun totalScanIsCappedAndNoted() {
        val branches = (1..300).map { branch ->
            FakeUiNode(
                className = "android.widget.LinearLayout",
                childNodes = (1..20).map { leaf ->
                    FakeUiNode(
                        className = "android.widget.TextView",
                        text = "Cell $branch-$leaf",
                        boundsInScreen = NodeBounds(0, leaf * 30, 300, leaf * 30 + 28)
                    )
                }
            )
        }
        val root = FakeUiNode(className = "android.widget.ScrollView", childNodes = branches)

        val screen = map(root).screen

        assertTrue(
            screen.title,
            screen.title.contains(
                "[tree scan stopped after ${AccessibilityScreenMapper.MAX_SCANNED_NODES} nodes]"
            )
        )
        assertEquals(AccessibilityScreenMapper.MAX_NODES, screen.nodes.size)
    }

    @Test
    fun aNodeThatThrowsWhileBeingReadDoesNotFailTheSnapshot() {
        val root = FakeUiNode(
            className = "android.widget.LinearLayout",
            childNodes = listOf(
                FakeUiNode(className = "android.widget.TextView", text = "Before"),
                FakeUiNode(
                    className = "android.widget.Button",
                    text = "Recycled",
                    isClickable = true,
                    failingReads = true,
                    boundsInScreen = NodeBounds(0, 100, 200, 140)
                ),
                FakeUiNode(className = "android.widget.TextView", text = "After")
            )
        )

        val screen = map(root).screen

        assertEquals(listOf("Before", "button", "After"), screen.nodes.map { node -> node.label })
    }

    @Test
    fun nodeReportingANegativeChildCountIsHandled() {
        val root = object : UiNodeReader by FakeUiNode(
            className = "android.widget.TextView",
            text = "Odd"
        ) {
            override val childCount: Int get() = -3
        }

        assertEquals(1, map(root).screen.nodes.size)
    }
}
