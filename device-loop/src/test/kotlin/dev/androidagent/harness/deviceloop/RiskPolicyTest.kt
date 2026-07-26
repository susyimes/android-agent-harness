// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskPolicyTest {

    private val payPatterns = listOf(
        Regex("\\bpay\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpayment\\b", RegexOption.IGNORE_CASE),
        Regex("\\btransfer\\b", RegexOption.IGNORE_CASE)
    )

    private fun screen(title: String, vararg nodes: DeviceNode): DeviceScreen {
        return DeviceScreen(id = "s1", title = title, nodes = nodes.toList())
    }

    @Test
    fun defaultPolicyFlagsNothing() {
        val policy = RiskPolicy()

        assertFalse(policy.isHighRisk(DeviceNode("pay_button", "button", "Pay")))
        assertFalse(policy.isHighRisk(DeviceNode("delete_button", "button", "Delete everything")))
    }

    @Test
    fun flagsNodesByConfiguredId() {
        val policy = RiskPolicy(highRiskNodeIds = setOf("pay_button"))

        assertTrue(policy.isHighRisk(DeviceNode("pay_button", "button", "Anything")))
        assertFalse(policy.isHighRisk(DeviceNode("cancel_button", "button", "Cancel")))
    }

    @Test
    fun flagsNodesByLabelPattern() {
        val policy = RiskPolicy(
            highRiskLabelPatterns = listOf(Regex("(?i)pay"), Regex("(?i)delete"))
        )

        assertTrue(policy.isHighRisk(DeviceNode("n1", "button", "Pay now")))
        assertTrue(policy.isHighRisk(DeviceNode("n2", "button", "DELETE account")))
        assertFalse(policy.isHighRisk(DeviceNode("n3", "button", "Cancel")))
    }

    @Test
    fun matchesTextAndViewIdNotOnlyTheLabel() {
        val policy = RiskPolicy(highRiskLabelPatterns = payPatterns)

        // A generic caption with the risk in the node's own text.
        assertTrue(
            policy.isHighRisk(
                DeviceNode("n1", "button", "Continue", text = "Transfer 1,200.00")
            )
        )
        // A localized/opaque label with the risk in the platform view id.
        assertTrue(
            policy.isHighRisk(
                DeviceNode("n2", "button", "Weiter", viewId = "btn_payment_submit")
            )
        )
        assertFalse(policy.isHighRisk(DeviceNode("n3", "button", "Weiter", viewId = "btn_next")))
    }

    @Test
    fun matchesTheUntruncatedValuesTheSurfaceReported() {
        val policy = RiskPolicy(highRiskLabelPatterns = payPatterns)
        // Display truncation cut the risky word out of the label; the full value
        // survives in the text, which is exactly why classification reads both.
        val truncated = DeviceNode(
            id = "n1",
            role = "button",
            label = "Send 1,200.00 to Ada Lovelace savings acc...",
            text = "Send 1,200.00 to Ada Lovelace savings account by instant transfer"
        )

        assertTrue(policy.isHighRisk(truncated))
    }

    @Test
    fun contextInferenceEscalatesGenericConfirmOnARiskyScreen() {
        val policy = RiskPolicy(highRiskLabelPatterns = payPatterns)
        val confirm = DeviceNode("n2", "button", "OK")
        val riskyScreen = screen(
            "Confirm payment",
            DeviceNode("n1", "textview", "Charge 12.50 to your card", clickable = false),
            confirm,
            DeviceNode("n3", "button", "Cancel")
        )

        assertTrue(policy.isHighRisk(confirm, riskyScreen))
        // The one-argument form is context free by design.
        assertFalse(policy.isHighRisk(confirm))
    }

    @Test
    fun contextInferenceAlsoReadsOtherNodeLabels() {
        val policy = RiskPolicy(highRiskLabelPatterns = payPatterns)
        val confirm = DeviceNode("n2", "button", "Continue")
        val riskyScreen = screen(
            "Review",
            DeviceNode("n1", "textview", "Transfer to Ada Lovelace", clickable = false),
            confirm
        )

        assertTrue(policy.isHighRisk(confirm, riskyScreen))
    }

    @Test
    fun contextInferenceLeavesGenericConfirmAloneOnABenignScreen() {
        val policy = RiskPolicy(highRiskLabelPatterns = payPatterns)
        val confirm = DeviceNode("n2", "button", "OK")
        val benignScreen = screen(
            "Notification settings",
            DeviceNode("n1", "textview", "Daily digest enabled", clickable = false),
            confirm,
            DeviceNode("n3", "button", "Cancel")
        )

        assertFalse(policy.isHighRisk(confirm, benignScreen))
    }

    @Test
    fun contextInferenceOnlyEscalatesGenericLabels() {
        val policy = RiskPolicy(highRiskLabelPatterns = payPatterns)
        val cancel = DeviceNode("n3", "button", "Cancel")
        val riskyScreen = screen(
            "Confirm payment",
            DeviceNode("n1", "textview", "Charge 12.50 to your card", clickable = false),
            cancel
        )

        assertFalse(policy.isHighRisk(cancel, riskyScreen))
    }

    @Test
    fun genericConfirmWordsCoverPunctuationCaseAndTheDocumentedChineseSet() {
        val policy = RiskPolicy(highRiskLabelPatterns = payPatterns)

        listOf("OK", "ok!", " Confirm ", "Continue", "Next", "Agree", "Allow", "Yes")
            .forEach { label ->
                assertTrue(label, policy.isGenericConfirmLabel(label))
            }
        listOf("确认", "确定", "好的", "继续", "下一步", "同意", "允许", "是").forEach { label ->
            assertTrue(label, policy.isGenericConfirmLabel(label))
        }
        listOf("Confirm payment", "Pay", "Cancel", "确认支付").forEach { label ->
            assertFalse(label, policy.isGenericConfirmLabel(label))
        }
    }

    @Test
    fun chineseConfirmButtonOnARiskyScreenIsEscalated() {
        val policy = RiskPolicy(highRiskLabelPatterns = listOf(Regex("支付"), Regex("转账")))
        val confirm = DeviceNode("n2", "button", "确认")
        val riskyScreen = screen(
            "支付确认",
            DeviceNode("n1", "textview", "12.50", clickable = false),
            confirm
        )
        val benignScreen = screen(
            "通知设置",
            DeviceNode("n1", "textview", "每日摘要", clickable = false),
            confirm
        )

        assertTrue(policy.isHighRisk(confirm, riskyScreen))
        assertFalse(policy.isHighRisk(confirm, benignScreen))
    }

    @Test
    fun allowlistExemptsBrandNamesThatContainRiskVocabulary() {
        val policy = RiskPolicy(
            highRiskLabelPatterns = listOf(Regex("(?i)pay")),
            exemptSubstrings = setOf("Alipay", "Paypal")
        )

        assertFalse(policy.isHighRisk(DeviceNode("n1", "button", "Alipay wallet")))
        assertFalse(policy.isHighRisk(DeviceNode("n2", "button", "PAYPAL settings")))
        // The exemption masks the brand, not the real action next to it.
        assertTrue(policy.isHighRisk(DeviceNode("n3", "button", "Alipay: pay 12.50")))
    }

    @Test
    fun allowlistAppliesToScreenContextButNeverToConfiguredNodeIds() {
        val policy = RiskPolicy(
            highRiskNodeIds = setOf("alipay_button"),
            highRiskLabelPatterns = listOf(Regex("(?i)pay")),
            exemptSubstrings = setOf("Alipay")
        )
        val node = DeviceNode("alipay_button", "button", "Alipay wallet")

        assertTrue(policy.isHighRisk(node))

        val confirm = DeviceNode("n2", "button", "OK")
        val brandOnlyScreen = screen(
            "Alipay",
            DeviceNode("n1", "textview", "Alipay wallet", clickable = false),
            confirm
        )
        assertFalse(policy.isHighRisk(confirm, brandOnlyScreen))
    }

    @Test
    fun customGenericConfirmVocabularyReplacesTheDefault() {
        val policy = RiskPolicy(
            highRiskLabelPatterns = payPatterns,
            genericConfirmLabels = setOf("go on")
        )
        val goOn = DeviceNode("n2", "button", "Go on")
        val ok = DeviceNode("n3", "button", "OK")
        val riskyScreen = screen("Confirm payment", goOn, ok)

        assertTrue(policy.isHighRisk(goOn, riskyScreen))
        assertFalse(policy.isHighRisk(ok, riskyScreen))
    }
}
