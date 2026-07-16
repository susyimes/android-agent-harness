// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

data class AgentToolSpec(
    val name: String,
    val description: String,
    val requiredArguments: Set<String> = emptySet()
) {
    init {
        require(TOOL_NAME.matches(name)) {
            "Tool name '$name' must match ${TOOL_NAME.pattern}."
        }
        require(description.isNotBlank()) { "Tool description must not be blank." }
        require(requiredArguments.none { argument -> argument.isBlank() }) {
            "Required argument names must not be blank."
        }
    }

    companion object {
        private val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

data class AgentToolCall(
    val id: String,
    val toolName: String,
    val arguments: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "Tool call id must not be blank." }
        require(toolName.isNotBlank()) { "Tool call name must not be blank." }
    }
}

data class AgentToolInvocation(
    val callId: String,
    val sessionId: String,
    val arguments: Map<String, String>
)

data class AgentToolResult(
    val content: String,
    val isError: Boolean = false
) {
    companion object {
        fun success(content: String): AgentToolResult = AgentToolResult(content = content)

        fun failure(content: String): AgentToolResult = AgentToolResult(
            content = content,
            isError = true
        )
    }
}

interface AgentTool {
    val spec: AgentToolSpec

    fun execute(invocation: AgentToolInvocation): AgentToolResult
}

class AgentToolRegistry(tools: List<AgentTool>) {
    private val toolByName: Map<String, AgentTool>

    init {
        val duplicates = tools.groupingBy { tool -> tool.spec.name }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicates.isEmpty()) { "Duplicate tool names: ${duplicates.sorted().joinToString()}." }
        toolByName = tools.associateBy { tool -> tool.spec.name }
    }

    fun specifications(): List<AgentToolSpec> = toolByName.values
        .map { tool -> tool.spec }
        .sortedBy { spec -> spec.name }

    fun execute(call: AgentToolCall, sessionId: String): AgentToolResult {
        val tool = toolByName[call.toolName]
            ?: return AgentToolResult.failure("Unknown tool: ${call.toolName}")
        val missing = tool.spec.requiredArguments
            .filterNot { argument -> call.arguments.containsKey(argument) }
            .sorted()
        if (missing.isNotEmpty()) {
            return AgentToolResult.failure("Missing required arguments: ${missing.joinToString()}")
        }

        return try {
            tool.execute(
                AgentToolInvocation(
                    callId = call.id,
                    sessionId = sessionId,
                    arguments = call.arguments.toSortedMap()
                )
            )
        } catch (error: RuntimeException) {
            AgentToolResult.failure(
                "Tool '${call.toolName}' failed: ${error.message ?: error::class.simpleName.orEmpty()}"
            )
        }
    }
}

