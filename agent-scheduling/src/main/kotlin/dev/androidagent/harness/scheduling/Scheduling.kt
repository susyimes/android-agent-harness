// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.scheduling

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentEffectAuthorization
import dev.androidagent.harness.approval.AgentEffectHasher
import dev.androidagent.harness.approval.AgentEffectIntent
import java.io.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue

enum class ScheduleTargetType {
    HEARTBEAT,
    DREAM,
    PROACTIVE,
    CRON,
    LONG_TASK
}

enum class ScheduleCadenceType {
    ONE_TIME,
    INTERVAL,
    DAILY,
    WEEKLY,
    WINDOW
}

enum class MissedRunPolicy {
    SKIP,
    RUN_ONCE,
    NEXT_WINDOW
}

enum class DeliveryPolicy {
    SILENT,
    IN_APP,
    NOTIFICATION,
    VISIBLE_LONG_TASK
}

data class ScheduleConstraints(
    val requiresNetwork: Boolean = false,
    val requiresUnmeteredNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val requiresDeviceIdle: Boolean = false
) : Serializable {
    init {
        require(!requiresUnmeteredNetwork || requiresNetwork) {
            "Unmetered network implies a network requirement."
        }
    }
}

data class ScheduleCadence(
    val type: ScheduleCadenceType,
    val oneTimeAtEpochMillis: Long? = null,
    val intervalMillis: Long? = null,
    val localTime: String? = null,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val windowStartLocalTime: String? = null,
    val windowEndLocalTime: String? = null
) : Serializable {
    init {
        when (type) {
            ScheduleCadenceType.ONE_TIME ->
                require(oneTimeAtEpochMillis != null) { "One-time schedule needs a time." }
            ScheduleCadenceType.INTERVAL ->
                require(intervalMillis != null && intervalMillis > 0) {
                    "Interval schedule needs a positive interval."
                }
            ScheduleCadenceType.DAILY ->
                require(parseTime(localTime) != null) { "Daily schedule needs local time." }
            ScheduleCadenceType.WEEKLY -> {
                require(parseTime(localTime) != null) { "Weekly schedule needs local time." }
                require(weekdays.isNotEmpty()) { "Weekly schedule needs weekdays." }
            }
            ScheduleCadenceType.WINDOW -> {
                require(intervalMillis != null && intervalMillis > 0) {
                    "Window schedule needs a positive interval."
                }
                require(parseTime(windowStartLocalTime) != null) {
                    "Window schedule needs a start time."
                }
                require(parseTime(windowEndLocalTime) != null) {
                    "Window schedule needs an end time."
                }
                require(parseTime(windowStartLocalTime)!! < parseTime(windowEndLocalTime)!!) {
                    "Window start must precede window end."
                }
            }
        }
    }

    companion object {
        fun oneTime(atEpochMillis: Long) =
            ScheduleCadence(ScheduleCadenceType.ONE_TIME, oneTimeAtEpochMillis = atEpochMillis)

        fun interval(intervalMillis: Long) =
            ScheduleCadence(ScheduleCadenceType.INTERVAL, intervalMillis = intervalMillis)

        fun daily(localTime: LocalTime) =
            ScheduleCadence(ScheduleCadenceType.DAILY, localTime = localTime.toString())

        fun weekly(localTime: LocalTime, weekdays: Set<DayOfWeek>) =
            ScheduleCadence(
                ScheduleCadenceType.WEEKLY,
                localTime = localTime.toString(),
                weekdays = weekdays
            )

        private fun parseTime(value: String?): LocalTime? =
            value?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    }
}

data class ScheduleSpec(
    val id: String,
    val targetType: ScheduleTargetType,
    val cadence: ScheduleCadence,
    val timezone: String,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long? = null,
    val executionWindowMillis: Long = 15 * 60 * 1_000L,
    val missedRunPolicy: MissedRunPolicy = MissedRunPolicy.SKIP,
    val maxJitterMillis: Long = 0,
    val constraints: ScheduleConstraints = ScheduleConstraints(),
    val revision: Long,
    val enabled: Boolean,
    val deliveryPolicy: DeliveryPolicy,
    val reason: String,
    val toolProfileId: String,
    val contextPolicyId: String
) : Serializable {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid schedule id '$id'." }
        require(runCatching { ZoneId.of(timezone) }.isSuccess) { "Invalid schedule timezone." }
        require(validUntilEpochMillis == null || validUntilEpochMillis >= validFromEpochMillis)
        require(executionWindowMillis > 0)
        require(maxJitterMillis >= 0)
        require(revision > 0)
        require(reason.isNotBlank())
        require(toolProfileId.isNotBlank())
        require(contextPolicyId.isNotBlank())
    }

    companion object {
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    }
}

data class OccurrenceAuthorizationSnapshot(
    val grantedCapabilityIds: Set<String>,
    val credentialRevision: String?,
    val policyRevision: String,
    val capturedAtEpochMillis: Long
) : Serializable {
    init {
        require(grantedCapabilityIds.none(String::isBlank))
        require(credentialRevision == null || credentialRevision.isNotBlank())
        require(policyRevision.isNotBlank())
    }
}

data class OccurrenceTrigger(
    val scheduleId: String,
    val scheduleRevision: Long,
    val targetType: ScheduleTargetType,
    val occurrenceId: String,
    val plannedAtEpochMillis: Long,
    val actualAtEpochMillis: Long,
    val attempt: Int,
    val reason: String,
    val authorization: OccurrenceAuthorizationSnapshot
) : Serializable {
    init {
        require(scheduleId.isNotBlank())
        require(scheduleRevision > 0)
        require(occurrenceId.isNotBlank())
        require(attempt > 0)
        require(reason.isNotBlank())
    }
}

enum class DispatchStatus {
    ACCEPTED,
    COMPLETED,
    SKIPPED_DISABLED,
    SKIPPED_REVISION_CHANGED,
    SKIPPED_DUPLICATE,
    SKIPPED_CONSTRAINT,
    SKIPPED_AUTHORIZATION,
    CANCELLED,
    FAILED,
    EXPIRED
}

data class DispatchReceipt(
    val occurrenceId: String,
    val status: DispatchStatus,
    val summary: String,
    val retryable: Boolean = false,
    val checkpointRef: String? = null
) : Serializable {
    init {
        require(occurrenceId.isNotBlank())
        require(summary.isNotBlank())
        require(checkpointRef == null || checkpointRef.isNotBlank())
    }
}

interface RunControl {
    val isCancellationRequested: Boolean
    val reason: String?

    fun onCancel(listener: (String) -> Unit)
}

class MutableRunControl : RunControl {
    private val cancelled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var cancellationReason: String? = null

    override val isCancellationRequested: Boolean
        get() = cancelled.get()

    override val reason: String?
        get() = cancellationReason

    override fun onCancel(listener: (String) -> Unit) {
        val reason = cancellationReason
        if (reason != null) listener(reason) else listeners += listener
    }

    fun cancel(reason: String = "Stopped by user."): Boolean {
        require(reason.isNotBlank())
        if (!cancelled.compareAndSet(false, true)) return false
        cancellationReason = reason
        listeners.forEach { listener -> runCatching { listener(reason) } }
        listeners.clear()
        return true
    }
}

fun interface PeriodicRunner {
    fun dispatch(trigger: OccurrenceTrigger, control: RunControl): DispatchReceipt
}

interface SchedulerBackend {
    fun schedule(spec: ScheduleSpec): ScheduleReceipt
    fun cancel(scheduleId: String): Boolean
}

data class ScheduleReceipt(
    val scheduleId: String,
    val revision: Long,
    val accepted: Boolean,
    val nextRunAtEpochMillis: Long?,
    val summary: String
) : Serializable

sealed interface LeaseResult {
    data class Acquired(val expiresAtEpochMillis: Long) : LeaseResult
    data class Busy(val expiresAtEpochMillis: Long) : LeaseResult
    data object DuplicateCompleted : LeaseResult
}

interface JobLeaseStore {
    fun tryAcquire(
        jobId: String,
        occurrenceId: String,
        expiresAtEpochMillis: Long
    ): LeaseResult

    fun release(jobId: String, occurrenceId: String, completed: Boolean = false)
}

class InMemoryJobLeaseStore(
    private val clock: AgentClock = SystemAgentClock
) : JobLeaseStore {
    private val leases = mutableMapOf<String, Lease>()
    private val completed = mutableSetOf<String>()

    @Synchronized
    override fun tryAcquire(
        jobId: String,
        occurrenceId: String,
        expiresAtEpochMillis: Long
    ): LeaseResult {
        require(jobId.isNotBlank() && occurrenceId.isNotBlank())
        require(expiresAtEpochMillis > clock.nowEpochMillis())
        val key = "$jobId:$occurrenceId"
        if (key in completed) return LeaseResult.DuplicateCompleted
        val current = leases[key]
        if (current != null && current.expiresAtEpochMillis > clock.nowEpochMillis()) {
            return LeaseResult.Busy(current.expiresAtEpochMillis)
        }
        leases[key] = Lease(expiresAtEpochMillis)
        return LeaseResult.Acquired(expiresAtEpochMillis)
    }

    @Synchronized
    override fun release(jobId: String, occurrenceId: String, completed: Boolean) {
        val key = "$jobId:$occurrenceId"
        leases.remove(key)
        if (completed) this.completed += key
    }

    private data class Lease(val expiresAtEpochMillis: Long)
}

object ScheduleCalculator {
    fun nextRunAt(
        spec: ScheduleSpec,
        afterEpochMillis: Long
    ): Long? {
        if (!spec.enabled) return null
        val lower = maxOf(afterEpochMillis + 1, spec.validFromEpochMillis)
        if (spec.validUntilEpochMillis != null && lower > spec.validUntilEpochMillis) return null
        val zone = ZoneId.of(spec.timezone)
        val next = when (spec.cadence.type) {
            ScheduleCadenceType.ONE_TIME ->
                spec.cadence.oneTimeAtEpochMillis?.takeIf { value -> value >= lower }
            ScheduleCadenceType.INTERVAL -> nextInterval(
                spec.validFromEpochMillis,
                requireNotNull(spec.cadence.intervalMillis),
                lower
            )
            ScheduleCadenceType.DAILY -> nextDaily(
                lower,
                zone,
                LocalTime.parse(spec.cadence.localTime)
            )
            ScheduleCadenceType.WEEKLY -> nextWeekly(
                lower,
                zone,
                LocalTime.parse(spec.cadence.localTime),
                spec.cadence.weekdays
            )
            ScheduleCadenceType.WINDOW -> nextWindow(
                spec,
                lower,
                zone
            )
        } ?: return null
        val jittered = applyDeterministicJitter(spec, next)
        return jittered.takeIf { value ->
            spec.validUntilEpochMillis == null || value <= spec.validUntilEpochMillis
        }
    }

    fun occurrenceId(spec: ScheduleSpec, plannedAtEpochMillis: Long): String {
        val value = "${
            spec.id
        }:${spec.revision}:$plannedAtEpochMillis"
        val hash = value.fold(1125899906842597L) { acc, char -> acc * 31 + char.code }
        return "${spec.id}-r${spec.revision}-${hash.toString(16)}"
    }

    private fun nextInterval(anchor: Long, interval: Long, lower: Long): Long {
        if (lower <= anchor) return anchor
        val elapsed = lower - anchor
        val steps = (elapsed + interval - 1) / interval
        return anchor + steps * interval
    }

    private fun nextDaily(lower: Long, zone: ZoneId, time: LocalTime): Long {
        val after = Instant.ofEpochMilli(lower).atZone(zone)
        var candidate = ZonedDateTime.of(after.toLocalDate(), time, zone)
        if (candidate.toInstant().toEpochMilli() < lower) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    private fun nextWeekly(
        lower: Long,
        zone: ZoneId,
        time: LocalTime,
        weekdays: Set<DayOfWeek>
    ): Long {
        val after = Instant.ofEpochMilli(lower).atZone(zone)
        for (offset in 0..7) {
            val date = after.toLocalDate().plusDays(offset.toLong())
            if (date.dayOfWeek !in weekdays) continue
            val value = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (value >= lower) return value
        }
        error("Weekly cadence has no reachable weekday.")
    }

    private fun nextWindow(spec: ScheduleSpec, lower: Long, zone: ZoneId): Long {
        val cadence = spec.cadence
        val interval = requireNotNull(cadence.intervalMillis)
        val startTime = LocalTime.parse(cadence.windowStartLocalTime)
        val endTime = LocalTime.parse(cadence.windowEndLocalTime)
        val after = Instant.ofEpochMilli(lower).atZone(zone)
        for (offset in 0..366) {
            val date = after.toLocalDate().plusDays(offset.toLong())
            val start = ZonedDateTime.of(date, startTime, zone).toInstant().toEpochMilli()
            val end = ZonedDateTime.of(date, endTime, zone).toInstant().toEpochMilli()
            val candidate = nextInterval(start, interval, lower)
            if (candidate <= end) return candidate
        }
        error("Window cadence could not find a run within one year.")
    }

    private fun applyDeterministicJitter(spec: ScheduleSpec, planned: Long): Long {
        if (spec.maxJitterMillis == 0L) return planned
        val seed = ("${spec.id}:${spec.revision}:$planned").hashCode().toLong().absoluteValue
        return planned + seed % (spec.maxJitterMillis + 1)
    }
}

interface ScheduleRepository {
    fun get(id: String): ScheduleSpec?
    fun list(): List<ScheduleSpec>
    fun put(spec: ScheduleSpec, expectedRevision: Long?)
    fun remove(id: String, expectedRevision: Long): Boolean
}

interface ScheduleDataMaintenance {
    fun exportSchedules(): List<ScheduleSpec>

    /**
     * Atomically removes the exact revision set supplied by the caller.
     * Returns false if the repository changed after approval.
     */
    fun removeAll(expectedRevisions: Map<String, Long>): Boolean
}

data class ScheduleDataDeletionResult(
    val applied: Boolean,
    val deletedSchedules: Int,
    val reason: String
)

class InMemoryScheduleRepository : ScheduleRepository, ScheduleDataMaintenance {
    private val schedules = linkedMapOf<String, ScheduleSpec>()

    @Synchronized
    override fun get(id: String): ScheduleSpec? = schedules[id]

    @Synchronized
    override fun list(): List<ScheduleSpec> = schedules.values.sortedBy(ScheduleSpec::id)

    @Synchronized
    override fun put(spec: ScheduleSpec, expectedRevision: Long?) {
        val current = schedules[spec.id]
        require((current?.revision ?: 0L) == (expectedRevision ?: 0L)) {
            "Schedule '${spec.id}' revision conflict."
        }
        require(spec.revision == (current?.revision ?: 0L) + 1L) {
            "Schedule revision must increment once."
        }
        schedules[spec.id] = spec
    }

    @Synchronized
    override fun remove(id: String, expectedRevision: Long): Boolean {
        val current = schedules[id] ?: return false
        require(current.revision == expectedRevision) { "Schedule '$id' revision conflict." }
        schedules.remove(id)
        return true
    }

    @Synchronized
    override fun exportSchedules(): List<ScheduleSpec> = list()

    @Synchronized
    override fun removeAll(expectedRevisions: Map<String, Long>): Boolean {
        val current = schedules.mapValues { (_, spec) -> spec.revision }
        if (current != expectedRevisions) return false
        schedules.clear()
        return true
    }
}

class GovernedScheduleService(
    private val repository: ScheduleRepository,
    private val backend: SchedulerBackend,
    private val approvals: AgentApprovalCoordinator
) {
    fun apply(
        draft: ScheduleSpec,
        runId: String,
        sessionId: String
    ): ScheduleReceipt {
        val current = repository.get(draft.id)
        require(draft.revision == (current?.revision ?: 0L) + 1L)
        val arguments = scheduleArguments(draft)
        val hash = AgentEffectHasher.hash("schedule_apply", arguments)
        val intent = AgentEffectIntent(
            runId,
            sessionId,
            "schedule:${draft.id}:r${draft.revision}",
            "schedule_apply",
            SCHEDULE_CAPABILITY,
            "schedule:${draft.id}",
            hash,
            "Apply schedule '${draft.id}' revision ${draft.revision}."
        )
        val token = (approvals.authorize(intent) as? AgentEffectAuthorization.Allowed)?.token
        if (token == null || !approvals.consume(token, intent)) {
            return ScheduleReceipt(
                draft.id,
                draft.revision,
                false,
                null,
                "Schedule approval was not granted."
            )
        }
        repository.put(draft, current?.revision)
        return if (draft.enabled) {
            backend.schedule(draft)
        } else {
            backend.cancel(draft.id)
            ScheduleReceipt(draft.id, draft.revision, true, null, "Schedule disabled.")
        }
    }

    fun delete(
        scheduleId: String,
        expectedRevision: Long,
        runId: String,
        sessionId: String
    ): Boolean {
        val current = repository.get(scheduleId) ?: return false
        require(current.revision == expectedRevision)
        val hash = AgentEffectHasher.hash(
            "schedule_delete",
            mapOf("id" to scheduleId, "revision" to expectedRevision.toString())
        )
        val intent = AgentEffectIntent(
            runId,
            sessionId,
            "schedule-delete:$scheduleId:r$expectedRevision",
            "schedule_delete",
            SCHEDULE_CAPABILITY,
            "schedule:$scheduleId",
            hash,
            "Delete schedule '$scheduleId'."
        )
        val token = (approvals.authorize(intent) as? AgentEffectAuthorization.Allowed)?.token
            ?: return false
        if (!approvals.consume(token, intent)) return false
        val removed = runCatching {
            repository.remove(scheduleId, expectedRevision)
        }.getOrDefault(false)
        if (!removed) return false
        backend.cancel(scheduleId)
        return true
    }

    fun deleteAll(
        runId: String,
        sessionId: String
    ): ScheduleDataDeletionResult {
        val maintenance = repository as? ScheduleDataMaintenance
            ?: return ScheduleDataDeletionResult(
                false,
                0,
                "Schedule repository does not support atomic domain deletion."
            )
        val current = repository.list()
        if (current.isEmpty()) {
            return ScheduleDataDeletionResult(true, 0, "Schedule data is already empty.")
        }
        val expected = current.associate { spec -> spec.id to spec.revision }
        val hash = AgentEffectHasher.hash(
            "schedule_delete_all",
            expected.toSortedMap().mapValues { (_, revision) -> revision.toString() }
        )
        val intent = AgentEffectIntent(
            runId = runId,
            sessionId = sessionId,
            toolCallId = "schedule-delete-all:$hash",
            toolName = "schedule_delete_all",
            capability = SCHEDULE_CAPABILITY,
            targetRef = "schedule:*",
            argumentHash = hash,
            summary = "Delete all ${current.size} schedules and cancel their future work."
        )
        val token = (approvals.authorize(intent) as? AgentEffectAuthorization.Allowed)?.token
        if (token == null) {
            return ScheduleDataDeletionResult(
                false,
                0,
                "Schedule data deletion approval was not granted."
            )
        }
        if (!approvals.consume(token, intent)) {
            return ScheduleDataDeletionResult(
                false,
                0,
                "Schedule approval token expired, changed, or was already consumed."
            )
        }
        if (!maintenance.removeAll(expected)) {
            return ScheduleDataDeletionResult(
                false,
                0,
                "Schedule data changed while deletion approval was pending."
            )
        }
        current.forEach { spec -> backend.cancel(spec.id) }
        return ScheduleDataDeletionResult(true, current.size, "Schedule data deleted.")
    }

    private fun scheduleArguments(spec: ScheduleSpec) = mapOf(
        "id" to spec.id,
        "target" to spec.targetType.name,
        "cadence" to spec.cadence.toString(),
        "timezone" to spec.timezone,
        "revision" to spec.revision.toString(),
        "enabled" to spec.enabled.toString(),
        "reason" to spec.reason
    )

    companion object {
        val SCHEDULE_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DURABLE_WRITE,
            risk = AgentToolRisk.MEDIUM,
            dataScopes = setOf("schedules"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY,
            targetArgumentNames = setOf("id")
        )
    }
}
