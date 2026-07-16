// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

data class AgentHarnessConfig(
    val maxProviderSteps: Int = 4,
    val maxToolCallsPerStep: Int = 4
) {
    init {
        require(maxProviderSteps in 1..32) { "maxProviderSteps must be between 1 and 32." }
        require(maxToolCallsPerStep in 1..32) { "maxToolCallsPerStep must be between 1 and 32." }
    }
}

sealed interface AgentHarnessTraceEvent {
    data class ContextLoaded(val itemIds: List<String>) : AgentHarnessTraceEvent

    data class ProviderInvoked(
        val step: Int,
        val providerId: String,
        val toolNames: List<String>
    ) : AgentHarnessTraceEvent

    data class ToolExecuted(
        val step: Int,
        val callId: String,
        val toolName: String,
        val succeeded: Boolean,
        val content: String
    ) : AgentHarnessTraceEvent

    data class Completed(
        val step: Int,
        val output: String
    ) : AgentHarnessTraceEvent
}

data class AgentHarnessResult(
    val session: AgentSession,
    val output: String,
    val providerSteps: Int,
    val trace: List<AgentHarnessTraceEvent>
)

interface AgentHarness {
    fun run(request: AgentHarnessRequest): AgentHarnessResult
}

class AgentHarnessProtocolException(message: String) : IllegalStateException(message)

class AgentHarnessLimitException(message: String) : IllegalStateException(message)

class DeterministicAgentHarness(
    private val provider: AgentProvider,
    private val contextProvider: AgentContextProvider,
    private val toolRegistry: AgentToolRegistry,
    private val sessionStore: AgentSessionStore,
    private val clock: AgentClock,
    private val idGenerator: AgentIdGenerator,
    private val config: AgentHarnessConfig = AgentHarnessConfig()
) : AgentHarness {

    init {
        require(provider.id.isNotBlank()) { "Provider id must not be blank." }
    }

    override fun run(request: AgentHarnessRequest): AgentHarnessResult {
        var session = sessionStore.load(request.sessionId) ?: newSession(request.sessionId)
        session = appendMessage(
            session = session,
            role = AgentRole.USER,
            content = request.userInput
        )
        sessionStore.save(session)

        val context = contextProvider.load(
            AgentContextRequest(
                session = session,
                userInput = request.userInput
            )
        ).sortedWith(compareBy(AgentContextItem::id, AgentContextItem::source, AgentContextItem::content))
        val duplicateContextIds = context.groupingBy { item -> item.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        if (duplicateContextIds.isNotEmpty()) {
            throw AgentHarnessProtocolException(
                "Context ids must be unique: ${duplicateContextIds.sorted().joinToString()}."
            )
        }

        val tools = toolRegistry.specifications()
        val trace = mutableListOf<AgentHarnessTraceEvent>(
            AgentHarnessTraceEvent.ContextLoaded(context.map { item -> item.id })
        )

        for (step in 1..config.maxProviderSteps) {
            trace += AgentHarnessTraceEvent.ProviderInvoked(
                step = step,
                providerId = provider.id,
                toolNames = tools.map { spec -> spec.name }
            )
            when (val response = provider.respond(AgentProviderRequest(session, context, tools, step))) {
                is AgentProviderResponse.FinalText -> {
                    session = appendMessage(
                        session = session,
                        role = AgentRole.ASSISTANT,
                        content = response.content
                    )
                    sessionStore.save(session)
                    trace += AgentHarnessTraceEvent.Completed(step, response.content)
                    return AgentHarnessResult(
                        session = session,
                        output = response.content,
                        providerSteps = step,
                        trace = trace.toList()
                    )
                }

                is AgentProviderResponse.ToolRequests -> {
                    validateToolCalls(response.calls)
                    response.calls.forEach { call ->
                        val result = toolRegistry.execute(call, session.id)
                        session = appendToolResult(session, call, result)
                        sessionStore.save(session)
                        trace += AgentHarnessTraceEvent.ToolExecuted(
                            step = step,
                            callId = call.id,
                            toolName = call.toolName,
                            succeeded = !result.isError,
                            content = result.content
                        )
                    }
                }
            }
        }

        throw AgentHarnessLimitException(
            "Provider '${provider.id}' did not finish within ${config.maxProviderSteps} steps."
        )
    }

    private fun newSession(sessionId: String): AgentSession {
        val now = clock.nowEpochMillis()
        return AgentSession(
            id = sessionId,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
    }

    private fun appendMessage(
        session: AgentSession,
        role: AgentRole,
        content: String
    ): AgentSession {
        return session.append(
            AgentMessage(
                id = idGenerator.nextId("message"),
                sessionId = session.id,
                role = role,
                content = content,
                createdAtEpochMillis = clock.nowEpochMillis()
            )
        )
    }

    private fun appendToolResult(
        session: AgentSession,
        call: AgentToolCall,
        result: AgentToolResult
    ): AgentSession {
        return session.append(
            AgentMessage(
                id = idGenerator.nextId("message"),
                sessionId = session.id,
                role = AgentRole.TOOL,
                content = result.content,
                createdAtEpochMillis = clock.nowEpochMillis(),
                toolCallId = call.id,
                toolName = call.toolName
            )
        )
    }

    private fun validateToolCalls(calls: List<AgentToolCall>) {
        if (calls.size > config.maxToolCallsPerStep) {
            throw AgentHarnessProtocolException(
                "Provider returned ${calls.size} tool calls; limit is ${config.maxToolCallsPerStep}."
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
    }
}

