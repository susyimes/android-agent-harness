// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentToolCall

/**
 * Deterministic offline provider used when no API credential is stored.
 *
 * Every turn it calls the `uppercase` tool on the latest user message and then
 * answers with the tool result, so the APK demonstrates the full
 * provider -> tool -> provider loop with zero setup and zero network access.
 */
class ScriptedChatProvider : AgentProvider {

    override val id: String = "local-scripted-sample"

    override fun respond(request: AgentProviderRequest): AgentProviderResponse {
        val messages = request.session.messages
        val lastUserIndex = messages.indexOfLast { message -> message.role == AgentRole.USER }
        val toolResultAfterUser = messages
            .drop(lastUserIndex + 1)
            .lastOrNull { message ->
                message.role == AgentRole.TOOL && message.toolName == "uppercase"
            }
        if (toolResultAfterUser != null) {
            return AgentProviderResponse.FinalText(
                "Scripted result (offline): ${toolResultAfterUser.content}"
            )
        }
        return AgentProviderResponse.ToolRequests(
            listOf(
                AgentToolCall(
                    id = "scripted-call-${messages.size}",
                    toolName = "uppercase",
                    arguments = mapOf("text" to messages[lastUserIndex].content)
                )
            )
        )
    }
}
