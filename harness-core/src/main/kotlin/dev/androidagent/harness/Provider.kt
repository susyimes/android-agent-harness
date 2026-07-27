// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

data class AgentProviderRequest(
    val session: AgentSession,
    val context: List<AgentContextItem>,
    val tools: List<AgentToolSpec>,
    val providerStep: Int
) {
    init {
        require(providerStep > 0) { "Provider step must be positive." }
    }
}

sealed interface AgentProviderResponse {
    data class FinalText(val content: String) : AgentProviderResponse

    data class ToolRequests(val calls: List<AgentToolCall>) : AgentProviderResponse {
        init {
            require(calls.isNotEmpty()) { "A tool response must contain at least one call." }
        }
    }
}

interface AgentProvider {
    val id: String

    fun respond(request: AgentProviderRequest): AgentProviderResponse
}

/**
 * One turn-scoped provider plus the hook that aborts its current I/O.
 *
 * Provider implementations should make [cancel] idempotent. The SDK creates a
 * fresh connection per run, so cancellation never poisons a later run.
 */
data class AgentProviderConnection(
    val provider: AgentProvider,
    val cancel: () -> Unit = {}
) {
    init {
        require(provider.id.isNotBlank()) { "Provider id must not be blank." }
    }
}

/** Creates an isolated provider connection for each Agent run. */
fun interface AgentProviderFactory {
    fun connect(): AgentProviderConnection

    companion object {
        fun fixed(provider: AgentProvider): AgentProviderFactory {
            return AgentProviderFactory { AgentProviderConnection(provider) }
        }
    }
}
