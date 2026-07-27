// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

enum class AgentContextTrust {
    APPLICATION,
    USER,
    /**
     * Durable context produced by an Agent tool or background self cycle.
     *
     * It remains usable evidence, but it is not silently promoted to a fact
     * asserted or approved by the user.
     */
    AGENT,
    EXTERNAL
}

data class AgentContextItem(
    val id: String,
    val source: String,
    val content: String,
    val trust: AgentContextTrust,
    val priority: Int = 0
) {
    init {
        require(id.isNotBlank()) { "Context id must not be blank." }
        require(source.isNotBlank()) { "Context source must not be blank." }
        require(content.isNotBlank()) { "Context content must not be blank." }
    }
}

data class AgentContextRequest(
    val session: AgentSession,
    val userInput: String
)

fun interface AgentContextProvider {
    fun load(request: AgentContextRequest): List<AgentContextItem>
}

object EmptyAgentContextProvider : AgentContextProvider {
    override fun load(request: AgentContextRequest): List<AgentContextItem> = emptyList()
}

class StaticAgentContextProvider(items: List<AgentContextItem>) : AgentContextProvider {
    private val snapshot = items.toList()

    override fun load(request: AgentContextRequest): List<AgentContextItem> = snapshot
}

data class AgentContextPolicy(
    val maxItems: Int = 16,
    val maxContentChars: Int = 16_000,
    val allowedTrust: Set<AgentContextTrust> = AgentContextTrust.entries.toSet()
) {
    init {
        require(maxItems in 1..256) { "maxItems must be between 1 and 256." }
        require(maxContentChars in 1..1_000_000) {
            "maxContentChars must be between 1 and 1000000."
        }
        require(allowedTrust.isNotEmpty()) { "allowedTrust must not be empty." }
    }
}

data class AgentContextBundle(
    val items: List<AgentContextItem>,
    val droppedItemIds: List<String>,
    val totalContentChars: Int
)

/**
 * Minimal context control plane.
 *
 * Providers can adapt product-owned stores, but only policy-selected snapshots leave this boundary.
 */
class AgentContextCoordinator(
    providers: List<AgentContextProvider>,
    private val policy: AgentContextPolicy = AgentContextPolicy()
) {
    private val providers = providers.toList()

    constructor(
        provider: AgentContextProvider,
        policy: AgentContextPolicy = AgentContextPolicy()
    ) : this(listOf(provider), policy)

    fun build(request: AgentContextRequest): AgentContextBundle {
        val candidates = providers.flatMap { provider -> provider.load(request) }
        val duplicateIds = candidates.groupingBy { item -> item.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            throw AgentHarnessProtocolException(
                "Context ids must be unique: ${duplicateIds.sorted().joinToString()}."
            )
        }

        val ordered = candidates.sortedWith(
            compareByDescending(AgentContextItem::priority)
                .thenBy(AgentContextItem::id)
                .thenBy(AgentContextItem::source)
                .thenBy(AgentContextItem::content)
        )
        val selected = mutableListOf<AgentContextItem>()
        val dropped = mutableListOf<String>()
        var totalContentChars = 0

        ordered.forEach { item ->
            val fitsTrust = item.trust in policy.allowedTrust
            val fitsItemBudget = selected.size < policy.maxItems
            val fitsCharBudget = totalContentChars + item.content.length <= policy.maxContentChars
            if (fitsTrust && fitsItemBudget && fitsCharBudget) {
                selected += item
                totalContentChars += item.content.length
            } else {
                dropped += item.id
            }
        }

        return AgentContextBundle(
            items = selected.toList(),
            droppedItemIds = dropped.toList(),
            totalContentChars = totalContentChars
        )
    }
}
