// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

/**
 * In-memory [UiNodeReader] for the JVM tests.
 *
 * [children] is a mutable list so a test can build a cycle (a node that is its
 * own descendant) after construction, which is exactly the shape that used to
 * end a snapshot in StackOverflowError.
 *
 * The reader-level failure mode is modelled too: [failingReads] makes the value
 * getters throw, the way a recycled or sealed platform node does.
 */
internal class FakeUiNode(
    className: CharSequence? = null,
    text: CharSequence? = null,
    contentDescription: CharSequence? = null,
    override val viewIdResourceName: String? = null,
    override val isClickable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isVisibleToUser: Boolean = true,
    override val isEnabled: Boolean = true,
    override val isPassword: Boolean = false,
    override val boundsInScreen: NodeBounds = NodeBounds(0, 0, 100, 40),
    private val failingReads: Boolean = false,
    childNodes: List<UiNodeReader> = emptyList()
) : UiNodeReader {

    private val classNameValue: CharSequence? = className
    private val textValue: CharSequence? = text
    private val descriptionValue: CharSequence? = contentDescription

    val children: MutableList<UiNodeReader> = childNodes.toMutableList()

    override val childCount: Int get() = children.size

    override fun child(index: Int): UiNodeReader? = children.getOrNull(index)

    override val className: CharSequence?
        get() = read(classNameValue)

    override val text: CharSequence?
        get() = read(textValue)

    override val contentDescription: CharSequence?
        get() = read(descriptionValue)

    private fun read(value: CharSequence?): CharSequence? {
        if (failingReads) {
            throw IllegalStateException("This node has been recycled.")
        }
        return value
    }
}
