// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

/** One semantic UI node on a fake device screen, mirroring an accessibility-tree entry. */
data class DeviceNode(
    val id: String,
    val role: String,
    val label: String,
    val text: String? = null
) {
    init {
        require(id.isNotBlank()) { "Node id must not be blank." }
        require(role.isNotBlank()) { "Node role must not be blank." }
        require(label.isNotBlank()) { "Node label must not be blank." }
    }
}

/** One fake device screen with its nodes in declaration order. */
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

/**
 * Deterministic in-memory device: a set of screens plus tap transitions.
 *
 * Pure JVM stand-in for a phone UI so the observe -> act -> observe loop can be
 * exercised without Android. Taps follow the configured (screenId, nodeId) -> screenId
 * transitions; entered text is kept per screen and node and applied to snapshots.
 */
class FakeDevice(
    screens: List<DeviceScreen>,
    startScreenId: String,
    transitions: Map<Pair<String, String>, String> = emptyMap()
) {
    private val screensById: Map<String, DeviceScreen>
    private val transitions: Map<Pair<String, String>, String>
    private val enteredText = linkedMapOf<Pair<String, String>, String>()
    private val log = mutableListOf<String>()

    var currentScreenId: String
        private set

    init {
        require(screens.isNotEmpty()) { "A fake device needs at least one screen." }
        val duplicates = screens.groupingBy { screen -> screen.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicates.isEmpty()) {
            "Duplicate screen ids: ${duplicates.sorted().joinToString()}."
        }
        screensById = screens.associateBy { screen -> screen.id }
        require(startScreenId in screensById) { "Unknown start screen '$startScreenId'." }
        transitions.forEach { (trigger, targetScreenId) ->
            val (screenId, nodeId) = trigger
            val screen = screensById[screenId]
            requireNotNull(screen) { "Transition source screen '$screenId' is unknown." }
            require(screen.nodes.any { node -> node.id == nodeId }) {
                "Transition node '$nodeId' is not on screen '$screenId'."
            }
            require(targetScreenId in screensById) {
                "Transition target screen '$targetScreenId' is unknown."
            }
        }
        this.transitions = transitions.toMap()
        currentScreenId = startScreenId
    }

    /** Current screen with any entered text applied, without mutating the declared screens. */
    fun snapshot(): DeviceScreen {
        val screen = screensById.getValue(currentScreenId)
        return screen.copy(
            nodes = screen.nodes.map { node ->
                val entered = enteredText[currentScreenId to node.id]
                if (entered == null) node else node.copy(text = entered)
            }
        )
    }

    fun tap(nodeId: String) {
        requireKnownNode(nodeId)
        log += "tap:$nodeId"
        val nextScreenId = transitions[currentScreenId to nodeId]
        if (nextScreenId != null) {
            currentScreenId = nextScreenId
        }
    }

    fun setText(nodeId: String, text: String) {
        requireKnownNode(nodeId)
        enteredText[currentScreenId to nodeId] = text
        log += "set_text:$nodeId:$text"
    }

    fun actionLog(): List<String> = log.toList()

    private fun requireKnownNode(nodeId: String) {
        require(screensById.getValue(currentScreenId).nodes.any { node -> node.id == nodeId }) {
            "Unknown node '$nodeId' on screen '$currentScreenId'."
        }
    }
}
