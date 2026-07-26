// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

/**
 * One semantic UI node, mirroring an accessibility-tree entry.
 *
 * Required fields ([id], [role], [label]) describe every surface. The optional
 * fields carry the extra signal a real phone exposes; they all have defaults so
 * a surface (or a test) only states what it actually knows:
 *
 * - [text]: the node's own text, separate from its label. On a real device the
 *   label is often a content description while the text is the editable value,
 *   and long values are frequently truncated for display. Classification
 *   ([RiskPolicy]) therefore reads label AND text AND [viewId], never the
 *   rendered line.
 * - [viewId]: the platform view id (for example the suffix of a view id
 *   resource name). It survives localization, so it is the most reliable
 *   signal for both re-targeting and risk matching.
 * - [clickable]: whether the platform reports the node itself as clickable. A
 *   non-clickable node is still tappable on real devices (surfaces fall back to
 *   a clickable ancestor or a coordinate gesture), so tools must not refuse a
 *   tap on this flag alone.
 * - [editable]: whether the node accepts text entry.
 * - [enabled]: whether the node is currently actionable. Tapping a disabled
 *   node is the classic silent no-op that makes agents loop forever, so
 *   [DeviceActTool] refuses it instead of pretending it worked.
 */
data class DeviceNode(
    val id: String,
    val role: String,
    val label: String,
    val text: String? = null,
    val viewId: String? = null,
    val clickable: Boolean = true,
    val editable: Boolean = false,
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "Node id must not be blank." }
        require(role.isNotBlank()) { "Node role must not be blank." }
        require(label.isNotBlank()) { "Node label must not be blank." }
    }
}

/** One device screen with its nodes in traversal (declaration) order. */
data class DeviceScreen(
    val id: String,
    val title: String,
    val nodes: List<DeviceNode>
) {
    init {
        require(id.isNotBlank()) { "Screen id must not be blank." }
        require(title.isNotBlank()) { "Screen title must not be blank." }
        val duplicates = nodes.groupingBy { node -> node.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicates.isEmpty()) {
            "Screen '$id' has duplicate node ids: ${duplicates.sorted().joinToString()}."
        }
    }
}
