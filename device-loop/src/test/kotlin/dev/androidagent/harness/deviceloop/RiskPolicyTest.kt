// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskPolicyTest {

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
}
