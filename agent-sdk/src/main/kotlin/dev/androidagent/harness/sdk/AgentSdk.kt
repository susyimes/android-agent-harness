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
import dev.androidagent.harness.EmptyAgentContextProvider
import dev.androidagent.harness.InMemoryAgentSessionStore
import dev.androidagent.harness.TransactionalAgentSessionStore
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    val errorMapper: AgentRunErrorMapper = AgentRunErrorMapper.NONE
) {
    init {
        require(sessionId.isNotBlank()) { "Agent run session id must not be blank." }
        require(userInput.isNotBlank()) { "Agent run input must not be blank." }
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
}

enum class AgentRunErrorKind {
    LIMIT,
    PROTOCOL,
    PROVIDER,
    PERSISTENCE,
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
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        configuration.maxConcurrentRuns,
        SdkThreadFactory(configuration.threadNamePrefix)
    )

    fun run(request: AgentRunRequest): AgentRunHandle {
        check(!closed.get()) { "AgentSdk is closed." }
        val connection = request.providerFactory.connect()
        val transaction = TransactionalAgentSessionStore(sessionStore, request.sessionId)
        val running = RunningAgentRun(
            owner = this,
            runId = UUID.randomUUID().toString(),
            request = request,
            connection = connection,
            transaction = transaction
        )
        val existing = activeBySession.putIfAbsent(request.sessionId, running)
        if (existing != null) {
            runCatching(connection.cancel)
            throw AgentSessionBusyException(request.sessionId)
        }

        running.emit(
            AgentRunEvent.Started(
                runId = running.runId,
                sessionId = request.sessionId,
                providerId = connection.provider.id
            )
        )
        try {
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

    private fun execute(running: RunningAgentRun) {
        running.worker.set(Thread.currentThread())
        try {
            if (running.cancellationRequested.get()) {
                finish(running, AgentRunOutcome.Cancelled(running.cancellationReason.get()))
                return
            }
            val request = running.request
            val harness = AgentHarnessRunner(
                provider = running.connection.provider,
                contextProviders = request.contextProviders,
                tools = request.tools,
                sessionStore = running.transaction,
                config = request.harnessConfig,
                contextPolicy = request.contextPolicy,
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
                        Thread.currentThread().isInterrupted
                }
            )
            val result = harness.run(
                AgentHarnessRequest(request.sessionId, request.userInput)
            )
            val outcome = if (running.cancellationRequested.get()) {
                AgentRunOutcome.Cancelled(running.cancellationReason.get())
            } else {
                AgentRunOutcome.Success(result)
            }
            finish(running, outcome)
        } catch (error: Throwable) {
            val outcome = when {
                running.cancellationRequested.get() -> {
                    AgentRunOutcome.Cancelled(running.cancellationReason.get())
                }
                error is AgentHarnessCancelledException ||
                    error is CancellationException ||
                    error is InterruptedException -> {
                    AgentRunOutcome.Cancelled()
                }
                else -> AgentRunOutcome.Failure(classify(running.request, error))
            }
            finish(running, outcome)
        } finally {
            running.worker.compareAndSet(Thread.currentThread(), null)
            Thread.interrupted()
        }
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
        val finalOutcome = when {
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
                outcome is AgentRunOutcome.Cancelled -> {
                running.transaction.discard()
                outcome
            }
            else -> outcome
        }
        activeBySession.remove(running.sessionId, running)
        running.completion.complete(finalOutcome)
        running.emit(
            AgentRunEvent.Finished(
                runId = running.runId,
                sessionId = running.sessionId,
                outcome = finalOutcome
            )
        )
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        activeBySession.values.toList().forEach { running ->
            requestCancellation(running, "AgentSdk was closed.")
        }
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
        val cancellationReason = AtomicReference("Agent run was cancelled.")
        val worker = AtomicReference<Thread?>()
        val completion = CompletableFuture<AgentRunOutcome>()

        override val sessionId: String
            get() = request.sessionId

        override val isDone: Boolean
            get() = completion.isDone

        override val isCancellationRequested: Boolean
            get() = cancellationRequested.get()

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
        }

        private fun unwrap(wait: () -> AgentRunOutcome): AgentRunOutcome {
            return try {
                wait()
            } catch (error: ExecutionException) {
                throw IllegalStateException("Agent completion failed unexpectedly.", error.cause)
            }
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
}
