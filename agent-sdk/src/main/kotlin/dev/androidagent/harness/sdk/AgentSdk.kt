// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentContextPolicy
import dev.androidagent.harness.AgentContextProvider
import dev.androidagent.harness.AgentCancellationSignal
import dev.androidagent.harness.AgentHarnessCancelledException
import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentHarnessLimitException
import dev.androidagent.harness.AgentHarnessObserver
import dev.androidagent.harness.AgentHarnessProtocolException
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessResult
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentHarnessTraceEvent
import dev.androidagent.harness.AgentProviderConnection
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentSessionStore
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.AgentToolResultEnvelope
import dev.androidagent.harness.AttachmentRef
import dev.androidagent.harness.AgentProviderDisplayEvent
import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.EmptyAgentContextProvider
import dev.androidagent.harness.InMemoryAgentSessionStore
import dev.androidagent.harness.TransactionalAgentSessionStore
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalObserver
import dev.androidagent.harness.approval.AgentApprovalRequest
import dev.androidagent.harness.approval.governedBy
import dev.androidagent.harness.context.CcpAgentContextProvider
import dev.androidagent.harness.context.CcpV2ContextEngine
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextNeedAnalyzer
import dev.androidagent.harness.context.ContextRouteBlockedException
import dev.androidagent.harness.context.ContextRouteAnswerException
import dev.androidagent.harness.context.ContextRouteGate
import dev.androidagent.harness.context.DelimitedPromptBundleRenderer
import dev.androidagent.harness.context.DeterministicContextRouteGate
import dev.androidagent.harness.context.LegacyContextSourceAdapter
import dev.androidagent.harness.context.NamedContextSource
import dev.androidagent.harness.context.PromptBundleRenderer
import dev.androidagent.harness.context.RuleBasedContextNeedAnalyzer
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class AgentSdkConfiguration(
    val maxConcurrentRuns: Int = 2,
    val threadNamePrefix: String = "agent-sdk"
) {
    init {
        require(maxConcurrentRuns in 1..32) {
            "maxConcurrentRuns must be between 1 and 32."
        }
        require(threadNamePrefix.isNotBlank()) { "threadNamePrefix must not be blank." }
    }
}

data class AgentRunRequest(
    val sessionId: String,
    val userInput: String,
    val providerFactory: AgentProviderFactory,
    val contextProviders: List<AgentContextProvider> = listOf(EmptyAgentContextProvider),
    val tools: List<AgentTool> = emptyList(),
    val harnessConfig: AgentHarnessConfig = AgentHarnessConfig(),
    val contextPolicy: AgentContextPolicy = AgentContextPolicy(),
    val toolProfile: AgentToolProfile = AgentToolProfile.all(),
    val listener: AgentRunListener = AgentRunListener.NONE,
    val errorMapper: AgentRunErrorMapper = AgentRunErrorMapper.NONE,
    val runPolicy: AgentRunPolicy = AgentRunPolicy.compatibility(
        harnessConfig,
        toolProfile.id
    ),
    val approvalCoordinator: AgentApprovalCoordinator = AgentApprovalCoordinator(),
    val traceSink: TraceSink = TraceSink.NONE,
    val contextSources: List<NamedContextSource> = emptyList(),
    val contextNeedAnalyzer: ContextNeedAnalyzer = RuleBasedContextNeedAnalyzer(),
    val contextRouteGate: ContextRouteGate = DeterministicContextRouteGate(),
    val promptBundleRenderer: PromptBundleRenderer = DelimitedPromptBundleRenderer(),
    val contextEngineOptions: AgentContextEngineOptions = AgentContextEngineOptions(),
    val attachments: List<AttachmentRef> = emptyList()
) {
    init {
        require(sessionId.isNotBlank()) { "Agent run session id must not be blank." }
        require(userInput.isNotBlank()) { "Agent run input must not be blank." }
        require(attachments.map { attachment -> attachment.id }.distinct().size == attachments.size) {
            "Attachment ids must be unique within an Agent run."
        }
    }
}

sealed interface AgentRunEvent {
    val runId: String
    val sessionId: String

    data class Started(
        override val runId: String,
        override val sessionId: String,
        val providerId: String
    ) : AgentRunEvent

    data class Trace(
        override val runId: String,
        override val sessionId: String,
        val event: AgentHarnessTraceEvent
    ) : AgentRunEvent

    data class Finished(
        override val runId: String,
        override val sessionId: String,
        val outcome: AgentRunOutcome
    ) : AgentRunEvent
}

fun interface AgentRunListener {
    /**
     * Called synchronously. Started may arrive on the caller thread; trace and
     * terminal events normally arrive on an SDK worker thread.
     */
    fun onEvent(event: AgentRunEvent)

    companion object {
        val NONE = AgentRunListener {}
    }
}

sealed interface AgentRunOutcome {
    data class Success(val result: AgentHarnessResult) : AgentRunOutcome

    data class Failure(val error: AgentRunError) : AgentRunOutcome

    data class Cancelled(val reason: String = "Agent run was cancelled.") : AgentRunOutcome

    data class Expired(val reason: String = "Agent run exceeded its wall-clock budget.") :
        AgentRunOutcome
}

enum class AgentRunErrorKind {
    LIMIT,
    PROTOCOL,
    PROVIDER,
    PERSISTENCE,
    CONTEXT,
    TIMEOUT,
    INTERNAL
}

data class AgentRunError(
    val kind: AgentRunErrorKind,
    val message: String,
    val cause: Throwable? = null
)

fun interface AgentRunErrorMapper {
    /** Return null to let the SDK apply its built-in classification. */
    fun map(error: Throwable): AgentRunError?

    companion object {
        val NONE = AgentRunErrorMapper { null }
    }
}

interface AgentRunHandle {
    val runId: String
    val sessionId: String

    val isDone: Boolean
    val isCancellationRequested: Boolean
    val state: AgentRunState
        get() = when {
            isCancellationRequested -> AgentRunState.CANCELLED
            isDone -> AgentRunState.COMPLETED
            else -> AgentRunState.RUNNING
        }

    /** Returns true only for the first accepted cancellation request. */
    fun cancel(reason: String = "Cancelled by host."): Boolean

    fun await(): AgentRunOutcome

    @Throws(TimeoutException::class)
    fun await(timeout: Long, unit: TimeUnit): AgentRunOutcome
}

class AgentSessionBusyException(sessionId: String) :
    IllegalStateException("Session '$sessionId' already has an active Agent run.")

/**
 * Host-facing SDK runtime.
 *
 * Each run gets a fresh provider connection and transactional session view.
 * Successful runs commit their whole transcript; failed or cancelled runs
 * leave the previously committed session untouched.
 */
class AgentSdk(
    private val sessionStore: AgentSessionStore = InMemoryAgentSessionStore(),
    configuration: AgentSdkConfiguration = AgentSdkConfiguration()
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activeBySession = ConcurrentHashMap<String, RunningAgentRun>()
    private val sessionLifecycleLock = Any()
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        configuration.maxConcurrentRuns,
        SdkThreadFactory(configuration.threadNamePrefix)
    )
    private val deadlineExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            SdkThreadFactory("${configuration.threadNamePrefix}-deadline")
        )

    fun run(request: AgentRunRequest): AgentRunHandle {
        check(!closed.get()) { "AgentSdk is closed." }
        val connection = request.providerFactory.connect()
        val (running, existing) = try {
            synchronized(sessionLifecycleLock) {
                val candidate = RunningAgentRun(
                    owner = this,
                    runId = UUID.randomUUID().toString(),
                    request = request,
                    connection = connection,
                    transaction = TransactionalAgentSessionStore(
                        sessionStore,
                        request.sessionId
                    )
                )
                candidate to activeBySession.putIfAbsent(request.sessionId, candidate)
            }
        } catch (error: RuntimeException) {
            runCatching(connection.cancel)
            throw error
        }
        if (existing != null) {
            runCatching(connection.cancel)
            throw AgentSessionBusyException(request.sessionId)
        }

        running.transition(AgentRunState.QUEUED)
        running.emit(
            AgentRunEvent.Started(
                runId = running.runId,
                sessionId = request.sessionId,
                providerId = connection.provider.id
            )
        )
        try {
            running.deadlineTask.set(
                deadlineExecutor.schedule(
                    { requestExpiration(running) },
                    request.runPolicy.budget.maxWallClockMillis,
                    TimeUnit.MILLISECONDS
                )
            )
            executor.execute { execute(running) }
        } catch (error: RuntimeException) {
            finish(
                running,
                AgentRunOutcome.Failure(
                    AgentRunError(
                        kind = AgentRunErrorKind.INTERNAL,
                        message = error.message ?: error.javaClass.simpleName,
                        cause = error
                    )
                )
            )
            throw error
        }
        return running
    }

    fun loadSession(sessionId: String) = sessionStore.load(sessionId)

    /**
     * Lists durable conversations when the configured store implements
     * [AgentSessionCatalog]. Plain [AgentSessionStore] implementations expose
     * no catalog and therefore return an empty list.
     */
    fun listSessions(): List<AgentSessionSummary> {
        return (sessionStore as? AgentSessionCatalog)?.listSessions().orEmpty()
    }

    /**
     * Deletes a committed session. An active session is fenced so its eventual
     * successful commit cannot silently recreate data the host just deleted.
     */
    fun deleteSession(sessionId: String): Boolean {
        require(sessionId.isNotBlank()) { "Session id must not be blank." }
        return synchronized(sessionLifecycleLock) {
            if (activeBySession.containsKey(sessionId)) {
                throw AgentSessionBusyException(sessionId)
            }
            (sessionStore as? AgentSessionCatalog)?.deleteSession(sessionId) ?: false
        }
    }

    /**
     * Clears committed sessions only when no run is active.
     *
     * Returns zero for stores that do not implement [AgentSessionCatalog].
     */
    fun clearSessions(): Int {
        return synchronized(sessionLifecycleLock) {
            check(activeBySession.isEmpty()) {
                "Cannot clear Agent sessions while a run is active."
            }
            (sessionStore as? AgentSessionCatalog)?.clearSessions() ?: 0
        }
    }

    private fun execute(running: RunningAgentRun) {
        running.worker.set(Thread.currentThread())
        running.transition(AgentRunState.RUNNING)
        try {
            if (running.expirationRequested.get()) {
                finish(running, AgentRunOutcome.Expired())
                return
            }
            if (running.cancellationRequested.get()) {
                finish(running, AgentRunOutcome.Cancelled(running.cancellationReason.get()))
                return
            }
            val request = running.request
            val contextProvider = ccpContextProvider(running)
            val runApprovals = request.approvalCoordinator.observedBy(
                object : AgentApprovalObserver {
                    override fun onRequested(request: AgentApprovalRequest) {
                        if (!running.terminal.get()) {
                            running.transition(AgentRunState.WAITING_APPROVAL)
                            running.emitStableEvent(
                                AgentEvent.ApprovalRequested(
                                    runId = running.runId,
                                    sessionId = running.sessionId,
                                    occurredAtEpochMillis = System.currentTimeMillis(),
                                    approvalId = request.id,
                                    effectSummary = request.effectSummary
                                )
                            )
                        }
                    }

                    override fun onResolved(
                        request: AgentApprovalRequest,
                        decision: AgentApprovalDecision
                    ) {
                        if (!running.terminal.get()) {
                            running.emitStableEvent(
                                AgentEvent.ApprovalResolved(
                                    runId = running.runId,
                                    sessionId = running.sessionId,
                                    occurredAtEpochMillis = System.currentTimeMillis(),
                                    approvalId = request.id,
                                    decision = decision.name
                                )
                            )
                            running.transition(AgentRunState.RUNNING)
                        }
                    }
                }
            )
            val harness = AgentHarnessRunner(
                provider = running.connection.provider,
                contextProviders = listOf(contextProvider),
                tools = request.tools.map { tool ->
                    tool.governedBy(runApprovals)
                },
                sessionStore = running.transaction,
                config = effectiveHarnessConfig(request),
                contextPolicy = AgentContextPolicy(
                    maxItems = 256,
                    maxContentChars = 1_000_000
                ),
                toolProfile = request.toolProfile,
                observer = AgentHarnessObserver { trace ->
                    if (!running.terminal.get() && !running.cancellationRequested.get()) {
                        running.emit(
                            AgentRunEvent.Trace(
                                runId = running.runId,
                                sessionId = request.sessionId,
                                event = trace
                            )
                        )
                    }
                },
                cancellationSignal = AgentCancellationSignal {
                    running.cancellationRequested.get() ||
                        running.expirationRequested.get() ||
                        Thread.currentThread().isInterrupted
                }
            )
            val result = harness.run(
                AgentHarnessRequest(
                    sessionId = request.sessionId,
                    userInput = request.userInput,
                    runId = running.runId,
                    attachments = request.attachments
                )
            )
            val outcome = when {
                running.expirationRequested.get() -> AgentRunOutcome.Expired()
                running.cancellationRequested.get() ->
                    AgentRunOutcome.Cancelled(running.cancellationReason.get())
                else -> AgentRunOutcome.Success(result)
            }
            finish(running, outcome)
        } catch (error: Throwable) {
            val outcome = when {
                running.expirationRequested.get() -> AgentRunOutcome.Expired()
                running.cancellationRequested.get() -> {
                    AgentRunOutcome.Cancelled(running.cancellationReason.get())
                }
                error is AgentHarnessCancelledException ||
                    error is CancellationException ||
                    error is InterruptedException -> {
                    AgentRunOutcome.Cancelled()
                }
                error is ContextRouteAnswerException -> localRouteOutcome(running, error)
                else -> AgentRunOutcome.Failure(classify(running.request, error))
            }
            finish(running, outcome)
        } finally {
            running.worker.compareAndSet(Thread.currentThread(), null)
            Thread.interrupted()
        }
    }

    private fun localRouteOutcome(
        running: RunningAgentRun,
        route: ContextRouteAnswerException
    ): AgentRunOutcome {
        return runCatching {
            val session = checkNotNull(running.transaction.load(running.sessionId)) {
                "Context route ended before the staged user turn was available."
            }
            val updated = session.append(
                AgentMessage(
                    id = "message-${UUID.randomUUID()}",
                    sessionId = running.sessionId,
                    role = AgentRole.ASSISTANT,
                    content = route.output,
                    createdAtEpochMillis = System.currentTimeMillis()
                )
            )
            running.transaction.save(updated)
            AgentRunOutcome.Success(
                AgentHarnessResult(
                    session = updated,
                    output = route.output,
                    providerSteps = 0,
                    trace = emptyList()
                )
            )
        }.getOrElse { error ->
            AgentRunOutcome.Failure(
                AgentRunError(
                    kind = AgentRunErrorKind.PERSISTENCE,
                    message = error.message ?: "Could not commit terminal context route.",
                    cause = error
                )
            )
        }
    }

    private fun ccpContextProvider(running: RunningAgentRun): CcpAgentContextProvider {
        val request = running.request
        val sources = buildList {
            if (request.contextProviders.isNotEmpty()) {
                add(
                    NamedContextSource(
                        id = LEGACY_CONTEXT_SOURCE_ID,
                        source = LegacyContextSourceAdapter(
                            providers = request.contextProviders,
                            allowedTrust = request.contextPolicy.allowedTrust
                        )
                    )
                )
            }
            addAll(request.contextSources)
        }
        val engine = CcpV2ContextEngine(
            sources = sources,
            analyzer = request.contextNeedAnalyzer,
            routeGate = request.contextRouteGate,
            renderer = request.promptBundleRenderer
        )
        return CcpAgentContextProvider(
            engine = engine,
            requestFactory = { legacy ->
                val options = request.contextEngineOptions
                val inferredInputTokens = maxOf(1, request.contextPolicy.maxContentChars / 4)
                val runInputLimit = request.runPolicy.budget.maxInputTokens
                val requestedTotal = options.tokenBudget
                    ?: inferredInputTokens + options.outputReserve
                val boundedTotal = runInputLimit?.let { limit ->
                    minOf(requestedTotal, limit + options.outputReserve)
                } ?: requestedTotal
                val totalTokens = maxOf(2, boundedTotal)
                val reserve = minOf(options.outputReserve, totalTokens - 1)
                ContextEngineRequest(
                    session = legacy.session,
                    userInput = legacy.userInput,
                    taskType = options.resolvedTaskType(request.runPolicy.trigger),
                    trigger = request.runPolicy.trigger.name,
                    requestedSourceIds = options.requestedSourceIds,
                    requiredCapabilities = options.requiredCapabilities,
                    entities = options.entities,
                    timeRange = options.timeRange,
                    riskLevel = options.riskLevel,
                    privacyCeiling = options.privacyCeiling,
                    tokenBudget = totalTokens,
                    outputReserve = reserve,
                    maxItems = request.contextPolicy.maxItems
                )
            },
            onCompilation = { compilation ->
                val now = System.currentTimeMillis()
                running.emitStableEvent(
                    AgentEvent.ContextCompiled(
                        runId = running.runId,
                        sessionId = running.sessionId,
                        occurredAtEpochMillis = now,
                        selectedIds = compilation.evidencePack.items.map { item -> item.id },
                        droppedIds = compilation.evidencePack.dropped.map { item ->
                            item.candidateId
                        },
                        totalContentChars = compilation.promptBundle.contextItems
                            .sumOf { item -> item.content.length }
                    )
                )
                running.emitStableEvent(
                    AgentEvent.RouteDecided(
                        runId = running.runId,
                        sessionId = running.sessionId,
                        occurredAtEpochMillis = now,
                        action = compilation.route.action.name,
                        reason = compilation.route.reason
                    )
                )
            }
        )
    }

    private fun effectiveHarnessConfig(request: AgentRunRequest): AgentHarnessConfig {
        val budget = request.runPolicy.budget
        val activation = request.harnessConfig.toolLoopActivation?.let { configured ->
            configured.copy(
                maxProviderSteps = minOf(configured.maxProviderSteps, budget.maxProviderSteps)
            )
        }
        return request.harnessConfig.copy(
            maxProviderSteps = minOf(
                request.harnessConfig.maxProviderSteps,
                budget.maxProviderSteps
            ),
            toolLoopActivation = activation,
            maxToolCallsTotal = minOf(
                request.harnessConfig.maxToolCallsTotal,
                budget.maxToolCalls
            ),
            maxRepeatedFailures = minOf(
                request.harnessConfig.maxRepeatedFailures,
                budget.maxRepeatedFailures
            ),
            maxInputTokens = minNullable(
                request.harnessConfig.maxInputTokens,
                budget.maxInputTokens
            ),
            maxOutputTokens = minNullable(
                request.harnessConfig.maxOutputTokens,
                budget.maxOutputTokens
            )
        )
    }

    private fun minNullable(first: Int?, second: Int?): Int? = when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

    private fun classify(request: AgentRunRequest, error: Throwable): AgentRunError {
        try {
            request.errorMapper.map(error)
        } catch (_: RuntimeException) {
            null
        }?.let { mapped -> return mapped }
        val kind = when (error) {
            is AgentHarnessLimitException -> AgentRunErrorKind.LIMIT
            is AgentHarnessProtocolException -> AgentRunErrorKind.PROTOCOL
            is ContextRouteBlockedException -> AgentRunErrorKind.CONTEXT
            is IllegalStateException -> AgentRunErrorKind.PROVIDER
            else -> AgentRunErrorKind.INTERNAL
        }
        return AgentRunError(
            kind = kind,
            message = error.message ?: error.javaClass.simpleName,
            cause = error
        )
    }

    private fun finish(running: RunningAgentRun, outcome: AgentRunOutcome) {
        if (!running.terminal.compareAndSet(false, true)) {
            return
        }
        running.deadlineTask.getAndSet(null)?.cancel(false)
        val finalOutcome = when {
            outcome is AgentRunOutcome.Success && running.expirationRequested.get() -> {
                running.transaction.discard()
                AgentRunOutcome.Expired()
            }
            outcome is AgentRunOutcome.Success && running.cancellationRequested.get() -> {
                running.transaction.discard()
                AgentRunOutcome.Cancelled(running.cancellationReason.get())
            }
            outcome is AgentRunOutcome.Success -> {
                try {
                    check(running.transaction.commit()) {
                        "Agent session transaction was not open at commit."
                    }
                    outcome
                } catch (error: RuntimeException) {
                    running.transaction.discard()
                    AgentRunOutcome.Failure(
                        AgentRunError(
                            kind = AgentRunErrorKind.PERSISTENCE,
                            message = "Could not commit Agent session: " +
                                (error.message ?: error.javaClass.simpleName),
                            cause = error
                        )
                    )
                }
            }
            outcome is AgentRunOutcome.Failure ||
                outcome is AgentRunOutcome.Cancelled ||
                outcome is AgentRunOutcome.Expired -> {
                running.transaction.discard()
                outcome
            }
            else -> outcome
        }
        running.transition(
            when (finalOutcome) {
                is AgentRunOutcome.Success -> AgentRunState.COMPLETED
                is AgentRunOutcome.Failure -> AgentRunState.FAILED
                is AgentRunOutcome.Cancelled -> AgentRunState.CANCELLED
                is AgentRunOutcome.Expired -> AgentRunState.EXPIRED
            }
        )
        activeBySession.remove(running.sessionId, running)
        running.deliverCompletion(finalOutcome)
    }

    private fun requestCancellation(running: RunningAgentRun, reason: String): Boolean {
        if (running.terminal.get() ||
            !running.cancellationRequested.compareAndSet(false, true)
        ) {
            return false
        }
        running.cancellationReason.set(reason.ifBlank { "Cancelled by host." })
        runCatching(running.connection.cancel)
        running.worker.get()?.interrupt()
        finish(running, AgentRunOutcome.Cancelled(running.cancellationReason.get()))
        return true
    }

    private fun requestExpiration(running: RunningAgentRun): Boolean {
        if (running.terminal.get() ||
            !running.expirationRequested.compareAndSet(false, true)
        ) {
            return false
        }
        runCatching(running.connection.cancel)
        running.worker.get()?.interrupt()
        finish(running, AgentRunOutcome.Expired())
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        activeBySession.values.toList().forEach { running ->
            requestCancellation(running, "AgentSdk was closed.")
        }
        deadlineExecutor.shutdownNow()
        executor.shutdownNow()
    }

    private class RunningAgentRun(
        private val owner: AgentSdk,
        override val runId: String,
        val request: AgentRunRequest,
        val connection: AgentProviderConnection,
        val transaction: TransactionalAgentSessionStore
    ) : AgentRunHandle {
        val terminal = AtomicBoolean(false)
        val cancellationRequested = AtomicBoolean(false)
        val expirationRequested = AtomicBoolean(false)
        val cancellationReason = AtomicReference("Agent run was cancelled.")
        val worker = AtomicReference<Thread?>()
        val deadlineTask = AtomicReference<ScheduledFuture<*>?>()
        val completion = CompletableFuture<AgentRunOutcome>()
        private val stateRef = AtomicReference(AgentRunState.CREATED)

        override val sessionId: String
            get() = request.sessionId

        override val isDone: Boolean
            get() = completion.isDone

        override val isCancellationRequested: Boolean
            get() = cancellationRequested.get()

        override val state: AgentRunState
            get() = stateRef.get()

        override fun cancel(reason: String): Boolean = owner.requestCancellation(this, reason)

        override fun await(): AgentRunOutcome = unwrap { completion.get() }

        override fun await(timeout: Long, unit: TimeUnit): AgentRunOutcome {
            require(timeout > 0) { "Timeout must be positive." }
            return unwrap { completion.get(timeout, unit) }
        }

        fun emit(event: AgentRunEvent) {
            try {
                request.listener.onEvent(event)
            } catch (_: RuntimeException) {
                // Host observability is never allowed to break the run.
            }
            stableEvent(event)?.let(::emitStableEvent)
            if (event is AgentRunEvent.Trace) {
                val trace = event.event as? AgentHarnessTraceEvent.ToolExecuted
                trace?.envelope?.candidates.orEmpty().forEach { candidate ->
                    emitStableEvent(
                        AgentEvent.CandidateProduced(
                            runId = runId,
                            sessionId = sessionId,
                            occurredAtEpochMillis = System.currentTimeMillis(),
                            candidateId = candidate.id,
                            candidateType = candidate.type
                        )
                    )
                }
            }
        }

        /**
         * Publishes the durable terminal trace before unblocking awaiters, then
         * invokes the legacy listener after the future is complete so listener
         * code may safely inspect or await the handle.
         */
        fun deliverCompletion(outcome: AgentRunOutcome) {
            val event = AgentRunEvent.Finished(
                runId = runId,
                sessionId = sessionId,
                outcome = outcome
            )
            stableEvent(event)?.let(::emitStableEvent)
            completion.complete(outcome)
            try {
                request.listener.onEvent(event)
            } catch (_: RuntimeException) {
                // Host observability is never allowed to break completion.
            }
        }

        fun transition(next: AgentRunState) {
            if (terminal.get() && next !in TERMINAL_STATES) {
                return
            }
            while (true) {
                val previous = stateRef.get()
                if (previous == next || previous in TERMINAL_STATES) {
                    return
                }
                if (stateRef.compareAndSet(previous, next)) {
                    emitStableEvent(
                        AgentEvent.RunStateChanged(
                            runId = runId,
                            sessionId = sessionId,
                            occurredAtEpochMillis = System.currentTimeMillis(),
                            previous = previous,
                            current = next
                        )
                    )
                    return
                }
            }
        }

        private fun stableEvent(event: AgentRunEvent): AgentEvent? {
            val now = System.currentTimeMillis()
            return when (event) {
                is AgentRunEvent.Started -> AgentEvent.RunStarted(
                    runId = event.runId,
                    sessionId = event.sessionId,
                    occurredAtEpochMillis = now,
                    trigger = request.runPolicy.trigger,
                    providerId = event.providerId,
                    budget = request.runPolicy.budget
                )

                is AgentRunEvent.Trace -> when (val trace = event.event) {
                    is AgentHarnessTraceEvent.ContextLoaded -> null

                    is AgentHarnessTraceEvent.ProviderInvoked -> AgentEvent.ProviderStarted(
                        runId = event.runId,
                        sessionId = event.sessionId,
                        occurredAtEpochMillis = now,
                        step = trace.step,
                        providerId = trace.providerId,
                        toolNames = trace.toolNames
                    )

                    is AgentHarnessTraceEvent.ProviderCompleted -> AgentEvent.ProviderCompleted(
                        runId = event.runId,
                        sessionId = event.sessionId,
                        occurredAtEpochMillis = now,
                        step = trace.step,
                        responseKind = trace.responseKind
                    )

                    is AgentHarnessTraceEvent.ProviderDisplay -> when (val display = trace.event) {
                        is AgentProviderDisplayEvent.TextDelta -> AgentEvent.ProviderDelta(
                            runId = event.runId,
                            sessionId = event.sessionId,
                            occurredAtEpochMillis = now,
                            step = trace.step,
                            text = display.text
                        )

                        is AgentProviderDisplayEvent.ActionNarration ->
                            AgentEvent.ProviderDisplay(
                                runId = event.runId,
                                sessionId = event.sessionId,
                                occurredAtEpochMillis = now,
                                step = trace.step,
                                kind = "action_narration",
                                text = display.text
                            )

                        is AgentProviderDisplayEvent.ToolStatus ->
                            AgentEvent.ProviderDisplay(
                                runId = event.runId,
                                sessionId = event.sessionId,
                                occurredAtEpochMillis = now,
                                step = trace.step,
                                kind = "tool_status",
                                text = display.status,
                                toolName = display.toolName
                            )

                        is AgentProviderDisplayEvent.Usage ->
                            AgentEvent.ProviderDisplay(
                                runId = event.runId,
                                sessionId = event.sessionId,
                                occurredAtEpochMillis = now,
                                step = trace.step,
                                kind = "usage",
                                inputTokens = display.inputTokens,
                                outputTokens = display.outputTokens
                            )
                    }

                    is AgentHarnessTraceEvent.ToolRequested -> AgentEvent.ToolRequested(
                        runId = event.runId,
                        sessionId = event.sessionId,
                        occurredAtEpochMillis = now,
                        step = trace.step,
                        callId = trace.callId,
                        toolName = trace.toolName,
                        argumentNames = trace.arguments.keys
                    )

                    is AgentHarnessTraceEvent.ToolExecuted -> AgentEvent.ToolCompleted(
                        runId = event.runId,
                        sessionId = event.sessionId,
                        occurredAtEpochMillis = now,
                        step = trace.step,
                        callId = trace.callId,
                        toolName = trace.toolName,
                        envelope = trace.envelope ?: AgentToolResultEnvelope(
                            status = if (trace.succeeded) {
                                dev.androidagent.harness.AgentToolResultStatus.SUCCESS
                            } else {
                                dev.androidagent.harness.AgentToolResultStatus.FAILURE
                            },
                            summary = trace.content.ifBlank { "Tool returned no summary." },
                            createdAtEpochMillis = now
                        )
                    )

                    is AgentHarnessTraceEvent.ToolLoopActivated -> AgentEvent.DeviceLoopActivated(
                        runId = event.runId,
                        sessionId = event.sessionId,
                        occurredAtEpochMillis = now,
                        step = trace.step,
                        toolName = trace.toolName,
                        maxProviderSteps = trace.maxProviderSteps
                    )

                    is AgentHarnessTraceEvent.Completed -> null
                }

                is AgentRunEvent.Finished -> AgentEvent.RunFinished(
                    runId = event.runId,
                    sessionId = event.sessionId,
                    occurredAtEpochMillis = now,
                    state = state,
                    summary = when (val outcome = event.outcome) {
                        is AgentRunOutcome.Success -> outcome.result.output
                        is AgentRunOutcome.Failure -> outcome.error.message
                        is AgentRunOutcome.Cancelled -> outcome.reason
                        is AgentRunOutcome.Expired -> outcome.reason
                    }
                )
            }
        }

        fun emitStableEvent(event: AgentEvent) {
            try {
                request.traceSink.emit(event)
            } catch (_: RuntimeException) {
                // Stable trace delivery is also observational and fail-open.
            }
        }

        private fun unwrap(wait: () -> AgentRunOutcome): AgentRunOutcome {
            return try {
                wait()
            } catch (error: ExecutionException) {
                throw IllegalStateException("Agent completion failed unexpectedly.", error.cause)
            }
        }

        private companion object {
            val TERMINAL_STATES = setOf(
                AgentRunState.COMPLETED,
                AgentRunState.FAILED,
                AgentRunState.CANCELLED,
                AgentRunState.EXPIRED
            )
        }
    }

    private class SdkThreadFactory(prefix: String) : ThreadFactory {
        private val next = AtomicInteger(1)
        private val normalizedPrefix = prefix.trim()

        override fun newThread(task: Runnable): Thread {
            return Thread(task, "$normalizedPrefix-${next.getAndIncrement()}").apply {
                isDaemon = false
            }
        }
    }

    private companion object {
        const val LEGACY_CONTEXT_SOURCE_ID = "legacy-agent-context"
    }
}
