// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

data class AgentHarnessConfig(
    val maxProviderSteps: Int = 4,
    val maxToolCallsPerStep: Int = 4,
    val toolLoopActivation: AgentToolLoopActivation? = null
) {
    init {
        require(maxProviderSteps in 1..MAX_PROVIDER_STEPS) {
            "maxProviderSteps must be between 1 and $MAX_PROVIDER_STEPS."
        }
        require(maxToolCallsPerStep in 1..32) { "maxToolCallsPerStep must be between 1 and 32." }
        toolLoopActivation?.let { activation ->
            require(activation.maxProviderSteps >= maxProviderSteps) {
                "Activated maxProviderSteps must be at least the initial maxProviderSteps."
            }
            require(activation.maxToolCallsPerStep <= maxToolCallsPerStep) {
                "Activated maxToolCallsPerStep cannot exceed the initial maxToolCallsPerStep."
            }
        }
    }

    companion object {
        const val MAX_PROVIDER_STEPS = 80
    }
}

/**
 * Expands a run only after the provider actually requests one of [toolNames].
 *
 * The provider sees the full tool catalog from the first step. This policy does
 * not infer intent from user text; the model's tool call is the activation
 * signal. Once activated, the larger step budget and tighter per-step tool
 * limit remain in force for the rest of the turn.
 */
data class AgentToolLoopActivation(
    val toolNames: Set<String>,
    val maxProviderSteps: Int = AgentHarnessConfig.MAX_PROVIDER_STEPS,
    val maxToolCallsPerStep: Int = 1
) {
    init {
        require(toolNames.isNotEmpty()) { "Activation tool names must not be empty." }
        require(toolNames.none(String::isBlank)) {
            "Activation tool names must not contain blank values."
        }
        require(maxProviderSteps in 1..AgentHarnessConfig.MAX_PROVIDER_STEPS) {
            "Activated maxProviderSteps must be between 1 and " +
                "${AgentHarnessConfig.MAX_PROVIDER_STEPS}."
        }
        require(maxToolCallsPerStep in 1..32) {
            "Activated maxToolCallsPerStep must be between 1 and 32."
        }
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
        val content: String,
        val arguments: Map<String, String> = emptyMap()
    ) : AgentHarnessTraceEvent

    data class ToolLoopActivated(
        val step: Int,
        val toolName: String,
        val maxProviderSteps: Int,
        val maxToolCallsPerStep: Int
    ) : AgentHarnessTraceEvent

    data class Completed(
        val step: Int,
        val output: String
    ) : AgentHarnessTraceEvent
}

/** Receives trace events synchronously as a bounded run progresses. */
fun interface AgentHarnessObserver {
    fun onEvent(event: AgentHarnessTraceEvent)

    companion object {
        val NONE = AgentHarnessObserver {}
    }
}

fun interface AgentCancellationSignal {
    fun isCancellationRequested(): Boolean

    companion object {
        val THREAD_INTERRUPTED = AgentCancellationSignal {
            Thread.currentThread().isInterrupted
        }
    }
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

class AgentHarnessCancelledException(message: String = "Agent run was cancelled.") :
    IllegalStateException(message)

/** Coordinates one bounded turn without depending on Android, transport, or storage implementations. */
class AgentOrchestrator(
    private val provider: AgentProvider,
    private val contextCoordinator: AgentContextCoordinator,
    private val toolOrchestrator: AgentToolOrchestrator,
    private val sessionStore: AgentSessionStore,
    private val clock: AgentClock,
    private val idGenerator: AgentIdGenerator,
    private val config: AgentHarnessConfig = AgentHarnessConfig(),
    private val observer: AgentHarnessObserver = AgentHarnessObserver.NONE,
    private val cancellationSignal: AgentCancellationSignal =
        AgentCancellationSignal.THREAD_INTERRUPTED
) {

    init {
        require(provider.id.isNotBlank()) { "Provider id must not be blank." }
        val unknownActivationTools = config.toolLoopActivation?.toolNames.orEmpty() -
            toolOrchestrator.specifications().map { specification -> specification.name }.toSet()
        require(unknownActivationTools.isEmpty()) {
            "Tool-loop activation contains unavailable tools: " +
                "${unknownActivationTools.sorted().joinToString()}."
        }
    }

    fun execute(request: AgentHarnessRequest): AgentHarnessResult {
        ensureActive()
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
        val trace = mutableListOf<AgentHarnessTraceEvent>()
        record(
            trace,
            AgentHarnessTraceEvent.ContextLoaded(
                itemIds = context.map { item -> item.id },
                droppedItemIds = contextBundle.droppedItemIds,
                totalContentChars = contextBundle.totalContentChars
            )
        )

        var step = 1
        var toolLoopActivated = false
        while (step <= providerStepLimit(toolLoopActivated)) {
            ensureActive()
            record(
                trace,
                AgentHarnessTraceEvent.ProviderInvoked(
                step = step,
                providerId = provider.id,
                toolNames = tools.map { spec -> spec.name }
                )
            )
            val response = provider.respond(AgentProviderRequest(session, context, tools, step))
            ensureActive()
            when (response) {
                is AgentProviderResponse.FinalText -> {
                    ensureActive()
                    session = appendMessage(
                        session = session,
                        role = AgentRole.ASSISTANT,
                        content = response.content
                    )
                    sessionStore.save(session)
                    record(trace, AgentHarnessTraceEvent.Completed(step, response.content))
                    return AgentHarnessResult(
                        session = session,
                        output = response.content,
                        providerSteps = step,
                        trace = trace.toList()
                    )
                }

                is AgentProviderResponse.ToolRequests -> {
                    val activation = config.toolLoopActivation
                    val activatingCall = if (toolLoopActivated || activation == null) {
                        null
                    } else {
                        response.calls.firstOrNull { call -> call.toolName in activation.toolNames }
                    }
                    if (activatingCall != null) {
                        val activatedPolicy = requireNotNull(activation)
                        toolLoopActivated = true
                        record(
                            trace,
                            AgentHarnessTraceEvent.ToolLoopActivated(
                                step = step,
                                toolName = activatingCall.toolName,
                                maxProviderSteps = activatedPolicy.maxProviderSteps,
                                maxToolCallsPerStep = activatedPolicy.maxToolCallsPerStep
                            )
                        )
                    }
                    toolOrchestrator.execute(
                        calls = selectToolCalls(response.calls, toolLoopActivated),
                        sessionId = session.id,
                        beforeEach = ::ensureActive
                    ).forEach { execution ->
                        ensureActive()
                        val call = execution.call
                        val result = execution.result
                        session = appendToolResult(session, call, result)
                        sessionStore.save(session)
                        record(
                            trace,
                            AgentHarnessTraceEvent.ToolExecuted(
                                step = step,
                                callId = call.id,
                                toolName = call.toolName,
                                succeeded = !result.isError,
                                content = result.content,
                                arguments = call.arguments.toMap()
                            )
                        )
                    }
                }
            }
            step += 1
        }

        val limit = providerStepLimit(toolLoopActivated)
        throw AgentHarnessLimitException(
            "Provider '${provider.id}' did not finish within $limit steps."
        )
    }

    private fun providerStepLimit(toolLoopActivated: Boolean): Int {
        return if (toolLoopActivated) {
            config.toolLoopActivation?.maxProviderSteps ?: config.maxProviderSteps
        } else {
            config.maxProviderSteps
        }
    }

    private fun selectToolCalls(
        calls: List<AgentToolCall>,
        toolLoopActivated: Boolean
    ): List<AgentToolCall> {
        val activation = config.toolLoopActivation
        if (!toolLoopActivated || activation == null) {
            return calls
        }
        val activationCalls = calls.filter { call -> call.toolName in activation.toolNames }
        return (activationCalls.ifEmpty { calls }).take(activation.maxToolCallsPerStep)
    }

    private fun ensureActive() {
        if (cancellationSignal.isCancellationRequested()) {
            throw AgentHarnessCancelledException()
        }
    }

    private fun record(
        trace: MutableList<AgentHarnessTraceEvent>,
        event: AgentHarnessTraceEvent
    ) {
        trace += event
        try {
            observer.onEvent(event)
        } catch (_: RuntimeException) {
            // Observability is not allowed to break the Agent protocol.
        }
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
        toolProfile: AgentToolProfile = AgentToolProfile.all(),
        observer: AgentHarnessObserver = AgentHarnessObserver.NONE,
        cancellationSignal: AgentCancellationSignal =
            AgentCancellationSignal.THREAD_INTERRUPTED
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
            config = config,
            observer = observer,
            cancellationSignal = cancellationSignal
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
