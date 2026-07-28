// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import java.util.concurrent.atomic.AtomicBoolean

data class AgentHarnessConfig(
    val maxProviderSteps: Int = 4,
    val maxToolCallsPerStep: Int = 4,
    val toolLoopActivation: AgentToolLoopActivation? = null,
    val maxToolCallsTotal: Int = 256,
    val maxRepeatedFailures: Int = 8,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null
) {
    init {
        require(maxProviderSteps in 1..MAX_PROVIDER_STEPS) {
            "maxProviderSteps must be between 1 and $MAX_PROVIDER_STEPS."
        }
        require(maxToolCallsPerStep in 1..32) { "maxToolCallsPerStep must be between 1 and 32." }
        require(maxToolCallsTotal in 0..10_000) {
            "maxToolCallsTotal must be between 0 and 10000."
        }
        require(maxRepeatedFailures in 1..100) {
            "maxRepeatedFailures must be between 1 and 100."
        }
        require(maxInputTokens == null || maxInputTokens > 0) {
            "maxInputTokens must be positive."
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "maxOutputTokens must be positive."
        }
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

    data class ProviderCompleted(
        val step: Int,
        val responseKind: String
    ) : AgentHarnessTraceEvent

    data class ProviderDisplay(
        val step: Int,
        val event: AgentProviderDisplayEvent
    ) : AgentHarnessTraceEvent

    data class ToolRequested(
        val step: Int,
        val callId: String,
        val toolName: String,
        val arguments: Map<String, String> = emptyMap()
    ) : AgentHarnessTraceEvent

    data class ToolExecuted(
        val step: Int,
        val callId: String,
        val toolName: String,
        val succeeded: Boolean,
        val content: String,
        val arguments: Map<String, String> = emptyMap(),
        val envelope: AgentToolResultEnvelope? = null
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
        var totalToolCalls = 0
        var repeatedFailureSignature: String? = null
        var repeatedFailureCount = 0
        var cumulativeInputTokens = 0
        var cumulativeOutputTokens = 0
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
            validateAttachments(request.attachments)
            val providerRequest = AgentProviderRequest(
                session = session,
                context = context,
                tools = tools,
                providerStep = step,
                attachments = if (step == 1) request.attachments else emptyList()
            )
            val estimatedInputTokens = estimateInputTokens(providerRequest)
            enforceTokenBudget(
                label = "Input",
                used = cumulativeInputTokens,
                next = estimatedInputTokens,
                limit = config.maxInputTokens
            )
            val acceptDisplayEvents = AtomicBoolean(true)
            val displayEventLock = Any()
            var reportedInputTokens: Int? = null
            var reportedOutputTokens: Int? = null
            var streamedOutputChars = 0L
            var displayEventFailure: RuntimeException? = null
            val streamingCallThread = Thread.currentThread()
            val response = try {
                if (provider is AgentStreamingProvider && provider.capabilities.streaming) {
                    provider.respondStreaming(
                        providerRequest,
                        AgentProviderDisplayObserver { displayEvent ->
                            synchronized(displayEventLock) {
                                if (
                                    !acceptDisplayEvents.get() ||
                                    cancellationSignal.isCancellationRequested()
                                ) {
                                    return@AgentProviderDisplayObserver
                                }
                                try {
                                    when (displayEvent) {
                                        is AgentProviderDisplayEvent.TextDelta -> {
                                            streamedOutputChars += displayEvent.text.length
                                            enforceTokenBudget(
                                                label = "Output",
                                                used = cumulativeOutputTokens,
                                                next = estimateTokens(streamedOutputChars),
                                                limit = config.maxOutputTokens
                                            )
                                        }
                                        is AgentProviderDisplayEvent.Usage -> {
                                            reportedInputTokens = displayEvent.inputTokens
                                                ?.also(::requireNonNegativeTokenUsage)
                                            reportedOutputTokens = displayEvent.outputTokens
                                                ?.also(::requireNonNegativeTokenUsage)
                                        }
                                        is AgentProviderDisplayEvent.ActionNarration,
                                        is AgentProviderDisplayEvent.ToolStatus -> Unit
                                    }
                                    record(
                                        trace,
                                        AgentHarnessTraceEvent.ProviderDisplay(
                                            step,
                                            displayEvent
                                        )
                                    )
                                } catch (error: RuntimeException) {
                                    displayEventFailure = error
                                    acceptDisplayEvents.set(false)
                                    if (Thread.currentThread() === streamingCallThread) {
                                        throw error
                                    }
                                }
                            }
                        }
                    )
                } else {
                    provider.respond(providerRequest)
                }
            } finally {
                // An implementation that emits asynchronously after returning
                // cannot mutate trace/session state with late deltas.
                synchronized(displayEventLock) {
                    acceptDisplayEvents.set(false)
                }
            }
            displayEventFailure?.let { error -> throw error }
            ensureActive()
            val inputTokens = reportedInputTokens ?: estimatedInputTokens
            val outputTokens = reportedOutputTokens ?: estimateOutputTokens(
                response,
                streamedOutputChars
            )
            enforceTokenBudget(
                label = "Input",
                used = cumulativeInputTokens,
                next = inputTokens,
                limit = config.maxInputTokens
            )
            enforceTokenBudget(
                label = "Output",
                used = cumulativeOutputTokens,
                next = outputTokens,
                limit = config.maxOutputTokens
            )
            cumulativeInputTokens += inputTokens
            cumulativeOutputTokens += outputTokens
            record(
                trace,
                AgentHarnessTraceEvent.ProviderCompleted(
                    step = step,
                    responseKind = when (response) {
                        is AgentProviderResponse.FinalText -> "final_text"
                        is AgentProviderResponse.ToolRequests -> "tool_requests"
                    }
                )
            )
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
                    val selectedCalls = selectToolCalls(response.calls, toolLoopActivated)
                    if (totalToolCalls + selectedCalls.size > config.maxToolCallsTotal) {
                        throw AgentHarnessLimitException(
                            "Tool-call budget ${config.maxToolCallsTotal} was exhausted."
                        )
                    }
                    totalToolCalls += selectedCalls.size
                    selectedCalls.forEach { call ->
                        record(
                            trace,
                            AgentHarnessTraceEvent.ToolRequested(
                                step = step,
                                callId = call.id,
                                toolName = call.toolName,
                                arguments = call.arguments.toMap()
                            )
                        )
                    }
                    toolOrchestrator.execute(
                        calls = selectedCalls,
                        sessionId = session.id,
                        runId = request.runId,
                        beforeEach = ::ensureActive
                    ).forEach { execution ->
                        ensureActive()
                        val call = execution.call
                        val result = execution.result.let { raw ->
                            raw.copy(
                                content = AgentToolResultEnvelope.boundedProviderContent(raw),
                                envelope = AgentToolResultEnvelope.fromLegacy(
                                    raw,
                                    clock.nowEpochMillis()
                                )
                            )
                        }
                        if (result.isError) {
                            val signature = "${call.toolName}:${result.content}"
                            repeatedFailureCount = if (signature == repeatedFailureSignature) {
                                repeatedFailureCount + 1
                            } else {
                                repeatedFailureSignature = signature
                                1
                            }
                            if (repeatedFailureCount >= config.maxRepeatedFailures) {
                                throw AgentHarnessLimitException(
                                    "Tool '${call.toolName}' repeated the same failure " +
                                        "$repeatedFailureCount times."
                                )
                            }
                        } else {
                            repeatedFailureSignature = null
                            repeatedFailureCount = 0
                        }
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
                                arguments = call.arguments.toMap(),
                                envelope = result.envelope
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

    private fun validateAttachments(attachments: List<AttachmentRef>) {
        attachments.forEach { attachment ->
            if (!provider.capabilities.accepts(attachment.mediaType)) {
                throw AgentHarnessProtocolException(
                    "Provider '${provider.id}' does not accept attachment media type " +
                        "'${attachment.mediaType}'."
                )
            }
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

    private fun estimateInputTokens(request: AgentProviderRequest): Int {
        val characters = request.session.messages.sumOf { message -> message.content.length.toLong() } +
            request.context.sumOf { item ->
                item.source.length.toLong() + item.content.length.toLong()
            } +
            request.tools.sumOf { tool ->
                tool.name.length.toLong() +
                    tool.description.length.toLong() +
                    tool.arguments.sumOf(String::length).toLong()
            } +
            request.attachments.sumOf { attachment ->
                attachment.byteSize +
                    attachment.mediaType.length +
                    attachment.displayName.orEmpty().length
            }
        return estimateTokens(characters)
    }

    private fun estimateOutputTokens(
        response: AgentProviderResponse,
        streamedOutputChars: Long
    ): Int {
        val responseCharacters = when (response) {
            is AgentProviderResponse.FinalText -> response.content.length.toLong()
            is AgentProviderResponse.ToolRequests -> response.calls.sumOf { call ->
                call.toolName.length.toLong() +
                    call.arguments.entries.sumOf { (name, value) ->
                        name.length.toLong() + value.length.toLong()
                    }
            }
        }
        return estimateTokens(maxOf(streamedOutputChars, responseCharacters))
    }

    private fun estimateTokens(characters: Long): Int {
        if (characters <= 0) return 0
        return ((characters + 3L) / 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun enforceTokenBudget(
        label: String,
        used: Int,
        next: Int,
        limit: Int?
    ) {
        if (limit != null && used.toLong() + next > limit) {
            throw AgentHarnessLimitException(
                "$label token budget $limit was exhausted."
            )
        }
    }

    private fun requireNonNegativeTokenUsage(tokens: Int) {
        if (tokens < 0) {
            throw AgentHarnessProtocolException(
                "Provider reported negative token usage."
            )
        }
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
