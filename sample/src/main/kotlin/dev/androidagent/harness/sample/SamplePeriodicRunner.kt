// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context
import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentHarnessTraceEvent
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.context.NamedContextSource
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextTrust
import dev.androidagent.harness.data.android.AndroidSystemContextSource
import dev.androidagent.harness.data.android.TodoContextSource
import dev.androidagent.harness.data.android.TodoState
import dev.androidagent.harness.data.android.UsageStatsContextSource
import dev.androidagent.harness.feedback.ActivationDisposition
import dev.androidagent.harness.feedback.DreamEngine
import dev.androidagent.harness.feedback.HeartbeatEngine
import dev.androidagent.harness.feedback.HeartbeatInput
import dev.androidagent.harness.feedback.InitiativeLevel
import dev.androidagent.harness.feedback.OutcomeStatus
import dev.androidagent.harness.feedback.ProactiveArbiter
import dev.androidagent.harness.feedback.ProactivePolicy
import dev.androidagent.harness.feedback.QuietHours
import dev.androidagent.harness.feedback.RunOutcomeRecord
import dev.androidagent.harness.approval.AgentEffectHasher
import dev.androidagent.harness.permission.android.PermissionContextSource
import dev.androidagent.harness.provider.openai.CodexCredential
import dev.androidagent.harness.provider.openai.CodexCredentialProvider
import dev.androidagent.harness.provider.openai.CodexResponsesConfig
import dev.androidagent.harness.provider.openai.OpenAiCompatibleConfig
import dev.androidagent.harness.provider.openai.OpenAiProviderFactories
import dev.androidagent.harness.scheduling.DispatchReceipt
import dev.androidagent.harness.scheduling.DispatchStatus
import dev.androidagent.harness.scheduling.LongTaskBurstResult
import dev.androidagent.harness.scheduling.LongTaskSpec
import dev.androidagent.harness.scheduling.OccurrenceAuthorizationSnapshot
import dev.androidagent.harness.scheduling.OccurrenceTrigger
import dev.androidagent.harness.scheduling.PeriodicRunner
import dev.androidagent.harness.scheduling.RunControl
import dev.androidagent.harness.sdk.AgentRunBudget
import dev.androidagent.harness.sdk.AgentRunOutcome
import dev.androidagent.harness.sdk.AgentRunPolicy
import dev.androidagent.harness.sdk.AgentRunRequest
import dev.androidagent.harness.sdk.AgentRunTrigger
import dev.androidagent.harness.sdk.AgentSdk
import dev.androidagent.harness.state.AgentApprovedStateContextSource
import dev.androidagent.harness.state.AgentCandidateSource
import dev.androidagent.harness.state.AgentCandidateStatus
import dev.androidagent.harness.state.AgentVaultDocumentContextSource
import dev.androidagent.harness.state.MemoryCandidate
import dev.androidagent.harness.state.MemoryCandidateType
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** WorkManager dispatch adapter; it reuses AgentSdk instead of owning a second loop. */
class SamplePeriodicRunner(
    context: Context
) : PeriodicRunner {
    private val appContext = context.applicationContext

    override fun dispatch(
        trigger: OccurrenceTrigger,
        control: RunControl
    ): DispatchReceipt {
        return if (
            trigger.targetType ==
            dev.androidagent.harness.scheduling.ScheduleTargetType.LONG_TASK
        ) {
            dispatchLongTask(trigger, control)
        } else {
            dispatchAgentRun(trigger, control).receipt
        }
    }

    private fun dispatchLongTask(
        trigger: OccurrenceTrigger,
        control: RunControl
    ): DispatchReceipt {
        val coordinator = SampleRuntime.longTasks(appContext)
        val scopeHash = longTaskScopeHash(
            trigger.scheduleId,
            trigger.scheduleRevision,
            trigger.authorization
        )
        val schedule = SampleRuntime.schedules(appContext).get(trigger.scheduleId)
        val spec = LongTaskSpec(
            id = longTaskJobId(trigger.scheduleId, trigger.scheduleRevision),
            sessionId = "automation-${trigger.scheduleId}",
            goal = trigger.reason,
            authorizationScopeHash = scopeHash,
            deadlineEpochMillis = schedule?.validUntilEpochMillis
                ?: ((schedule?.validFromEpochMillis
                    ?: maxOf(
                        trigger.plannedAtEpochMillis,
                        trigger.actualAtEpochMillis
                    )) + LONG_TASK_DEFAULT_LIFETIME_MILLIS),
            resumable = true,
            maxBursts = 8,
            maxRepeatedFailures = 3,
            burstBudget = longTaskBurstBudget()
        )
        control.onCancel { reason -> coordinator.stop(spec.id, reason) }
        val checkpoint = coordinator.dispatchBurst(
            spec = spec,
            authorization = trigger.authorization,
            reason = trigger.reason
        )
        return checkpoint.lastReceipt ?: DispatchReceipt(
            occurrenceId = trigger.occurrenceId,
            status = when (checkpoint.status) {
                dev.androidagent.harness.scheduling.LongTaskStatus.COMPLETED ->
                    DispatchStatus.COMPLETED
                dev.androidagent.harness.scheduling.LongTaskStatus.CANCELLED ->
                    DispatchStatus.CANCELLED
                dev.androidagent.harness.scheduling.LongTaskStatus.EXPIRED ->
                    DispatchStatus.EXPIRED
                dev.androidagent.harness.scheduling.LongTaskStatus.FAILED ->
                    DispatchStatus.FAILED
                else -> DispatchStatus.ACCEPTED
            },
            summary = "LongTask ${checkpoint.status.name.lowercase()} at burst " +
                "${checkpoint.burst}.",
            retryable = checkpoint.status ==
                dev.androidagent.harness.scheduling.LongTaskStatus.READY,
            checkpointRef = checkpoint.nextAction
        )
    }

    internal fun dispatchAgentBurst(
        trigger: OccurrenceTrigger,
        control: RunControl,
        budget: AgentRunBudget
    ): LongTaskBurstResult {
        val execution = dispatchAgentRun(trigger, control, budget)
        return LongTaskBurstResult(
            receipt = execution.receipt,
            evidenceRefs = execution.evidenceRefs,
            effectRefs = execution.effectRefs
        )
    }

    private fun dispatchAgentRun(
        trigger: OccurrenceTrigger,
        control: RunControl,
        budgetOverride: AgentRunBudget? = null
    ): AgentRunExecution {
        if (
            trigger.targetType ==
            dev.androidagent.harness.scheduling.ScheduleTargetType.PROACTIVE &&
            SamplePreferences(appContext).initiativeLevel() == InitiativeLevel.OFF.name
        ) {
            return AgentRunExecution(
                receipt(
                    trigger,
                    DispatchStatus.SKIPPED_CONSTRAINT,
                    "Proactive initiative is off."
                )
            )
        }
        val profile = ProviderSettingsRepository(appContext).profile()
        val providerFactory = providerFactory(profile) ?: return AgentRunExecution(
            receipt(
                trigger,
                DispatchStatus.SKIPPED_AUTHORIZATION,
                "Provider credential is unavailable."
            )
        )
        val preparation = prepareAutomation(trigger)
        if (preparation.skipReason != null) {
            return AgentRunExecution(
                receipt(
                    trigger,
                    DispatchStatus.SKIPPED_CONSTRAINT,
                    preparation.skipReason
                )
            )
        }
        val sdk = AgentSdk(SampleRuntime.sessions(appContext))
        val runTrigger = trigger.targetType.toRunTrigger()
        val runBudget = budgetOverride ?: defaultAutomationBudget()
        val request = AgentRunRequest(
            sessionId = "automation-${trigger.scheduleId}",
            userInput = preparation.prompt,
            providerFactory = providerFactory,
            harnessConfig = AgentHarnessConfig(
                maxProviderSteps = runBudget.maxProviderSteps,
                maxToolCallsPerStep = runBudget.maxToolCalls.coerceIn(1, 32),
                maxToolCallsTotal = runBudget.maxToolCalls,
                maxRepeatedFailures = runBudget.maxRepeatedFailures,
                maxInputTokens = runBudget.maxInputTokens,
                maxOutputTokens = runBudget.maxOutputTokens
            ),
            contextSources = listOf(
                NamedContextSource(
                    "approved-agent-state",
                    AgentApprovedStateContextSource(SampleRuntime.state(appContext))
                ),
                NamedContextSource(
                    "agent-state-documents",
                    AgentVaultDocumentContextSource(SampleRuntime.state(appContext))
                ),
                NamedContextSource(
                    "android-permissions",
                    PermissionContextSource(SampleRuntime.permissions(appContext))
                ),
                NamedContextSource("local-todo", TodoContextSource(SampleRuntime.todo(appContext))),
                NamedContextSource(
                    "mirror-stats",
                    UsageStatsContextSource(SampleRuntime.usageStats(appContext))
                ),
                NamedContextSource(
                    "android-system",
                    AndroidSystemContextSource(appContext)
                )
            ),
            runPolicy = AgentRunPolicy(
                trigger = runTrigger,
                budget = runBudget,
                toolProfileId = "automation-read-only"
            ),
            traceSink = SampleRuntime.traceSink()
        )
        val handle = try {
            sdk.run(request)
        } catch (error: RuntimeException) {
            sdk.close()
            return AgentRunExecution(
                receipt(
                    trigger,
                    DispatchStatus.FAILED,
                    error.message ?: "Could not start scheduled Agent run.",
                    retryable = true
                )
            )
        }
        SampleRuntime.registerRun(handle)
        control.onCancel { reason -> handle.cancel(reason) }
        val outcome = try {
            handle.await(
                runBudget.maxWallClockMillis + RUN_AWAIT_GRACE_MILLIS,
                TimeUnit.MILLISECONDS
            )
        } catch (error: RuntimeException) {
            handle.cancel("Scheduled wait failed.")
            AgentRunOutcome.Failure(
                dev.androidagent.harness.sdk.AgentRunError(
                    dev.androidagent.harness.sdk.AgentRunErrorKind.INTERNAL,
                    error.message ?: "Scheduled wait failed.",
                    error
                )
            )
        } finally {
            SampleRuntime.unregisterRun(handle.runId)
            sdk.close()
        }
        val evidenceRefs = (outcome as? AgentRunOutcome.Success)
            ?.result
            ?.trace
            ?.evidenceRefs()
            .orEmpty()
        val effectRefs = (outcome as? AgentRunOutcome.Success)
            ?.result
            ?.trace
            ?.effectRefs()
            .orEmpty()
        val result = when (outcome) {
            is AgentRunOutcome.Success -> {
                if (
                    trigger.targetType ==
                    dev.androidagent.harness.scheduling.ScheduleTargetType.LONG_TASK
                ) {
                    longTaskReceipt(
                        trigger,
                        outcome.result.output
                    )
                } else {
                    receipt(
                        trigger,
                        DispatchStatus.COMPLETED,
                        outcome.result.output
                    )
                }
            }
            is AgentRunOutcome.Cancelled -> receipt(
                trigger,
                DispatchStatus.CANCELLED,
                outcome.reason
            )
            is AgentRunOutcome.Expired -> receipt(
                trigger,
                DispatchStatus.EXPIRED,
                outcome.reason
            )
            is AgentRunOutcome.Failure -> receipt(
                trigger,
                DispatchStatus.FAILED,
                outcome.error.message,
                retryable = outcome.error.kind.name in setOf("PROVIDER", "TIMEOUT")
            )
        }
        if (
            trigger.targetType ==
            dev.androidagent.harness.scheduling.ScheduleTargetType.DREAM &&
            outcome is AgentRunOutcome.Success
        ) {
            createDreamCandidate(trigger, handle.runId, outcome.result.output)
        }
        SampleRuntime.outcomes(appContext).append(
            RunOutcomeRecord(
                id = UUID.randomUUID().toString(),
                runId = handle.runId,
                trigger = runTrigger,
                goalSummary = trigger.reason,
                resultSummary = result.summary,
                status = when (result.status) {
                    DispatchStatus.COMPLETED -> OutcomeStatus.COMPLETED
                    DispatchStatus.CANCELLED -> OutcomeStatus.CANCELLED
                    DispatchStatus.EXPIRED -> OutcomeStatus.EXPIRED
                    DispatchStatus.FAILED -> OutcomeStatus.FAILED
                    else -> OutcomeStatus.SKIPPED
                },
                effectRefs = effectRefs,
                evidenceRefs = evidenceRefs,
                errorCategory = result.status.takeIf {
                    it != DispatchStatus.COMPLETED
                }?.name,
                createdAtEpochMillis = System.currentTimeMillis()
            )
        )
        return AgentRunExecution(result, evidenceRefs, effectRefs)
    }

    private fun providerFactory(profile: ProviderProfile): AgentProviderFactory? {
        return when (profile.kind) {
            ProviderKind.OFFLINE -> AgentProviderFactory.fixed(ScriptedChatProvider())
            ProviderKind.CODEX -> {
                if (CodexAuthRepository(appContext).getProfile() == null) return null
                val auth = CodexAuthService(CodexAuthRepository(appContext))
                OpenAiProviderFactories.codex(
                    CodexResponsesConfig(
                        model = profile.model,
                        baseUrl = profile.baseUrl,
                        historyCharBudget = 24_000,
                        originator = "openclaw",
                        clientVersion = "android-agent-harness"
                    ),
                    CodexCredentialProvider { refresh ->
                        auth.requireProfile(refresh).let { value ->
                            CodexCredential(value.accessToken, value.accountId)
                        }
                    }
                )
            }
            ProviderKind.KIMI_PLAN,
            ProviderKind.ARK_PLAN,
            ProviderKind.CUSTOM -> {
                val secret = profile.secret?.takeIf(String::isNotBlank) ?: return null
                OpenAiProviderFactories.compatible(
                    OpenAiCompatibleConfig(
                        baseUrl = profile.baseUrl,
                        model = profile.model,
                        keyValue = secret,
                        parallelToolCalls = false,
                        historyCharBudget = 24_000
                    )
                )
            }
        }
    }

    private fun prepareAutomation(trigger: OccurrenceTrigger): AutomationPreparation {
        return when (trigger.targetType) {
            dev.androidagent.harness.scheduling.ScheduleTargetType.HEARTBEAT -> {
                val todos = SampleRuntime.todo(appContext).list()
                val overdue = todos.count { item ->
                    item.state == TodoState.COMMITTED &&
                        item.dueDate?.let { value ->
                            runCatching { LocalDate.parse(value).isBefore(LocalDate.now()) }
                                .getOrDefault(false)
                        } == true
                }
                val pending = SampleRuntime.state(appContext).snapshot().candidates.count { value ->
                    value.status in setOf(
                        AgentCandidateStatus.PROPOSED,
                        AgentCandidateStatus.VALIDATED,
                        AgentCandidateStatus.EVALUATED,
                        AgentCandidateStatus.WAITING_APPROVAL
                    )
                }
                val permissionProblems = if (
                    SamplePreferences(appContext).usageStatsEnabled() &&
                    SampleRuntime.permissions(appContext).snapshot("usage-stats")
                        ?.status?.name != "GRANTED"
                ) {
                    1
                } else {
                    0
                }
                val repeatedFailures = SampleRuntime.outcomes(appContext).query()
                    .takeLast(20)
                    .count { value -> value.status == OutcomeStatus.FAILED }
                val report = HeartbeatEngine().inspect(
                    HeartbeatInput(
                        overdueTodoCount = overdue,
                        permissionProblemCount = permissionProblems,
                        pendingCandidateCount = pending,
                        repeatedFailureCount = repeatedFailures,
                        evidenceRefs = listOf(
                            "todo:summary",
                            "permission:snapshot",
                            "state:candidate-inbox",
                            "outcome:recent"
                        )
                    )
                )
                report.activationSignals.forEach(
                    SampleRuntime.signals(appContext)::append
                )
                AutomationPreparation(
                    prompt = buildString {
                        appendLine(
                            "执行一次只读 Heartbeat。只总结 typed findings；不要直接修改长期资产。"
                        )
                        report.findings.forEach { finding ->
                            appendLine(
                                "${finding.severity}: ${finding.title} — ${finding.summary}"
                            )
                        }
                    }
                )
            }
            dev.androidagent.harness.scheduling.ScheduleTargetType.DREAM -> {
                val report = DreamEngine().reflect(
                    outcomes = SampleRuntime.outcomes(appContext).query(),
                    pendingCandidates = SampleRuntime.state(appContext).snapshot().candidates
                )
                AutomationPreparation(
                    prompt = buildString {
                        appendLine(
                            "执行一次 Dream 反思。输出一个可审查建议或明确无建议；" +
                                "不得直接晋升记忆、人格或技能。"
                        )
                        appendLine("patterns=${report.patterns.joinToString()}")
                        appendLine("conflicts=${report.conflicts.joinToString()}")
                        report.proposals.forEach { proposal ->
                            appendLine("${proposal.type}: ${proposal.summary}")
                        }
                    }
                )
            }
            dev.androidagent.harness.scheduling.ScheduleTargetType.PROACTIVE -> {
                val preferences = SamplePreferences(appContext)
                val level = runCatching {
                    InitiativeLevel.valueOf(
                        preferences.initiativeLevel()
                    )
                }.getOrDefault(InitiativeLevel.OFF)
                val threshold = when (level) {
                    InitiativeLevel.OFF -> 101
                    InitiativeLevel.LOW -> 85
                    InitiativeLevel.BALANCED -> 70
                    InitiativeLevel.HIGH -> 55
                }
                val cap = if (level == InitiativeLevel.OFF) {
                    0
                } else {
                    preferences.proactiveDailyCap()
                }
                val decisions = ProactiveArbiter(
                    SampleRuntime.signals(appContext),
                    SampleRuntime.outcomes(appContext)
                ).evaluate(
                    ProactivePolicy(
                        initiative = level,
                        minimumScore = threshold.coerceAtMost(100),
                        dailyActivationCap = cap,
                        quietHours = QuietHours(
                            preferences.proactiveQuietStart(),
                            preferences.proactiveQuietEnd(),
                            ZoneId.systemDefault().id
                        )
                    )
                )
                val active = decisions.firstOrNull { decision ->
                    decision.disposition == ActivationDisposition.ACTIVATE
                }
                if (active == null) {
                    AutomationPreparation(
                        prompt = "",
                        skipReason = decisions.firstOrNull()?.reason
                            ?: "No evidence-backed proactive opportunity."
                    )
                } else {
                    AutomationPreparation(
                        prompt = "评估这项主动机会并给出一条克制、可忽略的建议；" +
                            "不要执行外部动作：${active.request?.reason ?: active.reason}"
                    )
                }
            }
            dev.androidagent.harness.scheduling.ScheduleTargetType.CRON ->
                AutomationPreparation("执行已批准的定时只读任务：${trigger.reason}")
            dev.androidagent.harness.scheduling.ScheduleTargetType.LONG_TASK ->
                AutomationPreparation(
                    "继续已批准的长任务检查点：${trigger.reason}\n" +
                        "如果本次已经完成目标，首行输出 $LONG_TASK_DONE；" +
                        "如果仍需后续 burst，首行输出 $LONG_TASK_CONTINUE，" +
                        "第二行给出不含秘密的 next action。"
                )
        }
    }

    private fun longTaskReceipt(
        trigger: OccurrenceTrigger,
        output: String
    ): DispatchReceipt {
        val lines = output.trim().lines()
        val marker = lines.firstOrNull()?.trim()
        val body = lines.drop(1).joinToString("\n").trim()
        return if (marker == LONG_TASK_CONTINUE) {
            receipt(
                trigger = trigger,
                status = DispatchStatus.ACCEPTED,
                summary = body.ifBlank { "LongTask requested another bounded burst." },
                checkpointRef = body.ifBlank { "Continue the approved LongTask." }
            )
        } else {
            receipt(
                trigger = trigger,
                status = DispatchStatus.COMPLETED,
                summary = if (marker == LONG_TASK_DONE) {
                    body.ifBlank { "LongTask completed." }
                } else {
                    output
                }
            )
        }
    }

    private fun createDreamCandidate(
        trigger: OccurrenceTrigger,
        runId: String,
        output: String
    ) {
        val content = output.trim().take(8_000)
        if (content.isBlank() || content.isNoDreamProposal()) return
        runCatching {
            SampleRuntime.governance(appContext).memorySink.propose(
                MemoryCandidate(
                    source = AgentCandidateSource(
                        runId = runId,
                        sessionId = "automation-${trigger.scheduleId}",
                        author = "agent",
                        trigger = "dream"
                    ),
                    type = MemoryCandidateType.REFLECTION,
                    proposedText = content,
                    trust = ContextTrust.MODEL_INFERRED,
                    confidence = 0.55,
                    evidenceRefs = listOf("occurrence:${trigger.occurrenceId}"),
                    privacy = ContextPrivacy.INTERNAL,
                    targetScope = "agent",
                    dedupeKey = "dream:${trigger.scheduleId}:${trigger.plannedAtEpochMillis}",
                    ttlMillis = 30L * 24 * 60 * 60 * 1_000
                )
            )
        }
    }

    private fun String.isNoDreamProposal(): Boolean {
        val normalized = lowercase()
            .replace(Regex("""[\s\p{Punct}，。；：！？]+"""), "")
        return normalized in setOf(
            "无建议",
            "没有建议",
            "暂无建议",
            "本次无建议",
            "nosuggestion",
            "noproposal",
            "none"
        )
    }

    private fun dev.androidagent.harness.scheduling.ScheduleTargetType.toRunTrigger() =
        when (this) {
            dev.androidagent.harness.scheduling.ScheduleTargetType.HEARTBEAT ->
                AgentRunTrigger.HEARTBEAT
            dev.androidagent.harness.scheduling.ScheduleTargetType.DREAM ->
                AgentRunTrigger.DREAM
            dev.androidagent.harness.scheduling.ScheduleTargetType.PROACTIVE ->
                AgentRunTrigger.PROACTIVE
            dev.androidagent.harness.scheduling.ScheduleTargetType.CRON ->
                AgentRunTrigger.CRON
            dev.androidagent.harness.scheduling.ScheduleTargetType.LONG_TASK ->
                AgentRunTrigger.LONG_TASK
        }

    private fun receipt(
        trigger: OccurrenceTrigger,
        status: DispatchStatus,
        summary: String,
        retryable: Boolean = false,
        checkpointRef: String? = null
    ) = DispatchReceipt(
        occurrenceId = trigger.occurrenceId,
        status = status,
        summary = summary.take(2_000).ifBlank { status.name },
        retryable = retryable,
        checkpointRef = checkpointRef?.take(2_000)
    )

    private fun List<AgentHarnessTraceEvent>.evidenceRefs(): List<String> {
        return buildList {
            filterIsInstance<AgentHarnessTraceEvent.ContextLoaded>()
                .forEach { event -> addAll(event.itemIds) }
            filterIsInstance<AgentHarnessTraceEvent.ToolExecuted>()
                .forEach { event ->
                    addAll(event.envelope?.evidence.orEmpty().map { value -> value.id })
                }
        }.distinct().take(MAX_RUN_REFS)
    }

    private fun List<AgentHarnessTraceEvent>.effectRefs(): List<String> {
        return filterIsInstance<AgentHarnessTraceEvent.ToolExecuted>()
            .mapNotNull { event -> event.envelope?.effect }
            .filter { effect -> effect.occurred }
            .map { effect -> effect.effectId }
            .distinct()
            .take(MAX_RUN_REFS)
    }

    private data class AutomationPreparation(
        val prompt: String,
        val skipReason: String? = null
    )

    private data class AgentRunExecution(
        val receipt: DispatchReceipt,
        val evidenceRefs: List<String> = emptyList(),
        val effectRefs: List<String> = emptyList()
    )

    companion object {
        private const val LONG_TASK_DEFAULT_LIFETIME_MILLIS = 7L * 24 * 60 * 60_000L
        private const val LONG_TASK_DONE = "[LONGTASK_DONE]"
        private const val LONG_TASK_CONTINUE = "[LONGTASK_CONTINUE]"
        private const val RUN_AWAIT_GRACE_MILLIS = 5_000L
        private const val MAX_RUN_REFS = 256

        private fun defaultAutomationBudget() = AgentRunBudget(
            maxProviderSteps = 8,
            maxToolCalls = 0,
            maxWallClockMillis = 2 * 60_000L,
            maxRepeatedFailures = 3
        )

        private fun longTaskBurstBudget() = defaultAutomationBudget()

        fun longTaskScopeHash(
            scheduleId: String,
            scheduleRevision: Long,
            authorization: OccurrenceAuthorizationSnapshot
        ): String = AgentEffectHasher.hash(
            "long_task_scope",
            mapOf(
                "scheduleId" to scheduleId,
                "scheduleRevision" to scheduleRevision.toString(),
                "capabilities" to authorization.grantedCapabilityIds
                    .sorted()
                    .joinToString(","),
                "credentialRevision" to authorization.credentialRevision.orEmpty(),
                "policyRevision" to authorization.policyRevision
            )
        )

        fun longTaskJobId(scheduleId: String, scheduleRevision: Long): String {
            require(scheduleId.isNotBlank())
            require(scheduleRevision > 0)
            return "$scheduleId-r$scheduleRevision"
        }
    }
}
