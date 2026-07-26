// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * [UiNodeReader] backed by a live [AccessibilityNodeInfo].
 *
 * Every child obtained during traversal is appended to [obtained] so the
 * owner of the snapshot can recycle the whole batch once it is superseded.
 *
 * [equals] and [hashCode] delegate to the wrapped node, as the [UiNodeReader]
 * identity contract requires: a fresh wrapper is created per [child] call, so
 * without the delegation the mapper's visited set could never recognise a
 * self-referencing tree.
 */
internal class AccessibilityNodeInfoReader(
    val node: AccessibilityNodeInfo,
    private val obtained: MutableList<AccessibilityNodeInfo>
) : UiNodeReader {

    override val childCount: Int
        get() = node.childCount

    override fun child(index: Int): UiNodeReader? {
        val child = node.getChild(index) ?: return null
        obtained += child
        return AccessibilityNodeInfoReader(child, obtained)
    }

    override val className: CharSequence?
        get() = node.className

    override val text: CharSequence?
        get() = node.text

    override val contentDescription: CharSequence?
        get() = node.contentDescription

    override val viewIdResourceName: String?
        get() = node.viewIdResourceName

    override val isClickable: Boolean
        get() = node.isClickable

    override val isEditable: Boolean
        get() = node.isEditable

    override val isVisibleToUser: Boolean
        get() = node.isVisibleToUser

    override val isEnabled: Boolean
        get() = node.isEnabled

    override val isPassword: Boolean
        get() = node.isPassword

    override val boundsInScreen: NodeBounds
        get() {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        return other is AccessibilityNodeInfoReader && node == other.node
    }

    override fun hashCode(): Int = node.hashCode()
}
