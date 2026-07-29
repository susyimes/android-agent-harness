// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context
import android.content.ComponentName
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.InMemoryAgentApprovalJournal
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
import dev.androidagent.harness.state.AgentStateCollection
import dev.androidagent.harness.state.AgentVaultDocumentContextSource
import dev.androidagent.harness.state.RemoteAgentBriefContextSource
import dev.androidagent.harness.state.RemoteAgentBriefOptions
import dev.androidagent.harness.feedback.FileOutcomeJournal
import dev.androidagent.harness.feedback.FileSignalJournal
import dev.androidagent.harness.feedback.OutcomeJournal
import dev.androidagent.harness.feedback.SignalJournal
import dev.androidagent.harness.scheduling.DispatchReceipt
import dev.androidagent.harness.scheduling.LongTaskPeriodicRunner
import dev.androidagent.harness.scheduling.LongTaskCoordinator
import dev.androidagent.harness.scheduling.LongTaskStatus
import dev.androidagent.harness.scheduling.OccurrenceAuthorizationSnapshot
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

    @Volatile
    private var signalJournal: FileSignalJournal? = null

    @Volatile
    private var outcomeJournal: FileOutcomeJournal? = null

    private val voiceSessions = InMemoryVoiceSessionRepository(persistenceEnabled = false)
    private val activeRuns = ConcurrentHashMap<String, AgentRunHandle>()
    private val traces = CopyOnWriteArrayList<AgentEvent>()
    private val occurrenceReceipts = CopyOnWriteArrayList<DispatchReceipt>()

    @Volatile
    private var approvalMode = SampleApprovalMode.NONE

    private val approvalBridge = AndroidApprovalSurfaceBridge()
    private val approvalJournal = InMemoryAgentApprovalJournal()
    private val approvals = AgentApprovalCoordinator(
        gate = approvalBridge,
        policy = SampleApprovalPolicy.policy { approvalMode },
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

    fun remoteAgentBriefSource(
        context: Context,
        providerFactory: AgentProviderFactory
    ): RemoteAgentBriefContextSource {
        return RemoteAgentBriefContextSource(
            vault = state(context),
            providerFactory = providerFactory,
            options = RemoteAgentBriefOptions(timeoutMillis = REMOTE_AGENT_BRIEF_TIMEOUT_MILLIS)
        )
    }

    fun stateDocumentContextSource(context: Context): AgentVaultDocumentContextSource {
        return AgentVaultDocumentContextSource(
            vault = state(context),
            allowedCollections = AgentVaultDocumentContextSource.DEFAULT_COLLECTIONS -
                AgentStateCollection.BRIEFS
        )
    }

    fun governance(context: Context): AgentAssetGovernance {
        return governance ?: synchronized(this) {
            governance ?: AgentAssetGovernance(
                vault = state(context),
                approvals = approvals
            ).also { created -> governance = created }
        }
    }

    private const val REMOTE_AGENT_BRIEF_TIMEOUT_MILLIS = 12_000L

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
                runner = LongTaskPeriodicRunner { trigger, control, budget ->
                    SamplePeriodicRunner(context.applicationContext)
                        .dispatchAgentBurst(trigger, control, budget)
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

    fun disableScheduleForStop(
        context: Context,
        scheduleId: String
    ): Boolean {
        require(scheduleId.isNotBlank())
        val repository = schedules(context)
        repeat(MAX_SCHEDULE_STOP_CAS_ATTEMPTS) {
            val current = repository.get(scheduleId) ?: return false
            if (!current.enabled) return true
            val disabled = current.copy(
                revision = current.revision + 1L,
                enabled = false
            )
            if (
                runCatching {
                    repository.put(disabled, expectedRevision = current.revision)
                }.isSuccess
            ) {
                return true
            }
        }
        return false
    }

    fun signals(context: Context): SignalJournal {
        return signalJournal ?: synchronized(this) {
            signalJournal ?: FileSignalJournal(
                File(context.applicationContext.filesDir, "agent-feedback")
            ).also { created -> signalJournal = created }
        }
    }

    fun outcomes(context: Context): OutcomeJournal {
        return outcomeJournal ?: synchronized(this) {
            outcomeJournal ?: FileOutcomeJournal(
                File(context.applicationContext.filesDir, "agent-feedback")
            ).also { created -> outcomeJournal = created }
        }
    }

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

    fun initializeApprovalMode(context: Context) {
        approvalMode = SamplePreferences(context).approvalMode()
    }

    fun approvalMode(): SampleApprovalMode = approvalMode

    fun setApprovalMode(context: Context, mode: SampleApprovalMode) {
        SamplePreferences(context).setApprovalMode(mode)
        approvalMode = mode
        approvalBridge.cancelAll()
    }

    fun approvalRecords() = approvalJournal.snapshot()

    fun clearFeedback(context: Context): Int {
        val signals = signals(context) as FileSignalJournal
        val outcomes = outcomes(context) as FileOutcomeJournal
        return signals.clear() + outcomes.clear()
    }

    fun clearOperationalJournals(): Int {
        val count = traces.size + occurrenceReceipts.size + approvalJournal.snapshot().size
        traces.clear()
        occurrenceReceipts.clear()
        approvalJournal.clear()
        return count
    }

    private const val MAX_TRACE_EVENTS = 500
    private const val MAX_OCCURRENCE_RECEIPTS = 200
    private const val MAX_SCHEDULE_STOP_CAS_ATTEMPTS = 3
}
