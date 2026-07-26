// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

/**
 * Deterministic in-memory [DeviceSurface]: a set of screens plus tap transitions.
 *
 * Pure JVM stand-in for a phone UI so the observe -> act -> observe loop can be
 * exercised without Android. Taps follow the configured (screenId, nodeId) -> screenId
 * transitions; entered text is kept per screen and node and applied to snapshots.
 *
 * CAPABILITIES. The fake models one app, not a device shell, so it implements
 * exactly the actions it can honestly perform:
 * - [back] pops the screens a tap navigated into, and fails with ACTION_FAILED
 *   at the root, the way a real app exits instead of going "back" forever.
 * - [waitForStable] returns true immediately: an in-memory screen is always
 *   settled. It is deliberately not written to [actionLog], so the implicit
 *   settle that [DeviceActTool] performs after every action stays invisible to
 *   assertions about what the device was actually asked to do.
 * - [foregroundPackage] answers only when [packageName] was configured.
 * - home, swipe, scrollToText and launchApp stay unsupported, which is what
 *   makes this fake useful for testing the UNSUPPORTED_ACTION path.
 */
class FakeDevice(
    screens: List<DeviceScreen>,
    startScreenId: String,
    transitions: Map<Pair<String, String>, String> = emptyMap(),
    private val packageName: String? = null
) : DeviceSurface {
    private val screensById: Map<String, DeviceScreen>
    private val transitions: Map<Pair<String, String>, String>
    private val enteredText = linkedMapOf<Pair<String, String>, String>()
    private val history = ArrayDeque<String>()
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
        require(packageName?.isNotBlank() != false) { "Package name must not be blank." }
        this.transitions = transitions.toMap()
        currentScreenId = startScreenId
    }

    /** Current screen with any entered text applied, without mutating the declared screens. */
    override fun snapshot(): DeviceScreen {
        val screen = screensById.getValue(currentScreenId)
        return screen.copy(
            nodes = screen.nodes.map { node ->
                val entered = enteredText[currentScreenId to node.id]
                if (entered == null) node else node.copy(text = entered)
            }
        )
    }

    override fun tap(nodeId: String) {
        requireKnownNode(nodeId)
        log += "tap:$nodeId"
        val nextScreenId = transitions[currentScreenId to nodeId]
        if (nextScreenId != null) {
            history.addLast(currentScreenId)
            currentScreenId = nextScreenId
        }
    }

    override fun setText(nodeId: String, text: String) {
        requireKnownNode(nodeId)
        enteredText[currentScreenId to nodeId] = text
        log += "set_text:$nodeId:$text"
    }

    override fun back() {
        val previous = history.removeLastOrNull()
            ?: throw DeviceActionException(
                DeviceErrorType.ACTION_FAILED,
                "There is no previous screen: '$currentScreenId' is where this app started."
            )
        log += "back"
        currentScreenId = previous
    }

    /** An in-memory screen never animates, so it is always stable. */
    override fun waitForStable(timeoutMs: Long): Boolean {
        require(timeoutMs > 0L) { "waitForStable timeout must be positive." }
        return true
    }

    override fun foregroundPackage(): String? {
        return packageName
            ?: throw UnsupportedOperationException(
                "This fake device was constructed without a package name."
            )
    }

    fun actionLog(): List<String> = log.toList()

    private fun requireKnownNode(nodeId: String) {
        require(screensById.getValue(currentScreenId).nodes.any { node -> node.id == nodeId }) {
            "Unknown node '$nodeId' on screen '$currentScreenId'."
        }
    }
}
