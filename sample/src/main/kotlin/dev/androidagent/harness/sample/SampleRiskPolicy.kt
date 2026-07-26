// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.deviceloop.RiskPolicy

/**
 * The sample app's explicit high-risk classification for phone-mode actions.
 *
 * A node is high-risk when its label matches any of the case-insensitive
 * patterns below (the [RiskPolicy] uses containsMatchIn, so `\b` word
 * boundaries keep short words like "pay" from matching inside longer words).
 * The list is deliberately declared here in the app, not hidden inside the
 * library: governance stays configuration-driven and reviewable.
 *
 * Covered label families: paying and purchasing, money transfer, destructive
 * deletion and uninstall, and order-confirmation style buttons.
 */
object SampleRiskPolicy {

    private val highRiskLabelPatterns = listOf(
        "\\bpay\\b",
        "\\bpayment\\b",
        "\\bpurchase\\b",
        "\\bbuy now\\b",
        "\\bcheckout\\b",
        "\\btransfer\\b",
        "\\bsend money\\b",
        "\\bdelete\\b",
        "\\buninstall\\b",
        "\\bconfirm order\\b",
        "\\bplace order\\b",
        "\\bsubmit order\\b"
    ).map { pattern -> Regex(pattern, RegexOption.IGNORE_CASE) }

    /** A fresh policy instance carrying the documented label patterns. */
    fun policy(): RiskPolicy = RiskPolicy(highRiskLabelPatterns = highRiskLabelPatterns)
}
