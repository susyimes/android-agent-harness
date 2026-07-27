// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

data class AgentToolSpec(
    val name: String,
    val description: String,
    val requiredArguments: Set<String> = emptySet(),
    val optionalArguments: Set<String> = emptySet()
) {
    init {
        require(TOOL_NAME.matches(name)) {
            "Tool name '$name' must match ${TOOL_NAME.pattern}."
        }
        require(description.isNotBlank()) { "Tool description must not be blank." }
        require((requiredArguments + optionalArguments).none { argument -> argument.isBlank() }) {
            "Argument names must not be blank."
        }
        require(requiredArguments.intersect(optionalArguments).isEmpty()) {
            "Arguments cannot be both required and optional."
        }
    }

    val arguments: Set<String>
        get() = requiredArguments + optionalArguments

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

    fun contains(toolName: String): Boolean = toolByName.containsKey(toolName)

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

data class AgentToolProfile(
    val id: String,
    val allowedToolNames: Set<String>? = null
) {
    init {
        require(id.isNotBlank()) { "Tool profile id must not be blank." }
        require(allowedToolNames?.none { name -> name.isBlank() } != false) {
            "Allowed tool names must not be blank."
        }
    }

    fun allows(toolName: String): Boolean = allowedToolNames?.contains(toolName) ?: true

    companion object {
        fun all(id: String = "all"): AgentToolProfile = AgentToolProfile(id = id)

        fun only(id: String, toolNames: Set<String>): AgentToolProfile {
            return AgentToolProfile(id = id, allowedToolNames = toolNames.toSet())
        }
    }
}

data class AgentToolExecution(
    val call: AgentToolCall,
    val result: AgentToolResult
)

/** Keeps the provider-visible catalog and executable capability set on the same boundary. */
class AgentToolOrchestrator(
    private val registry: AgentToolRegistry,
    val profile: AgentToolProfile = AgentToolProfile.all(),
    private val maxToolCallsPerStep: Int = 4
) {
    init {
        require(maxToolCallsPerStep in 1..32) {
            "maxToolCallsPerStep must be between 1 and 32."
        }
        val knownNames = registry.specifications().map { spec -> spec.name }.toSet()
        val unknownProfileTools = profile.allowedToolNames.orEmpty() - knownNames
        require(unknownProfileTools.isEmpty()) {
            "Tool profile '${profile.id}' contains unknown tools: ${unknownProfileTools.sorted().joinToString()}."
        }
    }

    fun specifications(): List<AgentToolSpec> {
        return registry.specifications().filter { spec -> profile.allows(spec.name) }
    }

    fun execute(calls: List<AgentToolCall>, sessionId: String): List<AgentToolExecution> {
        if (calls.size > maxToolCallsPerStep) {
            throw AgentHarnessProtocolException(
                "Provider returned ${calls.size} tool calls; limit is $maxToolCallsPerStep."
            )
        }
        val duplicateCallIds = calls.groupingBy { call -> call.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        if (duplicateCallIds.isNotEmpty()) {
            throw AgentHarnessProtocolException(
                "Tool call ids must be unique within a step: ${duplicateCallIds.sorted().joinToString()}."
            )
        }

        return calls.map { call ->
            val result = when {
                !registry.contains(call.toolName) -> AgentToolResult.failure("Unknown tool: ${call.toolName}")
                !profile.allows(call.toolName) -> AgentToolResult.failure(
                    "Tool '${call.toolName}' is not available in profile '${profile.id}'."
                )
                else -> registry.execute(call, sessionId)
            }
            AgentToolExecution(call = call, result = result)
        }
    }
}
