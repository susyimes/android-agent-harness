// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

enum class AgentContextTrust {
    APPLICATION,
    USER,
    EXTERNAL
}

data class AgentContextItem(
    val id: String,
    val source: String,
    val content: String,
    val trust: AgentContextTrust
) {
    init {
        require(id.isNotBlank()) { "Context id must not be blank." }
        require(source.isNotBlank()) { "Context source must not be blank." }
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

