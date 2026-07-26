// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import dev.androidagent.harness.deviceloop.RiskPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the mapped nodes carry beyond "id role label": the signals the tool
 * layer and the risk policy act on, and the one value that must never leave the
 * device.
 */
class AccessibilityNodeSignalsTest {

    private fun map(root: UiNodeReader) =
        AccessibilityScreenMapper.map(root, "org.example.app", "Account")

    @Test
    fun credentialFieldsNeverExposeTheirValue() {
        val secret = "correct horse battery staple"
        val root = FakeUiNode(
            className = "android.widget.EditText",
            text = secret,
            contentDescription = "Login secret",
            viewIdResourceName = "org.example.app:id/credential_input",
            isEditable = true,
            isPassword = true
        )

        val node = map(root).screen.nodes.single()

        assertEquals(AccessibilityScreenMapper.REDACTED_VALUE, node.text)
        assertEquals("Login secret", node.label)
        assertFalse(node.label.contains(secret))
        assertFalse(node.text.orEmpty().contains(secret))
    }

    @Test
    fun credentialFieldWithoutDescriptionFallsBackToViewIdThenRole() {
        val withViewId = FakeUiNode(
            className = "android.widget.EditText",
            text = "s3cret",
            viewIdResourceName = "org.example.app:id/credential_input",
            isEditable = true,
            isPassword = true
        )
        val bare = FakeUiNode(
            className = "android.widget.EditText",
            text = "s3cret",
            isEditable = true,
            isPassword = true
        )

        assertEquals("credential_input", map(withViewId).screen.nodes.single().label)
        assertEquals("edittext", map(bare).screen.nodes.single().label)
        assertFalse(map(bare).screen.nodes.single().label.contains("s3cret"))
    }

    @Test
    fun disabledNodesAreMarkedSoTheModelStopsClickingThem() {
        val root = FakeUiNode(
            className = "android.widget.LinearLayout",
            childNodes = listOf(
                FakeUiNode(
                    className = "android.widget.Button",
                    text = "Continue",
                    isClickable = true,
                    isEnabled = false,
                    boundsInScreen = NodeBounds(0, 0, 200, 60)
                ),
                FakeUiNode(
                    className = "android.widget.Button",
                    text = "Back",
                    isClickable = true,
                    boundsInScreen = NodeBounds(0, 100, 200, 160)
                )
            )
        )

        val nodes = map(root).screen.nodes

        assertFalse(nodes[0].enabled)
        assertTrue(nodes[1].enabled)
    }

    @Test
    fun clickableEditableAndViewIdAreReported() {
        val root = FakeUiNode(
            className = "android.widget.LinearLayout",
            childNodes = listOf(
                FakeUiNode(
                    className = "android.widget.EditText",
                    text = "ada@example.org",
                    viewIdResourceName = "org.example.app:id/recipient_field",
                    isEditable = true,
                    boundsInScreen = NodeBounds(0, 0, 300, 60)
                ),
                FakeUiNode(
                    className = "android.widget.TextView",
                    text = "Balance 42.00",
                    viewIdResourceName = "org.example.app:id/balance_label",
                    boundsInScreen = NodeBounds(0, 100, 300, 140)
                )
            )
        )

        val nodes = map(root).screen.nodes

        assertTrue(nodes[0].editable)
        assertFalse(nodes[0].clickable)
        assertEquals("recipient_field", nodes[0].viewId)
        assertEquals("ada@example.org", nodes[0].text)
        assertFalse(nodes[1].editable)
        assertEquals("balance_label", nodes[1].viewId)
    }

    @Test
    fun viewIdIsAbsentRatherThanBlankWhenTheViewHasNone() {
        val root = FakeUiNode(className = "android.widget.TextView", text = "Plain")

        assertNull(map(root).screen.nodes.single().viewId)
    }

    /**
     * The risk gate classifies on the node values, so a long label must arrive
     * whole: truncating first is exactly how "Transfer all funds to account
     * 1234" turns into an unremarkable "Transfer all funds to acc..." that a
     * precise policy pattern no longer matches.
     */
    @Test
    fun longValuesReachTheRiskPolicyIntact() {
        val filler = "Please review the following instruction carefully. ".repeat(4)
        val message = filler + "Confirm to transfer all funds to account 1234."
        val root = FakeUiNode(
            className = "android.widget.TextView",
            text = message,
            isClickable = true
        )
        val policy = RiskPolicy(highRiskLabelPatterns = listOf(Regex("(?i)\\btransfer all funds\\b")))

        val node = map(root).screen.nodes.single()

        assertEquals(message, node.label)
        assertFalse(node.label.endsWith("..."))
        assertTrue(message.length > 200)
        assertTrue(policy.isHighRisk(node))
    }

    @Test
    fun viewIdReachesTheRiskPolicyToo() {
        val root = FakeUiNode(
            className = "android.widget.Button",
            contentDescription = "OK",
            viewIdResourceName = "org.example.app:id/btn_payment_submit",
            isClickable = true
        )
        val policy = RiskPolicy(highRiskLabelPatterns = listOf(Regex("(?i)\\bpayment\\b")))

        assertTrue(policy.isHighRisk(map(root).screen.nodes.single()))
    }

    @Test
    fun onlyPathologicalValuesAreClampedAndNeverWithAnEllipsis() {
        val huge = "x".repeat(AccessibilityScreenMapper.MAX_VALUE_LENGTH * 3)
        val root = FakeUiNode(className = "android.widget.TextView", text = huge)

        val node = map(root).screen.nodes.single()

        assertEquals(AccessibilityScreenMapper.MAX_VALUE_LENGTH, node.label.length)
        assertFalse(node.label.endsWith("..."))
    }
}
