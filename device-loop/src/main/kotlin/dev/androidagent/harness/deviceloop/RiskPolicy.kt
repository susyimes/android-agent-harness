// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

/**
 * Explicit high-risk classification for device nodes.
 *
 * Governance stays configuration-driven: a node is high-risk only when its id is listed
 * or its label matches one of the configured patterns. There are no built-in magic lists.
 */
class RiskPolicy(
    private val highRiskNodeIds: Set<String> = emptySet(),
    private val highRiskLabelPatterns: List<Regex> = emptyList()
) {
    fun isHighRisk(node: DeviceNode): Boolean {
        return node.id in highRiskNodeIds ||
            highRiskLabelPatterns.any { pattern -> pattern.containsMatchIn(node.label) }
    }
}
