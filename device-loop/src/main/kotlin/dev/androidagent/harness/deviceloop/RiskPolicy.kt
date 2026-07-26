// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import java.util.Locale

/**
 * Explicit high-risk classification for device nodes.
 *
 * Governance stays configuration-driven: a node is high-risk only because of
 * what the caller configured. There are no built-in risk words.
 *
 * WHAT IS MATCHED. The old policy matched [DeviceNode.label] only, which is
 * trivially bypassable on a real phone: the dangerous word very often lives in
 * the node's text or in its view id while the label is a generic caption, an
 * icon description, or a truncated string. This policy therefore matches every
 * configured pattern against label AND [DeviceNode.text] AND [DeviceNode.viewId],
 * always on the values exactly as the surface reported them. Never pre-truncate
 * or pre-render values for classification: display truncation ("Transfer all
 * funds to acc...") happens after this decision, never before it. View ids get
 * their separators normalized to spaces first, so word-boundary patterns work
 * on "btn_payment_submit" too.
 *
 * CONTEXTUAL INFERENCE. The most dangerous button on a phone is usually
 * labelled "OK". A confirmation dialog moves the risk vocabulary from the
 * button to the surrounding screen, so [isHighRisk] with a [DeviceScreen]
 * escalates a target whose whole label is a generic confirm word (see
 * [DEFAULT_GENERIC_CONFIRM_LABELS]) when the rest of the screen — its title
 * plus the labels of the other nodes — contains configured risk vocabulary.
 * The single-argument [isHighRisk] keeps the old, context-free behavior for
 * callers that only have a node.
 *
 * FALSE-POSITIVE HAZARD. Substring vocabulary fires inside unrelated words and
 * brand names: "pay" matches "Alipay", "Paypal" and "Payless"; "transfer" fires
 * on "Transfer market news"; a Chinese pattern can appear in an app name. Every
 * false positive costs a human confirmation, and operators who are asked too
 * often start approving without reading — which is strictly worse than not
 * pausing at all. Two mitigations are available:
 * - Write precise patterns (word boundaries, `\bpay\b` rather than `pay`).
 * - Pass [exemptSubstrings]: each entry is masked out (case-insensitively)
 *   before matching, so "Alipay wallet" with "alipay" exempt is not risky while
 *   "Alipay: pay 12.50" still is, because the second "pay" survives masking.
 *   Masking deliberately does NOT apply to [highRiskNodeIds]: an id the
 *   operator listed by hand is always risky.
 *
 * All of it is inert by default: an unconfigured policy flags nothing.
 */
class RiskPolicy(
    private val highRiskNodeIds: Set<String> = emptySet(),
    private val highRiskLabelPatterns: List<Regex> = emptyList(),
    exemptSubstrings: Set<String> = emptySet(),
    genericConfirmLabels: Set<String> = DEFAULT_GENERIC_CONFIRM_LABELS
) {
    private val exemptSubstrings: List<String> = exemptSubstrings
        .filter { substring -> substring.isNotBlank() }
        .sortedByDescending { substring -> substring.length }
    private val genericConfirmLabels: Set<String> = genericConfirmLabels
        .map { label -> normalizeLabel(label) }
        .filter { label -> label.isNotEmpty() }
        .toSet()

    /**
     * Context-free classification: id list, or risk vocabulary in the node's own
     * label, text or view id.
     *
     * Convenience for callers that hold a node but no screen. It cannot see
     * confirmation dialogs, so prefer the two-argument overload wherever a
     * snapshot is available.
     */
    fun isHighRisk(node: DeviceNode): Boolean {
        if (node.id in highRiskNodeIds) {
            return true
        }
        return matchesVocabulary(node.label) ||
            matchesVocabulary(node.text) ||
            matchesViewId(node.viewId)
    }

    /**
     * Classification with screen context: the context-free rules first, then
     * generic-confirm-word escalation against [screen].
     */
    fun isHighRisk(node: DeviceNode, screen: DeviceScreen): Boolean {
        if (isHighRisk(node)) {
            return true
        }
        if (!isGenericConfirmLabel(node.label)) {
            return false
        }
        return screenVocabularyIsRisky(node, screen)
    }

    /** True when the WHOLE label is a generic confirm word such as "OK" or "确认". */
    fun isGenericConfirmLabel(label: String): Boolean {
        return normalizeLabel(label) in genericConfirmLabels
    }

    /** True when the screen title or another node's label carries risk vocabulary. */
    private fun screenVocabularyIsRisky(node: DeviceNode, screen: DeviceScreen): Boolean {
        if (matchesVocabulary(screen.title)) {
            return true
        }
        return screen.nodes.any { other ->
            other.id != node.id && matchesVocabulary(other.label)
        }
    }

    private fun matchesVocabulary(value: String?): Boolean {
        if (value == null) {
            return false
        }
        val masked = maskExempt(value)
        return highRiskLabelPatterns.any { pattern -> pattern.containsMatchIn(masked) }
    }

    /**
     * View ids are matched with their separators turned into spaces.
     *
     * Regex word boundaries do not exist inside "btn_payment_submit" (an
     * underscore is a word character), so the careful `\bpayment\b` pattern an
     * operator writes for labels would silently never fire on view ids — the
     * one value that does not change with the device language. Splitting on
     * `_ - . /` makes the same pattern work on both.
     */
    private fun matchesViewId(viewId: String?): Boolean {
        if (viewId == null) {
            return false
        }
        return matchesVocabulary(viewId.replace(VIEW_ID_SEPARATORS, " "))
    }

    /** Replaces every exempt substring with a space so neighbours never merge. */
    private fun maskExempt(value: String): String {
        var masked = value
        exemptSubstrings.forEach { exempt ->
            masked = masked.replace(exempt, " ", ignoreCase = true)
        }
        return masked
    }

    private fun normalizeLabel(label: String): String {
        return label.lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .trim()
            .trim(*TRIMMED_PUNCTUATION)
            .trim()
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Separators that split a view id into words: btn_payment_submit. */
        private val VIEW_ID_SEPARATORS = Regex("[._\\-/]+")

        /** Decoration that surrounds confirm buttons without changing their meaning. */
        private val TRIMMED_PUNCTUATION = charArrayOf(
            '.', '!', '?', ':', ',', ';', '"', '\'', '(', ')', '[', ']',
            '。', '！', '？', '：', '，', '、',
            '“', '”', '（', '）', '…'
        )

        /**
         * Labels that say nothing about what will happen — the whole point of
         * contextual inference.
         *
         * English: confirm, ok, okay, continue, next, agree, allow, yes.
         * Chinese (Simplified and the Traditional spellings that differ):
         * 确认 / 確認 (confirm), 确定 / 確定 (OK), 好 and 好的 (fine),
         * 继续 / 繼續 (continue), 下一步 (next), 同意 (agree), 允许 / 允許 (allow),
         * 是 and 是的 (yes), 知道了 (got it).
         *
         * Matching is on the whole normalized label, never a substring: a label
         * like "Confirm payment" is not generic, and it is caught directly by
         * the configured vocabulary anyway.
         */
        val DEFAULT_GENERIC_CONFIRM_LABELS: Set<String> = setOf(
            "confirm",
            "ok",
            "okay",
            "continue",
            "next",
            "agree",
            "allow",
            "yes",
            "确认",
            "確認",
            "确定",
            "確定",
            "好",
            "好的",
            "继续",
            "繼續",
            "下一步",
            "同意",
            "允许",
            "允許",
            "是",
            "是的",
            "知道了"
        )
    }
}
