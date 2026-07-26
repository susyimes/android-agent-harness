// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import dev.androidagent.harness.deviceloop.DeviceNode
import dev.androidagent.harness.deviceloop.DeviceScreen
import java.util.Locale

/**
 * Result of mapping one accessibility tree: the semantic [screen] plus the
 * reader behind every node id, so a device surface can resolve a node id back
 * to the live tree node when the agent acts on it.
 */
class MappedScreen(
    val screen: DeviceScreen,
    val readerByNodeId: Map<String, UiNodeReader>
)

/**
 * Pure, deterministic mapping from a [UiNodeReader] tree to a [DeviceScreen].
 *
 * TRAVERSAL. The walk is an explicit iterative preorder (a node before its
 * children, children in index order) over a stack — never recursion. A
 * third-party app can expose a virtual tree that references itself, and a
 * recursive walk answers that with StackOverflowError, which is an Error: it
 * escapes every `catch (RuntimeException)` in the surface and the tool layer
 * and kills the hosting app. The walk is therefore bounded four ways, and every
 * bound that fires is reported in the screen title so the model knows it is
 * looking at a cut:
 * - [MAX_DEPTH] levels below the root; deeper subtrees are not descended into.
 * - [MAX_SCANNED_NODES] nodes visited in total; the walk stops there.
 * - [MAX_CHILDREN_PER_NODE] children per node, so an absurd childCount cannot
 *   turn into an unbounded number of obtained nodes.
 * - a visited set (by reader identity, see [UiNodeReader]) that makes a cycle
 *   terminate instead of repeating forever.
 * Reader access is defensive: a node that throws while being read (recycled or
 * sealed underneath us) contributes no information instead of failing the whole
 * snapshot.
 *
 * INCLUSION. A node is included only when it is visible to the user AND at
 * least one of the following holds: it is clickable, it is editable, or it has
 * non-blank text or content description (after whitespace normalization).
 * Children are visited even when their parent is excluded, so content inside
 * plain layout containers is still reported. At most [MAX_NODES] nodes are
 * reported, in traversal order.
 *
 * NODE IDS ARE STABLE, NOT POSITIONAL. Positional ids (n1, n2, ...) drift the
 * moment a list scrolls or a banner loads: the model observes "n7 = Delete",
 * the app inserts a row, and the very next tap on "n7" hits something nobody
 * reviewed. Each id is instead a short hex hash of an identity basis:
 * - with a view id: `package | viewId | label | role | coarse bounds`
 * - without one:    `package | label | role | coarse bounds | tree path`
 * where the label is whitespace-normalized and lowercased, coarse bounds are
 * the node's screen rectangle rounded to a [BOUNDS_GRID_PX] pixel grid, and the
 * tree path is the child-index path from the root. Coarse bounds are what makes
 * the id survive animation and minor reflow while still changing on a real page
 * change; a node that moves across a grid boundary does get a new id, which is
 * the conservative direction (a stale id is far more dangerous than a fresh
 * one). Ids are unique per screen: a basis that repeats is re-hashed with its
 * tree path and then with an occurrence counter.
 *
 * ROLE (first match wins), where "simple name" is the class name after the last
 * '.' in lowercase:
 * 1. node is editable, or simple name contains "edittext" -> "edittext"
 * 2. simple name contains "button"                        -> "button"
 * 3. simple name contains "image"                         -> "imageview"
 * 4. simple name contains "text"                          -> "textview"
 * 5. node is clickable (clickable-container hint)         -> "button"
 * 6. otherwise                                            -> "other"
 *
 * LABEL: first non-blank of content description, text, view id suffix, else the
 * role token. TEXT: the node's own normalized text when it is non-blank AND
 * (the node is editable OR the text differs from the label).
 *
 * CREDENTIALS. A node the platform flags as a password never contributes its
 * value: its text is replaced with [REDACTED_VALUE] and its label falls back to
 * the content description, the view id suffix or the role. Nothing else in the
 * pipeline can undo this, because the snapshot is what gets sent to the model.
 *
 * VALUES ARE NOT DISPLAY-TRUNCATED. Labels and texts are whitespace-normalized
 * and reported in full. The risk policy classifies on these values, and
 * truncating first is exactly how a dangerous phrase disappears from
 * classification ("Transfer all funds to acc..." no longer contains the account
 * or the amount). Display truncation belongs to the rendering layer, after the
 * risk decision. [MAX_VALUE_LENGTH] is only a memory ceiling for pathological
 * nodes and is far above any phrase a policy would match on.
 *
 * SCREEN IDENTITY: id is "s" plus the unsigned hex of the deterministic Java
 * string hash of "<package>|<normalized window title>" (truncation notes never
 * change it); title is the first non-blank of window title and package name,
 * else "unknown", plus one note per bound that fired.
 */
object AccessibilityScreenMapper {

    /** Maximum number of reported nodes per screen. */
    const val MAX_NODES = 60

    /** Maximum tree depth below the root that is descended into. */
    const val MAX_DEPTH = 80

    /** Maximum number of nodes visited (and therefore obtained) per snapshot. */
    const val MAX_SCANNED_NODES = 3_000

    /** Maximum number of children read from a single node. */
    const val MAX_CHILDREN_PER_NODE = 500

    /**
     * Memory ceiling for a single label or text value.
     *
     * NOT display truncation: values are cut here only so one pathological node
     * cannot carry a megabyte into the snapshot. See the class documentation.
     */
    const val MAX_VALUE_LENGTH = 1_024

    /** Grid, in pixels, the node bounds are rounded to for the stable id. */
    const val BOUNDS_GRID_PX = 24

    /** Fixed stand-in for the value of a node the platform flags as a credential. */
    const val REDACTED_VALUE = "[redacted]"

    private val WHITESPACE = Regex("\\s+")

    /** Maps [root] (null means no obtainable tree) to a semantic screen. */
    fun map(root: UiNodeReader?, packageName: String?, windowTitle: String?): MappedScreen {
        val pkg = packageName?.trim().orEmpty()
        val walk = walk(root)
        val reported = if (walk.included.size > MAX_NODES) {
            walk.included.subList(0, MAX_NODES)
        } else {
            walk.included
        }

        val readerByNodeId = LinkedHashMap<String, UiNodeReader>()
        val nodes = reported.map { found ->
            val node = toDeviceNode(pkg, found, readerByNodeId.keys)
            readerByNodeId[node.id] = found.reader
            node
        }

        val normalizedTitle = normalize(windowTitle)
        val baseTitle = firstNonBlank(normalizedTitle, pkg) ?: "unknown"
        val notes = buildList {
            if (walk.included.size > MAX_NODES) {
                add("[showing first $MAX_NODES of ${walk.included.size} nodes]")
            }
            if (walk.depthLimited) {
                add("[tree truncated below depth $MAX_DEPTH]")
            }
            if (walk.scanLimited) {
                add("[tree scan stopped after $MAX_SCANNED_NODES nodes]")
            }
            if (walk.childLimited) {
                add("[nodes wider than $MAX_CHILDREN_PER_NODE children truncated]")
            }
            if (walk.cyclic) {
                add("[repeated tree nodes skipped]")
            }
        }
        val screen = DeviceScreen(
            id = screenIdFor(pkg, normalizedTitle),
            title = (listOf(baseTitle) + notes).joinToString(" "),
            nodes = nodes
        )
        return MappedScreen(screen = screen, readerByNodeId = readerByNodeId)
    }

    // ------------------------------------------------------------- traversal

    /** One included node together with the child-index path that reached it. */
    private class Found(val reader: UiNodeReader, val path: String)

    private class Frame(val reader: UiNodeReader, val path: String, val depth: Int)

    private class Walk(
        val included: List<Found>,
        val depthLimited: Boolean,
        val scanLimited: Boolean,
        val childLimited: Boolean,
        val cyclic: Boolean
    )

    private fun walk(root: UiNodeReader?): Walk {
        val included = mutableListOf<Found>()
        if (root == null) {
            return Walk(
                included = included,
                depthLimited = false,
                scanLimited = false,
                childLimited = false,
                cyclic = false
            )
        }
        var depthLimited = false
        var scanLimited = false
        var childLimited = false
        var cyclic = false
        var scanned = 0
        val visited = HashSet<UiNodeReader>()
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, "r", 0))

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            if (!visited.add(frame.reader)) {
                cyclic = true
                continue
            }
            scanned += 1
            if (scanned > MAX_SCANNED_NODES) {
                scanLimited = true
                break
            }
            if (isIncluded(frame.reader)) {
                included += Found(frame.reader, frame.path)
            }
            if (frame.depth >= MAX_DEPTH) {
                depthLimited = true
                continue
            }
            val declared = guardedChildCount(frame.reader)
            val count = minOf(declared, MAX_CHILDREN_PER_NODE)
            if (declared > count) {
                childLimited = true
            }
            // Reverse order so the LIFO stack still yields preorder, index 0 first.
            for (index in count - 1 downTo 0) {
                val child = guardedChild(frame.reader, index) ?: continue
                stack.addLast(Frame(child, "${frame.path}.$index", frame.depth + 1))
            }
        }
        return Walk(included, depthLimited, scanLimited, childLimited, cyclic)
    }

    private fun guardedChildCount(reader: UiNodeReader): Int {
        return try {
            reader.childCount.coerceAtLeast(0)
        } catch (_: RuntimeException) {
            0
        }
    }

    private fun guardedChild(reader: UiNodeReader, index: Int): UiNodeReader? {
        return try {
            reader.child(index)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun isIncluded(reader: UiNodeReader): Boolean {
        return try {
            if (!reader.isVisibleToUser) {
                return false
            }
            reader.isClickable ||
                reader.isEditable ||
                normalize(reader.text).isNotEmpty() ||
                normalize(reader.contentDescription).isNotEmpty()
        } catch (_: RuntimeException) {
            false
        }
    }

    // ------------------------------------------------------------- node data

    private fun toDeviceNode(
        packageName: String,
        found: Found,
        usedIds: Set<String>
    ): DeviceNode {
        val reader = found.reader
        val role = roleOf(reader)
        val redacted = flag(reader) { isPassword }
        val viewId = clamp(viewIdSuffix(readString(reader) { viewIdResourceName }))
            .takeIf { value -> value.isNotEmpty() }
        val description = clamp(normalize(readChars(reader) { contentDescription }))
        val ownText = if (redacted) {
            REDACTED_VALUE
        } else {
            clamp(normalize(readChars(reader) { text }))
        }
        val editable = flag(reader) { isEditable }

        val label = if (redacted) {
            firstNonBlank(description, viewId.orEmpty()) ?: role
        } else {
            firstNonBlank(description, ownText, viewId.orEmpty()) ?: role
        }
        val text = ownText.takeIf { value ->
            value.isNotEmpty() && (editable || value != label)
        }
        val bounds = boundsOf(reader)

        return DeviceNode(
            id = stableId(packageName, viewId, label, role, bounds, found.path, usedIds),
            role = role,
            label = label,
            text = text,
            viewId = viewId,
            clickable = flag(reader) { isClickable },
            editable = editable,
            enabled = flag(reader) { isEnabled }
        )
    }

    private fun roleOf(reader: UiNodeReader): String {
        val simpleName = readChars(reader) { className }?.toString().orEmpty()
            .substringAfterLast('.')
            .lowercase(Locale.ROOT)
        return when {
            flag(reader) { isEditable } || simpleName.contains("edittext") -> "edittext"
            simpleName.contains("button") -> "button"
            simpleName.contains("image") -> "imageview"
            simpleName.contains("text") -> "textview"
            flag(reader) { isClickable } -> "button"
            else -> "other"
        }
    }

    private fun boundsOf(reader: UiNodeReader): NodeBounds {
        return try {
            reader.boundsInScreen
        } catch (_: RuntimeException) {
            NodeBounds.EMPTY
        }
    }

    private inline fun flag(reader: UiNodeReader, read: UiNodeReader.() -> Boolean): Boolean {
        return try {
            reader.read()
        } catch (_: RuntimeException) {
            false
        }
    }

    private inline fun readChars(
        reader: UiNodeReader,
        read: UiNodeReader.() -> CharSequence?
    ): CharSequence? {
        return try {
            reader.read()
        } catch (_: RuntimeException) {
            null
        }
    }

    private inline fun readString(
        reader: UiNodeReader,
        read: UiNodeReader.() -> String?
    ): String? {
        return try {
            reader.read()
        } catch (_: RuntimeException) {
            null
        }
    }

    // -------------------------------------------------------------- node ids

    /**
     * Deterministic id for one node, unique within the screen being mapped.
     *
     * The first candidate is the hash of the documented identity basis. Two
     * nodes can legitimately share it (two identical controls whose rounded
     * bounds land in the same grid cell), and [DeviceScreen] rejects duplicate
     * ids, so collisions fall back to the tree path and then to an occurrence
     * counter — both deterministic for a given tree.
     */
    private fun stableId(
        packageName: String,
        viewId: String?,
        label: String,
        role: String,
        bounds: NodeBounds,
        path: String,
        usedIds: Set<String>
    ): String {
        val identity = normalizeForId(label)
        val basis = if (viewId != null) {
            "$packageName|$viewId|$identity|$role|${coarseBounds(bounds)}"
        } else {
            "$packageName|$identity|$role|${coarseBounds(bounds)}|$path"
        }
        val first = "n" + shortHash(basis)
        if (first !in usedIds) {
            return first
        }
        val second = "n" + shortHash("$basis|$path")
        if (second !in usedIds) {
            return second
        }
        var attempt = 2
        while (true) {
            val candidate = "n" + shortHash("$basis|$path#$attempt")
            if (candidate !in usedIds) {
                return candidate
            }
            attempt += 1
        }
    }

    /** Node rectangle rounded to the nearest [BOUNDS_GRID_PX] grid cell. */
    private fun coarseBounds(bounds: NodeBounds): String {
        return listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            .joinToString(",") { value -> gridCell(value).toString() }
    }

    private fun gridCell(value: Int): Int {
        return Math.floorDiv(value + BOUNDS_GRID_PX / 2, BOUNDS_GRID_PX)
    }

    /**
     * 32-bit FNV-1a of the UTF-8 bytes, as eight lowercase hex characters.
     *
     * Chosen over String.hashCode for the id basis because it mixes short,
     * highly similar strings (two neighbouring grid cells) much better, and it
     * is specified byte-for-byte, so ids do not depend on the runtime.
     */
    private fun shortHash(basis: String): String {
        var value = FNV_OFFSET_BASIS
        basis.toByteArray(Charsets.UTF_8).forEach { byte ->
            value = value xor (byte.toInt() and 0xFF)
            value *= FNV_PRIME
        }
        return String.format(Locale.ROOT, "%08x", value)
    }

    private const val FNV_OFFSET_BASIS = -2128831035 // 0x811C9DC5 as a signed Int
    private const val FNV_PRIME = 16777619

    // ------------------------------------------------------------------ text

    private fun normalize(value: CharSequence?): String {
        if (value == null) {
            return ""
        }
        return value.toString().replace(WHITESPACE, " ").trim()
    }

    /** Memory ceiling only; see [MAX_VALUE_LENGTH]. */
    private fun clamp(value: String): String {
        return if (value.length <= MAX_VALUE_LENGTH) value else value.substring(0, MAX_VALUE_LENGTH)
    }

    private fun normalizeForId(value: String): String = value.lowercase(Locale.ROOT)

    private fun firstNonBlank(vararg values: String): String? {
        return values.firstOrNull { value -> value.isNotEmpty() }
    }

    private fun viewIdSuffix(viewIdResourceName: String?): String {
        if (viewIdResourceName == null) {
            return ""
        }
        return viewIdResourceName.substringAfterLast('/').trim()
    }

    private fun screenIdFor(packageName: String, windowTitle: String): String {
        val basis = "$packageName|$windowTitle"
        return "s" + Integer.toHexString(basis.hashCode())
    }
}
