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

