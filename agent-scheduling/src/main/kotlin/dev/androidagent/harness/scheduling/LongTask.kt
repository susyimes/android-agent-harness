// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.scheduling

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.sdk.AgentEvent
import dev.androidagent.harness.sdk.AgentRunBudget
import dev.androidagent.harness.sdk.TraceSink
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

enum class LongTaskStatus {
    READY,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED
}

data class LongTaskSpec(
    val id: String,
    val sessionId: String,
    val goal: String,
    val authorizationScopeHash: String,
    val deadlineEpochMillis: Long,
    val resumable: Boolean,
    val maxBursts: Int = 100,
    val maxRepeatedFailures: Int = 3,
    val burstBudget: AgentRunBudget = AgentRunBudget(
        maxProviderSteps = 16,
        maxToolCalls = 16,
        maxWallClockMillis = 5 * 60_000L,
        maxRepeatedFailures = 3
    )
) {
    init {
        require(ScheduleSpec.ID_PATTERN.matches(id))
        require(sessionId.isNotBlank())
        require(goal.isNotBlank())
        require(authorizationScopeHash.isNotBlank())
        require(maxBursts in 1..10_000)
        require(maxRepeatedFailures in 1..100)
    }
}

data class LongTaskCheckpoint(
    val jobId: String,
    val sessionId: String,
    val revision: Long,
    val status: LongTaskStatus,
    val burst: Int,
    val authorizationScopeHash: String,
    val nextAction: String?,
    val evidenceRefs: List<String>,
    val effectRefs: List<String>,
    val repeatedFailureKey: String?,
    val repeatedFailureCount: Int,
    val lastReceipt: DispatchReceipt?,
    val updatedAtEpochMillis: Long
) : Serializable {
    init {
        require(jobId.isNotBlank())
        require(sessionId.isNotBlank())
        require(revision > 0)
        require(burst >= 0)
        require(authorizationScopeHash.isNotBlank())
        require(nextAction == null || nextAction.isNotBlank())
        require(evidenceRefs.none(String::isBlank))
        require(effectRefs.none(String::isBlank))
        require(repeatedFailureKey == null || repeatedFailureKey.isNotBlank())
        require(repeatedFailureCount >= 0)
    }
}

interface LongTaskCheckpointStore {
    fun get(jobId: String): LongTaskCheckpoint?
    fun put(checkpoint: LongTaskCheckpoint, expectedRevision: Long?)
    fun list(): List<LongTaskCheckpoint>
}

interface LongTaskCheckpointMaintenance {
    fun clear(): Int
}

class InMemoryLongTaskCheckpointStore :
    LongTaskCheckpointStore,
    LongTaskCheckpointMaintenance {
    private val values = linkedMapOf<String, LongTaskCheckpoint>()

    @Synchronized
    override fun get(jobId: String): LongTaskCheckpoint? = values[jobId]

    @Synchronized
    override fun put(checkpoint: LongTaskCheckpoint, expectedRevision: Long?) {
        val current = values[checkpoint.jobId]
        require(current?.revision == expectedRevision) {
            "LongTask '${checkpoint.jobId}' checkpoint conflict."
        }
        require(checkpoint.revision == (current?.revision ?: 0L) + 1L) {
            "LongTask checkpoint revision must advance exactly once."
        }
        require(
            current == null ||
                checkpoint.burst == current.burst ||
                checkpoint.burst == current.burst + 1
        ) { "LongTask burst may advance only once." }
        values[checkpoint.jobId] = checkpoint
    }

    @Synchronized
    override fun list(): List<LongTaskCheckpoint> =
        values.values.sortedBy(LongTaskCheckpoint::jobId)

    @Synchronized
    override fun clear(): Int {
        val count = values.size
        values.clear()
        return count
    }
}

class LongTaskCoordinator(
    private val runner: PeriodicRunner,
    private val checkpoints: LongTaskCheckpointStore,
    private val clock: AgentClock = SystemAgentClock,
    private val traceSink: TraceSink = TraceSink.NONE
) {
    private val activeControls = ConcurrentHashMap<String, MutableRunControl>()
    private val pauseRequests = ConcurrentHashMap.newKeySet<String>()

    fun initialize(spec: LongTaskSpec): LongTaskCheckpoint {
        val existing = checkpoints.get(spec.id)
        if (existing != null) return existing
        val value = LongTaskCheckpoint(
            jobId = spec.id,
            sessionId = spec.sessionId,
            revision = 1L,
            status = LongTaskStatus.READY,
            burst = 0,
            authorizationScopeHash = spec.authorizationScopeHash,
            nextAction = spec.goal,
            evidenceRefs = emptyList(),
            effectRefs = emptyList(),
            repeatedFailureKey = null,
            repeatedFailureCount = 0,
            lastReceipt = null,
            updatedAtEpochMillis = clock.nowEpochMillis()
        )
        saveCheckpoint(value, null)
        return value
    }

    fun dispatchBurst(
        spec: LongTaskSpec,
        authorization: OccurrenceAuthorizationSnapshot,
        reason: String = "Continue explicit LongTask."
    ): LongTaskCheckpoint {
        val current = initialize(spec)
        require(current.authorizationScopeHash == spec.authorizationScopeHash) {
            "LongTask authorization scope changed."
        }
        require(current.sessionId == spec.sessionId) {
            "LongTask session changed."
        }
        if (current.status in TERMINAL_STATUSES) return current
        if (current.status == LongTaskStatus.PAUSED) return current
        val now = clock.nowEpochMillis()
        if (now > spec.deadlineEpochMillis) {
            return updateWithoutBurst(
                current,
                LongTaskStatus.EXPIRED,
                "LongTask deadline expired."
            )
        }
        if (current.burst >= spec.maxBursts) {
            return updateWithoutBurst(
                current,
                LongTaskStatus.FAILED,
                "LongTask burst budget exhausted."
            )
        }
        if (!spec.resumable && current.burst > 0 && current.status != LongTaskStatus.RUNNING) {
            return updateWithoutBurst(
                current,
                LongTaskStatus.FAILED,
                "Non-resumable LongTask cannot restart."
            )
        }
        val burst = current.burst + 1
        val occurrenceId = "${spec.id}-burst-$burst"
        val control = MutableRunControl()
        check(activeControls.putIfAbsent(spec.id, control) == null) {
            "LongTask '${spec.id}' already has an active burst."
        }
        val running = current.copy(
            revision = current.revision + 1L,
            status = LongTaskStatus.RUNNING,
            updatedAtEpochMillis = now
        )
        saveCheckpoint(running, current.revision)
        val receipt = try {
            runner.dispatch(
                OccurrenceTrigger(
                    scheduleId = spec.id,
                    scheduleRevision = 1,
                    targetType = ScheduleTargetType.LONG_TASK,
                    occurrenceId = occurrenceId,
                    plannedAtEpochMillis = now,
                    actualAtEpochMillis = now,
                    attempt = 1,
                    reason = reason,
                    authorization = authorization
                ),
                control
            )
        } catch (error: RuntimeException) {
            DispatchReceipt(
                occurrenceId,
                DispatchStatus.FAILED,
                error.message ?: "LongTask runner failed.",
                retryable = true
            )
        } finally {
            activeControls.remove(spec.id, control)
        }
        val latest = checkpoints.get(spec.id) ?: running
        if (latest.status == LongTaskStatus.CANCELLED) {
            return latest.copy(
                revision = latest.revision + 1L,
                status = LongTaskStatus.CANCELLED,
                burst = burst,
                lastReceipt = receipt.copy(
                    status = DispatchStatus.CANCELLED,
                    summary = control.reason ?: "LongTask cancelled.",
                    retryable = false
                ),
                nextAction = null,
                updatedAtEpochMillis = clock.nowEpochMillis()
            ).also { value -> saveCheckpoint(value, latest.revision) }
        }
        val pauseRequested = pauseRequests.remove(spec.id)
        if (latest.status == LongTaskStatus.PAUSED || pauseRequested) {
            return latest.copy(
                revision = latest.revision + 1L,
                status = LongTaskStatus.PAUSED,
                burst = burst,
                lastReceipt = receipt.copy(
                    status = DispatchStatus.CANCELLED,
                    summary = control.reason ?: "LongTask paused.",
                    retryable = false
                ),
                nextAction = latest.nextAction
                    ?: current.nextAction
                    ?: receipt.checkpointRef
                    ?: "Resume from burst $burst.",
                updatedAtEpochMillis = clock.nowEpochMillis()
            ).also { value -> saveCheckpoint(value, latest.revision) }
        }
        if (control.isCancellationRequested) {
            return latest.copy(
                revision = latest.revision + 1L,
                status = LongTaskStatus.CANCELLED,
                burst = burst,
                lastReceipt = receipt.copy(
                    status = DispatchStatus.CANCELLED,
                    summary = control.reason ?: "LongTask cancelled.",
                    retryable = false
                ),
                nextAction = null,
                updatedAtEpochMillis = clock.nowEpochMillis()
            ).also { value -> saveCheckpoint(value, latest.revision) }
        }
        val failureKey = if (receipt.status == DispatchStatus.FAILED) receipt.summary else null
        val repeated = if (failureKey != null && failureKey == current.repeatedFailureKey) {
            current.repeatedFailureCount + 1
        } else if (failureKey != null) {
            1
        } else {
            0
        }
        val status = when {
            receipt.status == DispatchStatus.COMPLETED -> LongTaskStatus.COMPLETED
            receipt.status == DispatchStatus.CANCELLED -> LongTaskStatus.CANCELLED
            receipt.status == DispatchStatus.EXPIRED -> LongTaskStatus.EXPIRED
            receipt.status == DispatchStatus.FAILED &&
                (!receipt.retryable || repeated >= spec.maxRepeatedFailures) ->
                LongTaskStatus.FAILED
            receipt.status in SKIP_STATUSES -> LongTaskStatus.PAUSED
            else -> LongTaskStatus.READY
        }
        val next = latest.copy(
            revision = latest.revision + 1L,
            status = status,
            burst = burst,
            nextAction = when (status) {
                LongTaskStatus.READY ->
                    receipt.checkpointRef ?: "Continue from burst $burst."
                LongTaskStatus.PAUSED ->
                    receipt.checkpointRef
                        ?: current.nextAction
                        ?: "Resume from burst $burst."
                else -> null
            },
            repeatedFailureKey = failureKey,
            repeatedFailureCount = repeated,
            lastReceipt = receipt,
            updatedAtEpochMillis = clock.nowEpochMillis()
        )
        saveCheckpoint(next, latest.revision)
        return next
    }

    fun pause(jobId: String, reason: String = "Paused by user."): Boolean {
        require(reason.isNotBlank())
        val current = checkpoints.get(jobId) ?: return false
        if (current.status in TERMINAL_STATUSES) return false
        if (current.status == LongTaskStatus.PAUSED) return true
        val active = activeControls[jobId]
        if (active != null) pauseRequests += jobId
        val paused = current.copy(
            revision = current.revision + 1L,
            status = LongTaskStatus.PAUSED,
            nextAction = current.nextAction ?: "Resume from burst ${current.burst}.",
            updatedAtEpochMillis = clock.nowEpochMillis()
        )
        saveCheckpoint(paused, current.revision)
        active?.cancel(reason)
        return true
    }

    fun resume(
        jobId: String,
        expectedAuthorizationScopeHash: String
    ): Boolean {
        require(expectedAuthorizationScopeHash.isNotBlank())
        if (activeControls.containsKey(jobId)) return false
        val current = checkpoints.get(jobId) ?: return false
        if (current.status != LongTaskStatus.PAUSED) return false
        if (current.authorizationScopeHash != expectedAuthorizationScopeHash) return false
        val resumed = current.copy(
            revision = current.revision + 1L,
            status = LongTaskStatus.READY,
            nextAction = current.nextAction ?: "Resume from burst ${current.burst}.",
            updatedAtEpochMillis = clock.nowEpochMillis()
        )
        saveCheckpoint(resumed, current.revision)
        return true
    }

    fun stop(jobId: String, reason: String = "Stopped by user."): Boolean {
        val current = checkpoints.get(jobId) ?: return false
        if (current.status in TERMINAL_STATUSES) return false
        pauseRequests.remove(jobId)
        activeControls[jobId]?.cancel(reason)
        val stopped = current.copy(
            revision = current.revision + 1L,
            status = LongTaskStatus.CANCELLED,
            nextAction = null,
            updatedAtEpochMillis = clock.nowEpochMillis()
        )
        saveCheckpoint(stopped, current.revision)
        return true
    }

    private fun updateWithoutBurst(
        current: LongTaskCheckpoint,
        status: LongTaskStatus,
        summary: String
    ): LongTaskCheckpoint {
        val next = current.copy(
            revision = current.revision + 1L,
            status = status,
            nextAction = null,
            lastReceipt = DispatchReceipt(
                occurrenceId = "${current.jobId}-control-${current.burst}",
                status = if (status == LongTaskStatus.EXPIRED) {
                    DispatchStatus.EXPIRED
                } else {
                    DispatchStatus.FAILED
                },
                summary = summary
            ),
            updatedAtEpochMillis = clock.nowEpochMillis()
        )
        saveCheckpoint(next, current.revision)
        return next
    }

    private fun saveCheckpoint(
        checkpoint: LongTaskCheckpoint,
        expectedRevision: Long?
    ) {
        checkpoints.put(checkpoint, expectedRevision)
        try {
            traceSink.emit(
                AgentEvent.CheckpointSaved(
                    runId = "longtask:${checkpoint.jobId}",
                    sessionId = checkpoint.sessionId,
                    occurredAtEpochMillis = checkpoint.updatedAtEpochMillis,
                    revision = checkpoint.revision
                )
            )
        } catch (_: RuntimeException) {
            // Checkpoint persistence is authoritative; trace delivery is observational.
        }
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            LongTaskStatus.COMPLETED,
            LongTaskStatus.FAILED,
            LongTaskStatus.CANCELLED,
            LongTaskStatus.EXPIRED
        )
        val SKIP_STATUSES = setOf(
            DispatchStatus.SKIPPED_DISABLED,
            DispatchStatus.SKIPPED_REVISION_CHANGED,
            DispatchStatus.SKIPPED_DUPLICATE,
            DispatchStatus.SKIPPED_CONSTRAINT,
            DispatchStatus.SKIPPED_AUTHORIZATION
        )
    }
}
