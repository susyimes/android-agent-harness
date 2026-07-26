// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

/**
 * Screen rectangle of one node, in device pixels.
 *
 * A pure value type on purpose: it keeps android.graphics.Rect out of the
 * [UiNodeReader] seam, so the whole screen mapping (including the stable node
 * ids, which are derived from bounds) stays unit-testable on a plain JVM.
 */
data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left

    val height: Int get() = bottom - top

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        /** Bounds of a node the platform could not measure. */
        val EMPTY = NodeBounds(0, 0, 0, 0)
    }
}

/**
 * Read-only view of one accessibility-tree node.
 *
 * This is the seam between the live Android accessibility tree and the pure
 * screen mapping: [AccessibilityScreenMapper] only ever sees this interface, so
 * the tree-to-DeviceScreen mapping is unit-testable on the JVM with fake trees
 * and no Android classes. The production implementation wraps a live
 * android.view.accessibility.AccessibilityNodeInfo.
 *
 * IDENTITY CONTRACT: the mapper walks the tree with an explicit visited set to
 * survive the self-referencing virtual trees some third-party apps expose, and
 * that set uses `equals`/`hashCode`. An implementation that creates a fresh
 * wrapper per [child] call (the production one does) MUST therefore delegate
 * `equals`/`hashCode` to the underlying platform node; otherwise a cycle is
 * never recognised and the walk only stops at the node cap.
 *
 * FAILURE CONTRACT: every member may throw on a live tree (a node can be
 * recycled or sealed underneath the reader). The mapper treats such failures as
 * "no information" for the node in question rather than failing the snapshot.
 */
interface UiNodeReader {
    /** Number of direct children of this node. */
    val childCount: Int

    /** The child at [index], or null when the child cannot be resolved. */
    fun child(index: Int): UiNodeReader?

    /** Fully qualified view class name, for example android.widget.Button. */
    val className: CharSequence?

    /** The node's own text, for example a label or the current field content. */
    val text: CharSequence?

    /** Accessibility content description, when the view declares one. */
    val contentDescription: CharSequence?

    /** Fully qualified view id resource name, for example pkg:id/pay_button. */
    val viewIdResourceName: String?

    /** True when the node reacts to click actions. */
    val isClickable: Boolean

    /** True when the node accepts text input. */
    val isEditable: Boolean

    /** True when the node is currently visible to the user. */
    val isVisibleToUser: Boolean

    /** True when the node is enabled for interaction. */
    val isEnabled: Boolean

    /**
     * True when the platform marks this node as holding a credential.
     *
     * SECURITY: the mapped screen is sent verbatim to a model, so the value of
     * such a node is never reported; [AccessibilityScreenMapper] substitutes a
     * fixed placeholder and never derives the node label from it either.
     */
    val isPassword: Boolean

    /**
     * Bounds of the node in screen coordinates.
     *
     * Used only in coarse (grid-rounded) form, as one component of the stable
     * node id, and as the tap-gesture target on the surface.
     */
    val boundsInScreen: NodeBounds
}
