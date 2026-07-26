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
    data class ContextLoaded(
        val itemIds: List<String>,
        val droppedItemIds: List<String> = emptyList(),
        val totalContentChars: Int = 0
    ) : AgentHarnessTraceEvent

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

/** Coordinates one bounded turn without depending on Android, transport, or storage implementations. */
class AgentOrchestrator(
    private val provider: AgentProvider,
    private val contextCoordinator: AgentContextCoordinator,
    private val toolOrchestrator: AgentToolOrchestrator,
    private val sessionStore: AgentSessionStore,
    private val clock: AgentClock,
    private val idGenerator: AgentIdGenerator,
    private val config: AgentHarnessConfig = AgentHarnessConfig()
) {

    init {
        require(provider.id.isNotBlank()) { "Provider id must not be blank." }
    }

    fun execute(request: AgentHarnessRequest): AgentHarnessResult {
        var session = sessionStore.load(request.sessionId) ?: newSession(request.sessionId)
        session = appendMessage(
            session = session,
            role = AgentRole.USER,
            content = request.userInput
        )
        sessionStore.save(session)

        val contextBundle = contextCoordinator.build(
            AgentContextRequest(
                session = session,
                userInput = request.userInput
            )
        )
        val context = contextBundle.items
        val tools = toolOrchestrator.specifications()
        val trace = mutableListOf<AgentHarnessTraceEvent>(
            AgentHarnessTraceEvent.ContextLoaded(
                itemIds = context.map { item -> item.id },
                droppedItemIds = contextBundle.droppedItemIds,
                totalContentChars = contextBundle.totalContentChars
            )
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
                    toolOrchestrator.execute(response.calls, session.id).forEach { execution ->
                        val call = execution.call
                        val result = execution.result
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
}

/** Public runtime entrypoint and composition root for the minimal architecture. */
class AgentHarnessRunner(
    private val orchestrator: AgentOrchestrator
) : AgentHarness {

    constructor(
        provider: AgentProvider,
        contextProviders: List<AgentContextProvider> = listOf(EmptyAgentContextProvider),
        tools: List<AgentTool> = emptyList(),
        sessionStore: AgentSessionStore = InMemoryAgentSessionStore(),
        clock: AgentClock = SystemAgentClock,
        idGenerator: AgentIdGenerator = UuidAgentIdGenerator(),
        config: AgentHarnessConfig = AgentHarnessConfig(),
        contextPolicy: AgentContextPolicy = AgentContextPolicy(),
        toolProfile: AgentToolProfile = AgentToolProfile.all()
    ) : this(
        AgentOrchestrator(
            provider = provider,
            contextCoordinator = AgentContextCoordinator(contextProviders, contextPolicy),
            toolOrchestrator = AgentToolOrchestrator(
                registry = AgentToolRegistry(tools),
                profile = toolProfile,
                maxToolCallsPerStep = config.maxToolCallsPerStep
            ),
            sessionStore = sessionStore,
            clock = clock,
            idGenerator = idGenerator,
            config = config
        )
    )

    override fun run(request: AgentHarnessRequest): AgentHarnessResult {
        return orchestrator.execute(request)
    }
}

/** Source-compatible M0 facade retained for existing consumers. */
class DeterministicAgentHarness(
    provider: AgentProvider,
    contextProvider: AgentContextProvider,
    toolRegistry: AgentToolRegistry,
    sessionStore: AgentSessionStore,
    clock: AgentClock,
    idGenerator: AgentIdGenerator,
    config: AgentHarnessConfig = AgentHarnessConfig()
) : AgentHarness {
    private val delegate = AgentHarnessRunner(
        AgentOrchestrator(
            provider = provider,
            contextCoordinator = AgentContextCoordinator(contextProvider),
            toolOrchestrator = AgentToolOrchestrator(
                registry = toolRegistry,
                maxToolCallsPerStep = config.maxToolCallsPerStep
            ),
            sessionStore = sessionStore,
            clock = clock,
            idGenerator = idGenerator,
            config = config
        )
    )

    override fun run(request: AgentHarnessRequest): AgentHarnessResult = delegate.run(request)
}
