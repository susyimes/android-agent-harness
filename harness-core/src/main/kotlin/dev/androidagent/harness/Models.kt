// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

enum class AgentRole {
    USER,
    ASSISTANT,
    TOOL
}

data class AgentMessage(
    val id: String,
    val sessionId: String,
    val role: AgentRole,
    val content: String,
    val createdAtEpochMillis: Long,
    val toolCallId: String? = null,
    val toolName: String? = null
) {
    init {
        require(id.isNotBlank()) { "Message id must not be blank." }
        require(sessionId.isNotBlank()) { "Message session id must not be blank." }
        if (role == AgentRole.TOOL) {
            require(!toolCallId.isNullOrBlank()) { "Tool messages require a tool call id." }
            require(!toolName.isNullOrBlank()) { "Tool messages require a tool name." }
        }
    }
}

data class AgentSession(
    val id: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val messages: List<AgentMessage> = emptyList()
) {
    init {
        require(id.isNotBlank()) { "Session id must not be blank." }
        require(messages.all { message -> message.sessionId == id }) {
            "Every message must belong to session '$id'."
        }
    }

    fun append(message: AgentMessage): AgentSession {
        require(message.sessionId == id) { "Cannot append a message from another session." }
        return copy(
            updatedAtEpochMillis = maxOf(updatedAtEpochMillis, message.createdAtEpochMillis),
            messages = messages + message
        )
    }
}

data class AgentHarnessRequest(
    val sessionId: String,
    val userInput: String
) {
    init {
        require(sessionId.isNotBlank()) { "Session id must not be blank." }
        require(userInput.isNotBlank()) { "User input must not be blank." }
    }
}

