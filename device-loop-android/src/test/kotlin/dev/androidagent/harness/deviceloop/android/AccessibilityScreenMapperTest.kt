// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityScreenMapperTest {

    private fun map(
        root: UiNodeReader?,
        pkg: String? = "org.example.app",
        title: String? = "Checkout"
    ) = AccessibilityScreenMapper.map(root, pkg, title)

    @Test
    fun includesOnlyVisibleNodesWithContentOrInteractivity() {
        val root = FakeUiNode(
            className = "android.widget.LinearLayout",
            childNodes = listOf(
                FakeUiNode(className = "android.widget.TextView", text = "Hello"),
                FakeUiNode(
                    className = "android.widget.Button",
                    text = "Hidden",
                    isClickable = true,
                    isVisibleToUser = false
                ),
                FakeUiNode(className = "android.view.View"),
                FakeUiNode(className = "android.view.View", isClickable = true),
                FakeUiNode(className = "android.widget.TextView", text = "   \n\t  ")
            )
        )

        val screen = map(root).screen

        assertEquals(2, screen.nodes.size)
        assertEquals("Hello", screen.nodes[0].label)
        assertEquals("button", screen.nodes[1].role)
    }

    @Test
    fun reportsNodesInDepthFirstPreorder() {
        val root = FakeUiNode(
            className = "android.widget.FrameLayout",
            childNodes = listOf(
                FakeUiNode(
                    className = "android.widget.LinearLayout",
                    childNodes = listOf(
                        FakeUiNode(className = "android.widget.TextView", text = "First"),
                        FakeUiNode(className = "android.widget.TextView", text = "Second")
                    )
                ),
                FakeUiNode(className = "android.widget.Button", text = "Third", isClickable = true)
            )
        )

        val mapped = map(root)

        assertEquals(listOf("First", "Second", "Third"), mapped.screen.nodes.map { it.label })
        assertEquals(mapped.screen.nodes.map { it.id }, mapped.readerByNodeId.keys.toList())
    }

    @Test
    fun labelPrefersContentDescriptionThenTextThenViewIdSuffixThenRole() {
        val root = FakeUiNode(
            className = "android.widget.LinearLayout",
            childNodes = listOf(
                FakeUiNode(
                    className = "android.widget.Button",
                    contentDescription = "Pay now",
                    text = "Pay",
                    viewIdResourceName = "org.example.app:id/pay_button",
                    isClickable = true
                ),
                FakeUiNode(
                    className = "android.widget.Button",
                    text = "Cancel",
                    viewIdResourceName = "org.example.app:id/cancel_button",
                    isClickable = true
                ),
                FakeUiNode(
                    className = "android.widget.Button",
                    viewIdResourceName = "org.example.app:id/retry_button",
                    isClickable = true
                ),
                FakeUiNode(className = "android.view.View", isClickable = true)
            )
        )

        val labels = map(root).screen.nodes.map { node -> node.label }

        assertEquals(listOf("Pay now", "Cancel", "retry_button", "button"), labels)
    }

    @Test
    fun collapsesWhitespaceInLabels() {
        val root = FakeUiNode(
            className = "android.widget.TextView",
            text = "  Total:\n\t42 USD  "
        )

        assertEquals("Total: 42 USD", map(root).screen.nodes.single().label)
    }

    @Test
    fun capsNodeCountAndNotesTruncationInTitle() {
        val children = (1..65).map { index ->
            FakeUiNode(
                className = "android.widget.TextView",
                text = "Item $index",
                boundsInScreen = NodeBounds(0, index * 40, 400, index * 40 + 36)
            )
        }
        val root = FakeUiNode(className = "android.widget.ListView", childNodes = children)

        val screen = map(root).screen

        assertEquals(AccessibilityScreenMapper.MAX_NODES, screen.nodes.size)
        assertEquals("Item 60", screen.nodes.last().label)
        assertTrue(screen.title.endsWith("[showing first 60 of 65 nodes]"))
    }

    @Test
    fun untruncatedScreenTitleHasNoNote() {
        val root = FakeUiNode(className = "android.widget.TextView", text = "Only")

        assertEquals("Checkout", map(root).screen.title)
    }

    @Test
    fun nullRootYieldsEmptyScreen() {
        val mapped = map(null)

        assertTrue(mapped.screen.nodes.isEmpty())
        assertTrue(mapped.readerByNodeId.isEmpty())
        assertTrue(mapped.screen.id.startsWith("s"))
        assertEquals("Checkout", mapped.screen.title)
    }

    @Test
    fun treeWithoutQualifyingNodesYieldsEmptyScreen() {
        val root = FakeUiNode(
            className = "android.widget.FrameLayout",
            childNodes = listOf(FakeUiNode(className = "android.view.View"))
        )

        assertTrue(map(root).screen.nodes.isEmpty())
    }

    @Test
    fun titleFallsBackToPackageNameThenUnknown() {
        assertEquals(
            "org.example.app",
            map(null, pkg = "org.example.app", title = null).screen.title
        )
        assertEquals("unknown", map(null, pkg = null, title = null).screen.title)
    }

    @Test
    fun rolesFollowDocumentedRule() {
        val root = FakeUiNode(
            className = "android.widget.LinearLayout",
            childNodes = listOf(
                FakeUiNode(className = "android.widget.Button", text = "A", isClickable = true),
                FakeUiNode(className = "android.widget.EditText", isEditable = true),
                FakeUiNode(className = "android.widget.TextView", text = "C"),
                FakeUiNode(className = "android.widget.ImageView", contentDescription = "D"),
                FakeUiNode(className = "android.widget.LinearLayout", isClickable = true),
                FakeUiNode(className = "org.example.CustomView", contentDescription = "F"),
                FakeUiNode(
                    className = "android.widget.ImageButton",
                    contentDescription = "G",
                    isClickable = true
                )
            )
        )

        val roles = map(root).screen.nodes.map { node -> node.role }

        assertEquals(
            listOf("button", "edittext", "textview", "imageview", "button", "other", "button"),
            roles
        )
    }

    @Test
    fun textReportedForEditableNodesAndWhenDifferentFromLabel() {
        val root = FakeUiNode(
            className = "android.widget.LinearLayout",
            childNodes = listOf(
                FakeUiNode(
                    className = "android.widget.EditText",
                    text = "typed value",
                    isEditable = true
                ),
                FakeUiNode(className = "android.widget.TextView", text = "Same label"),
                FakeUiNode(
                    className = "android.widget.Button",
                    contentDescription = "Submit order",
                    text = "Submit",
                    isClickable = true
                ),
                FakeUiNode(className = "android.widget.EditText", isEditable = true)
            )
        )

        val nodes = map(root).screen.nodes

        assertEquals("typed value", nodes[0].text)
        assertNull(nodes[1].text)
        assertEquals("Submit", nodes[2].text)
        assertNull(nodes[3].text)
    }

    @Test
    fun screenIdIsDeterministicAndIgnoresTruncationNote() {
        val small = FakeUiNode(className = "android.widget.TextView", text = "One")
        val big = FakeUiNode(
            className = "android.widget.ListView",
            childNodes = (1..70).map { index ->
                FakeUiNode(
                    className = "android.widget.TextView",
                    text = "Item $index",
                    boundsInScreen = NodeBounds(0, index * 40, 400, index * 40 + 36)
                )
            }
        )

        val first = map(small).screen
        val second = map(small).screen
        val truncated = map(big).screen

        assertEquals(first.id, second.id)
        assertEquals(first.id, truncated.id)
        assertTrue(first.id.startsWith("s"))
    }

    @Test
    fun readerMappingPointsAtTheIncludedReaders() {
        val target = FakeUiNode(className = "android.widget.Button", text = "Go", isClickable = true)
        val root = FakeUiNode(
            className = "android.widget.FrameLayout",
            childNodes = listOf(FakeUiNode(className = "android.view.View"), target)
        )

        val mapped = map(root)
        val onlyId = mapped.screen.nodes.single().id

        assertSame(target, mapped.readerByNodeId.getValue(onlyId))
    }

    @Test
    fun descendsIntoChildrenOfExcludedContainers() {
        val root = FakeUiNode(
            className = "android.widget.ScrollView",
            childNodes = listOf(
                FakeUiNode(
                    className = "android.widget.LinearLayout",
                    childNodes = listOf(
                        FakeUiNode(className = "android.widget.TextView", text = "Deep child")
                    )
                )
            )
        )

        val screen = map(root).screen

        assertEquals(1, screen.nodes.size)
        assertEquals("Deep child", screen.nodes.single().label)
    }

    @Test
    fun everyNodeIdIsUniqueEvenWhenControlsAreIdentical() {
        val identical = (1..8).map {
            FakeUiNode(
                className = "android.widget.Button",
                text = "Delete",
                viewIdResourceName = "org.example.app:id/delete",
                isClickable = true,
                boundsInScreen = NodeBounds(0, 0, 120, 48)
            )
        }
        val root = FakeUiNode(className = "android.widget.ListView", childNodes = identical)

        val ids = map(root).screen.nodes.map { node -> node.id }

        assertEquals(8, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.any { id -> id.isBlank() })
    }
}
