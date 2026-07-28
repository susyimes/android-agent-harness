// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context
import android.content.ComponentName
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalPolicy
import dev.androidagent.harness.approval.AgentApprovalRequirement
import dev.androidagent.harness.approval.InMemoryAgentApprovalJournal
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.sdk.AgentEvent
import dev.androidagent.harness.sdk.AgentRunHandle
import dev.androidagent.harness.sdk.TraceSink
import dev.androidagent.harness.data.android.AndroidFileAgentStateVault
import dev.androidagent.harness.data.android.AndroidUsageStatsRepository
import dev.androidagent.harness.data.android.FileTodoRepository
import dev.androidagent.harness.data.android.UsageStatsConfiguration
import dev.androidagent.harness.deviceloop.android.HarnessAccessibilityService
import dev.androidagent.harness.permission.android.AndroidApprovalSurfaceBridge
import dev.androidagent.harness.permission.android.PlatformAndroidPermissionRepository
import dev.androidagent.harness.permission.android.StandardAndroidPermissionSpecs
import dev.androidagent.harness.sdk.FileAgentSessionStore
import dev.androidagent.harness.sdk.house.FileAgentHouseRepository
import dev.androidagent.harness.state.AgentAssetGovernance
import dev.androidagent.harness.feedback.InMemoryOutcomeJournal
import dev.androidagent.harness.feedback.InMemorySignalJournal
import dev.androidagent.harness.feedback.OutcomeJournal
import dev.androidagent.harness.feedback.SignalJournal
import dev.androidagent.harness.scheduling.DispatchReceipt
import dev.androidagent.harness.scheduling.LongTaskCoordinator
import dev.androidagent.harness.scheduling.LongTaskStatus
import dev.androidagent.harness.scheduling.OccurrenceAuthorizationSnapshot
import dev.androidagent.harness.scheduling.PeriodicRunner
import dev.androidagent.harness.scheduling.ScheduleRepository
import dev.androidagent.harness.scheduling.ScheduleSpec
import dev.androidagent.harness.scheduling.android.AndroidRunCheckpointStore
import dev.androidagent.harness.scheduling.android.AndroidScheduleRepository
import dev.androidagent.harness.voice.android.InMemoryVoiceSessionRepository
import dev.androidagent.harness.voice.android.VoiceSessionRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local access to app-private durable adapters.
 *
 * Only the application context and files are retained; Activities are never
 * captured.
 */
object SampleRuntime {
    @Volatile
    private var sessions: FileAgentSessionStore? = null

    @Volatile
    private var house: FileAgentHouseRepository? = null

    @Volatile
    private var state: AndroidFileAgentStateVault? = null

    @Volatile
    private var governance: AgentAssetGovernance? = null

    @Volatile
    private var todo: FileTodoRepository? = null

    @Volatile
    private var permissions: PlatformAndroidPermissionRepository? = null

    @Volatile
    private var usageStats: AndroidUsageStatsRepository? = null

    @Volatile
    private var schedules: AndroidScheduleRepository? = null

    @Volatile
    private var checkpoints: AndroidRunCheckpointStore? = null

    @Volatile
    private var longTasks: LongTaskCoordinator? = null

    private val signalJournal = InMemorySignalJournal()
    private val outcomeJournal = InMemoryOutcomeJournal()
    private val voiceSessions = InMemoryVoiceSessionRepository(persistenceEnabled = false)
    private val activeRuns = ConcurrentHashMap<String, AgentRunHandle>()
    private val traces = CopyOnWriteArrayList<AgentEvent>()
    private val occurrenceReceipts = CopyOnWriteArrayList<DispatchReceipt>()

    private val approvalBridge = AndroidApprovalSurfaceBridge()
    private val approvalJournal = InMemoryAgentApprovalJournal()
    private val approvals = AgentApprovalCoordinator(
        gate = approvalBridge,
        policy = AgentApprovalPolicy { intent ->
            when (intent.capability.sideEffect) {
                AgentToolSideEffect.NONE,
                AgentToolSideEffect.LOCAL_READ,
                AgentToolSideEffect.LOCAL_DRAFT_WRITE ->
                    AgentApprovalRequirement.NOT_REQUIRED

                AgentToolSideEffect.DEVICE_ACTION ->
                    if (intent.capability.risk == AgentToolRisk.LOW) {
                        AgentApprovalRequirement.NOT_REQUIRED
                    } else {
                        AgentApprovalRequirement.REQUIRED
                    }

                AgentToolSideEffect.LOCAL_DURABLE_WRITE,
                AgentToolSideEffect.EXTERNAL_WRITE ->
                    AgentApprovalRequirement.REQUIRED
            }
        },
        journal = approvalJournal
    )

    fun sessions(context: Context): FileAgentSessionStore {
        return sessions ?: synchronized(this) {
            sessions ?: FileAgentSessionStore(
                File(context.applicationContext.filesDir, "agent-sessions")
            ).also { created -> sessions = created }
        }
    }

    fun house(context: Context): FileAgentHouseRepository {
        return house ?: synchronized(this) {
            house ?: FileAgentHouseRepository(
                File(context.applicationContext.filesDir, "agent-house")
            ).also { created -> house = created }
        }
    }

    fun state(context: Context): AndroidFileAgentStateVault {
        return state ?: synchronized(this) {
            state ?: AndroidFileAgentStateVault(context.applicationContext)
                .also { created -> state = created }
        }
    }

    fun governance(context: Context): AgentAssetGovernance {
        return governance ?: synchronized(this) {
            governance ?: AgentAssetGovernance(
                vault = state(context),
                approvals = approvals
            ).also { created -> governance = created }
        }
    }

    fun todo(context: Context): FileTodoRepository {
        return todo ?: synchronized(this) {
            todo ?: FileTodoRepository(
                File(context.applicationContext.filesDir, "agent-todo")
            ).also { created -> todo = created }
        }
    }

    fun permissions(context: Context): PlatformAndroidPermissionRepository {
        return permissions ?: synchronized(this) {
            permissions ?: PlatformAndroidPermissionRepository(
                context.applicationContext,
                listOf(
                    StandardAndroidPermissionSpecs.audioRecording(),
                    StandardAndroidPermissionSpecs.usageStats(),
                    StandardAndroidPermissionSpecs.overlay(),
                    StandardAndroidPermissionSpecs.exactAlarm(),
                    StandardAndroidPermissionSpecs.notifications(),
                    StandardAndroidPermissionSpecs.coarseLocation(),
                    StandardAndroidPermissionSpecs.calendarRead(),
                    StandardAndroidPermissionSpecs.accessibility(
                        ComponentName(
                            context.applicationContext,
                            HarnessAccessibilityService::class.java
                        )
                    )
                )
            ).also { created -> permissions = created }
        }
    }

    fun usageStats(context: Context): AndroidUsageStatsRepository {
        return usageStats ?: synchronized(this) {
            val appContext = context.applicationContext
            usageStats ?: AndroidUsageStatsRepository(
                appContext,
                permissions(appContext),
                configuration = {
                    UsageStatsConfiguration(
                        enabled = SamplePreferences(appContext).usageStatsEnabled()
                    )
                }
            ).also { created -> usageStats = created }
        }
    }

    fun schedules(context: Context): ScheduleRepository {
        return schedules ?: synchronized(this) {
            schedules ?: AndroidScheduleRepository(context.applicationContext)
                .also { created -> schedules = created }
        }
    }

    fun checkpoints(context: Context): AndroidRunCheckpointStore {
        return checkpoints ?: synchronized(this) {
            checkpoints ?: AndroidRunCheckpointStore(context.applicationContext)
                .also { created -> checkpoints = created }
        }
    }

    fun authorizationSnapshot(
        context: Context,
        @Suppress("UNUSED_PARAMETER") spec: ScheduleSpec
    ): OccurrenceAuthorizationSnapshot {
        val appContext = context.applicationContext
        val profile = ProviderSettingsRepository(appContext).profile()
        val credentialRevision = when (profile.kind.credentialMode) {
            ProviderCredentialMode.NONE -> "offline"
            ProviderCredentialMode.API_KEY ->
                profile.secret?.hashCode()?.toUInt()?.toString(16)
            ProviderCredentialMode.CODEX_LOGIN ->
                CodexAuthRepository(appContext).getProfile()
                    ?.accountId
                    ?.hashCode()
                    ?.toUInt()
                    ?.toString(16)
        }
        return OccurrenceAuthorizationSnapshot(
            grantedCapabilityIds = permissions(appContext)
                .snapshots()
                .filter { snapshot -> snapshot.status.name == "GRANTED" }
                .map { snapshot -> snapshot.capabilityId }
                .toSet(),
            credentialRevision = credentialRevision,
            policyRevision = "sample-policy-v1",
            capturedAtEpochMillis = System.currentTimeMillis()
        )
    }

    fun longTasks(context: Context): LongTaskCoordinator {
        return longTasks ?: synchronized(this) {
            longTasks ?: LongTaskCoordinator(
                runner = PeriodicRunner { trigger, control ->
                    SamplePeriodicRunner(context.applicationContext)
                        .dispatchAgentBurst(trigger, control)
                },
                checkpoints = checkpoints(context.applicationContext),
                traceSink = traceSink()
            ).also { created -> longTasks = created }
        }
    }

    fun stopLongTasksForSchedule(
        context: Context,
        scheduleId: String,
        reason: String
    ): Int {
        val coordinator = longTasks(context)
        return checkpoints(context).list()
            .filter { checkpoint ->
                checkpoint.status !in setOf(
                    LongTaskStatus.COMPLETED,
                    LongTaskStatus.FAILED,
                    LongTaskStatus.CANCELLED,
                    LongTaskStatus.EXPIRED
                ) &&
                    checkpoint.jobId.startsWith("$scheduleId-r")
            }
            .count { checkpoint -> coordinator.stop(checkpoint.jobId, reason) }
    }

    fun signals(): SignalJournal = signalJournal

    fun outcomes(): OutcomeJournal = outcomeJournal

    fun voiceSessions(): VoiceSessionRepository = voiceSessions

    fun registerRun(handle: AgentRunHandle) {
        activeRuns[handle.runId] = handle
    }

    fun unregisterRun(runId: String) {
        activeRuns.remove(runId)
    }

    fun activeRunSnapshot(): List<AgentRunHandle> = activeRuns.values
        .filterNot(AgentRunHandle::isDone)
        .sortedBy(AgentRunHandle::runId)

    fun stopAllRuns(reason: String = "用户从控制中心停止全部运行。"): Int {
        return activeRunSnapshot().count { handle -> handle.cancel(reason) }
    }

    fun traceSink(): TraceSink = TraceSink { event ->
        traces += event
        while (traces.size > MAX_TRACE_EVENTS) {
            traces.removeAt(0)
        }
    }

    fun traceSnapshot(): List<AgentEvent> = traces.toList()

    fun clearTraces(): Int {
        val count = traces.size
        traces.clear()
        return count
    }

    fun recordOccurrence(receipt: DispatchReceipt) {
        occurrenceReceipts += receipt
        while (occurrenceReceipts.size > MAX_OCCURRENCE_RECEIPTS) {
            occurrenceReceipts.removeAt(0)
        }
    }

    fun occurrenceSnapshot(): List<DispatchReceipt> = occurrenceReceipts.toList()

    fun approvalBridge(): AndroidApprovalSurfaceBridge = approvalBridge

    fun approvalCoordinator(): AgentApprovalCoordinator = approvals

    fun approvalRecords() = approvalJournal.snapshot()

    fun clearFeedback(): Int = signalJournal.clear() + outcomeJournal.clear()

    fun clearOperationalJournals(): Int {
        val count = traces.size + occurrenceReceipts.size + approvalJournal.snapshot().size
        traces.clear()
        occurrenceReceipts.clear()
        approvalJournal.clear()
        return count
    }

    private const val MAX_TRACE_EVENTS = 500
    private const val MAX_OCCURRENCE_RECEIPTS = 200
}
