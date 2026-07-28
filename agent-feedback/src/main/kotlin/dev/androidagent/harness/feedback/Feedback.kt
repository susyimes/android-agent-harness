// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.feedback

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.AgentIdGenerator
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.UuidAgentIdGenerator
import dev.androidagent.harness.sdk.AgentRunBudget
import dev.androidagent.harness.sdk.AgentRunTrigger
import dev.androidagent.harness.scheduling.ScheduleTargetType
import dev.androidagent.harness.state.AgentAssetCandidate
import dev.androidagent.harness.state.AgentCandidateStatus
import java.io.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class FeedbackSignalType {
    USER_ACCEPTED,
    USER_EDITED,
    USER_REJECTED,
    USER_CORRECTED,
    USER_DISMISSED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_EXPIRED,
    TYPED_SKIP,
    TODO_CHANGED,
    PERMISSION_CHANGED,
    SCHEDULE_CHANGED,
    CANDIDATE_BACKLOG,
    NETWORK_CHANGED,
    BATTERY_CHANGED,
    FOREGROUND_CHANGED,
    STATS_CHANGED,
    APPROVAL_DENIED,
    REPEATED_FAILURE,
    RISK_ESCALATED,
    ACTIVATION_EMITTED
}

enum class FeedbackSignalSource {
    USER,
    RUN,
    PRODUCT,
    ANDROID,
    SAFETY,
    HOST
}

data class FeedbackSignal(
    val id: String,
    val type: FeedbackSignalType,
    val source: FeedbackSignalSource,
    val summary: String,
    val importance: Int,
    val evidenceRefs: List<String>,
    val attributes: Map<String, String> = emptyMap(),
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null
) : Serializable {
    init {
        require(id.isNotBlank())
        require(summary.isNotBlank())
        require(importance in 0..100)
        require(evidenceRefs.none(String::isBlank))
        require(attributes.keys.none(String::isBlank))
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= createdAtEpochMillis)
    }
}

enum class OutcomeStatus {
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    SKIPPED
}

data class RunOutcomeRecord(
    val id: String,
    val runId: String,
    val trigger: AgentRunTrigger,
    val goalSummary: String,
    val resultSummary: String,
    val status: OutcomeStatus,
    val userFeedback: FeedbackSignalType? = null,
    val effectRefs: List<String> = emptyList(),
    val evidenceRefs: List<String> = emptyList(),
    val providerSteps: Int = 0,
    val toolCalls: Int = 0,
    val errorCategory: String? = null,
    val createdAtEpochMillis: Long
) : Serializable {
    init {
        require(id.isNotBlank() && runId.isNotBlank())
        require(goalSummary.isNotBlank() && resultSummary.isNotBlank())
        require(effectRefs.none(String::isBlank) && evidenceRefs.none(String::isBlank))
        require(providerSteps >= 0 && toolCalls >= 0)
        require(errorCategory == null || errorCategory.isNotBlank())
    }
}

interface SignalJournal {
    fun append(signal: FeedbackSignal)
    fun query(sinceEpochMillis: Long = 0L): List<FeedbackSignal>
}

interface OutcomeJournal {
    fun append(outcome: RunOutcomeRecord)
    fun query(sinceEpochMillis: Long = 0L): List<RunOutcomeRecord>
}

class InMemorySignalJournal : SignalJournal {
    private val values = linkedMapOf<String, FeedbackSignal>()

    @Synchronized
    override fun append(signal: FeedbackSignal) {
        val existing = values[signal.id]
        require(existing == null || existing == signal) { "Signal id already exists." }
        values[signal.id] = signal
    }

    @Synchronized
    override fun query(sinceEpochMillis: Long): List<FeedbackSignal> =
        values.values.filter { it.createdAtEpochMillis >= sinceEpochMillis }
            .sortedWith(
                compareBy<FeedbackSignal> { it.createdAtEpochMillis }.thenBy { it.id }
            )

    @Synchronized
    fun clear(): Int {
        val count = values.size
        values.clear()
        return count
    }
}

class InMemoryOutcomeJournal : OutcomeJournal {
    private val values = linkedMapOf<String, RunOutcomeRecord>()

    @Synchronized
    override fun append(outcome: RunOutcomeRecord) {
        val existing = values[outcome.id]
        require(existing == null || existing == outcome) { "Outcome id already exists." }
        values[outcome.id] = outcome
    }

    @Synchronized
    override fun query(sinceEpochMillis: Long): List<RunOutcomeRecord> =
        values.values.filter { it.createdAtEpochMillis >= sinceEpochMillis }
            .sortedWith(
                compareBy<RunOutcomeRecord> { it.createdAtEpochMillis }.thenBy { it.id }
            )

    @Synchronized
    fun clear(): Int {
        val count = values.size
        values.clear()
        return count
    }
}

data class ActivationRequest(
    val reason: String,
    val triggerType: AgentRunTrigger,
    val evidenceRefs: List<String>,
    val suggestedBudget: AgentRunBudget,
    val contextPolicyId: String,
    val toolProfileId: String,
    val expiresAtEpochMillis: Long
) : Serializable {
    init {
        require(reason.isNotBlank())
        require(evidenceRefs.none(String::isBlank))
        require(contextPolicyId.isNotBlank() && toolProfileId.isNotBlank())
    }
}

data class Opportunity(
    val id: String,
    val reason: String,
    val trigger: AgentRunTrigger,
    val evidenceRefs: List<String>,
    val importance: Int,
    val confidence: Double,
    val noisePenalty: Int,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    init {
        require(id.isNotBlank() && reason.isNotBlank())
        require(importance in 0..100)
        require(confidence in 0.0..1.0)
        require(noisePenalty in 0..100)
        require(expiresAtEpochMillis >= createdAtEpochMillis)
    }
}

fun interface OpportunityDetector {
    fun detect(signals: List<FeedbackSignal>, outcomes: List<RunOutcomeRecord>): List<Opportunity>
}

class RuleBasedOpportunityDetector : OpportunityDetector {
    override fun detect(
        signals: List<FeedbackSignal>,
        outcomes: List<RunOutcomeRecord>
    ): List<Opportunity> {
        val rejectionKeys = signals.filter { signal ->
            signal.type in setOf(
                FeedbackSignalType.USER_REJECTED,
                FeedbackSignalType.USER_DISMISSED
            )
        }.map { signal -> signal.attributes["topic"].orEmpty() }.filter(String::isNotBlank)
            .groupingBy(String::lowercase).eachCount()
        return signals.filter { signal ->
            signal.type in ACTIVATION_SIGNAL_TYPES &&
                (signal.expiresAtEpochMillis == null ||
                    signal.expiresAtEpochMillis >= signal.createdAtEpochMillis)
        }.map { signal ->
            val topic = signal.attributes["topic"].orEmpty().lowercase()
            Opportunity(
                id = "opportunity:${signal.id}",
                reason = signal.summary,
                trigger = when (signal.type) {
                    FeedbackSignalType.CANDIDATE_BACKLOG,
                    FeedbackSignalType.STATS_CHANGED -> AgentRunTrigger.DREAM
                    FeedbackSignalType.TODO_CHANGED,
                    FeedbackSignalType.PERMISSION_CHANGED -> AgentRunTrigger.HEARTBEAT
                    else -> AgentRunTrigger.PROACTIVE
                },
                evidenceRefs = signal.evidenceRefs,
                importance = signal.importance,
                confidence = signal.attributes["confidence"]?.toDoubleOrNull()
                    ?.coerceIn(0.0, 1.0) ?: 0.7,
                noisePenalty = (rejectionKeys[topic] ?: 0).times(20).coerceAtMost(80),
                createdAtEpochMillis = signal.createdAtEpochMillis,
                expiresAtEpochMillis = signal.expiresAtEpochMillis
                    ?: signal.createdAtEpochMillis + 24 * 60 * 60 * 1_000L
            )
        }
    }

    private companion object {
        val ACTIVATION_SIGNAL_TYPES = setOf(
            FeedbackSignalType.TODO_CHANGED,
            FeedbackSignalType.PERMISSION_CHANGED,
            FeedbackSignalType.CANDIDATE_BACKLOG,
            FeedbackSignalType.REPEATED_FAILURE,
            FeedbackSignalType.RISK_ESCALATED,
            FeedbackSignalType.STATS_CHANGED
        )
    }
}

enum class InitiativeLevel {
    OFF,
    LOW,
    BALANCED,
    HIGH
}

data class QuietHours(
    val start: String,
    val end: String,
    val timezone: String
) {
    init {
        require(runCatching { LocalTime.parse(start) }.isSuccess)
        require(runCatching { LocalTime.parse(end) }.isSuccess)
        require(runCatching { ZoneId.of(timezone) }.isSuccess)
    }

    fun contains(epochMillis: Long): Boolean {
        val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(timezone)).toLocalTime()
        val startTime = LocalTime.parse(start)
        val endTime = LocalTime.parse(end)
        return if (startTime <= endTime) {
            time >= startTime && time < endTime
        } else {
            time >= startTime || time < endTime
        }
    }
}

data class ProactivePolicy(
    val initiative: InitiativeLevel = InitiativeLevel.OFF,
    val minimumScore: Int = 70,
    val dailyActivationCap: Int = 3,
    val cooldownMillis: Long = 2 * 60 * 60 * 1_000L,
    val quietHours: QuietHours? = null,
    val allowedTriggers: Set<AgentRunTrigger> = setOf(
        AgentRunTrigger.HEARTBEAT,
        AgentRunTrigger.DREAM,
        AgentRunTrigger.PROACTIVE
    )
) {
    init {
        require(minimumScore in 0..100)
        require(dailyActivationCap in 0..100)
        require(cooldownMillis >= 0)
    }
}

enum class ActivationDisposition {
    ACTIVATE,
    DEFER,
    SUPPRESS
}

data class ActivationDecision(
    val opportunity: Opportunity,
    val score: Int,
    val disposition: ActivationDisposition,
    val reason: String,
    val request: ActivationRequest? = null
)

class ProactiveArbiter(
    private val signals: SignalJournal,
    private val outcomes: OutcomeJournal,
    private val detector: OpportunityDetector = RuleBasedOpportunityDetector(),
    private val clock: AgentClock = SystemAgentClock,
    private val idGenerator: AgentIdGenerator = UuidAgentIdGenerator()
) {
    fun evaluate(policy: ProactivePolicy): List<ActivationDecision> {
        val now = clock.nowEpochMillis()
        val dayStart = now - 24 * 60 * 60 * 1_000L
        val recentSignals = signals.query(dayStart)
        val recentOutcomes = outcomes.query(dayStart)
        val emitted = recentSignals.filter { signal ->
            signal.type == FeedbackSignalType.ACTIVATION_EMITTED
        }
        var activationCount = emitted.size
        var lastActivationAt = emitted.maxOfOrNull(FeedbackSignal::createdAtEpochMillis)
        return detector.detect(recentSignals, recentOutcomes)
            .filter { opportunity -> opportunity.expiresAtEpochMillis >= now }
            .sortedByDescending(::score)
            .map { opportunity ->
                val score = score(opportunity)
                val blocked = when {
                    policy.initiative == InitiativeLevel.OFF -> "Proactive initiative is off."
                    opportunity.trigger !in policy.allowedTriggers -> "Trigger is disabled by policy."
                    policy.quietHours?.contains(now) == true -> "Quiet hours are active."
                    activationCount >= policy.dailyActivationCap ->
                        "Daily activation cap reached."
                    lastActivationAt != null && now - lastActivationAt < policy.cooldownMillis ->
                        "Activation cooldown is active."
                    score < policy.minimumScore -> "Opportunity score is below threshold."
                    else -> null
                }
                if (blocked != null) {
                    ActivationDecision(
                        opportunity,
                        score,
                        if (blocked.contains("Quiet") || blocked.contains("cooldown")) {
                            ActivationDisposition.DEFER
                        } else {
                            ActivationDisposition.SUPPRESS
                        },
                        blocked
                    )
                } else {
                    val request = opportunity.toRequest(now)
                    signals.append(
                        FeedbackSignal(
                            id = idGenerator.nextId("activation"),
                            type = FeedbackSignalType.ACTIVATION_EMITTED,
                            source = FeedbackSignalSource.HOST,
                            summary = request.reason,
                            importance = opportunity.importance,
                            evidenceRefs = opportunity.evidenceRefs,
                            attributes = mapOf("opportunityId" to opportunity.id),
                            createdAtEpochMillis = now,
                            expiresAtEpochMillis = request.expiresAtEpochMillis
                        )
                    )
                    activationCount += 1
                    lastActivationAt = now
                    ActivationDecision(
                        opportunity,
                        score,
                        ActivationDisposition.ACTIVATE,
                        "Opportunity passed local activation policy.",
                        request
                    )
                }
            }
    }

    private fun score(opportunity: Opportunity): Int {
        val confidence = (opportunity.confidence * 25).toInt()
        return (opportunity.importance * 3 / 4 + confidence - opportunity.noisePenalty)
            .coerceIn(0, 100)
    }

    private fun Opportunity.toRequest(now: Long) = ActivationRequest(
        reason = reason,
        triggerType = trigger,
        evidenceRefs = evidenceRefs,
        suggestedBudget = when (trigger) {
            AgentRunTrigger.HEARTBEAT -> AgentRunBudget(8, 4, 60_000L, 2)
            AgentRunTrigger.DREAM -> AgentRunBudget(16, 8, 3 * 60_000L, 3)
            AgentRunTrigger.PROACTIVE -> AgentRunBudget(8, 4, 60_000L, 2)
            else -> AgentRunBudget(8, 4, 60_000L, 2)
        },
        contextPolicyId = "feedback-${trigger.name.lowercase()}",
        toolProfileId = when (trigger) {
            AgentRunTrigger.HEARTBEAT -> "heartbeat-readonly"
            AgentRunTrigger.DREAM -> "dream-candidate-only"
            else -> "proactive-readonly"
        },
        expiresAtEpochMillis = minOf(expiresAtEpochMillis, now + 60 * 60 * 1_000L)
    )
}

enum class FindingSeverity {
    QUIET,
    INFO,
    ACTIONABLE,
    URGENT
}

data class FeedbackFinding(
    val id: String,
    val title: String,
    val summary: String,
    val severity: FindingSeverity,
    val evidenceRefs: List<String>,
    val notificationCandidate: Boolean
)

data class HeartbeatInput(
    val overdueTodoCount: Int,
    val permissionProblemCount: Int,
    val pendingCandidateCount: Int,
    val repeatedFailureCount: Int,
    val evidenceRefs: List<String>
)

data class HeartbeatReport(
    val findings: List<FeedbackFinding>,
    val activationSignals: List<FeedbackSignal>
)

class HeartbeatEngine(
    private val clock: AgentClock = SystemAgentClock,
    private val idGenerator: AgentIdGenerator = UuidAgentIdGenerator()
) {
    fun inspect(input: HeartbeatInput): HeartbeatReport {
        require(
            listOf(
                input.overdueTodoCount,
                input.permissionProblemCount,
                input.pendingCandidateCount,
                input.repeatedFailureCount
            ).none { it < 0 }
        )
        val findings = buildList {
            if (input.overdueTodoCount > 0) add(
                finding(
                    "Overdue Todo",
                    "${input.overdueTodoCount} committed Todo items are overdue.",
                    FindingSeverity.ACTIONABLE,
                    input.evidenceRefs,
                    notification = true
                )
            )
            if (input.permissionProblemCount > 0) add(
                finding(
                    "Capability changed",
                    "${input.permissionProblemCount} enabled capabilities need attention.",
                    FindingSeverity.INFO,
                    input.evidenceRefs,
                    notification = false
                )
            )
            if (input.pendingCandidateCount > 0) add(
                finding(
                    "Candidate review",
                    "${input.pendingCandidateCount} memory, skill, or persona candidates " +
                        "are waiting for review.",
                    FindingSeverity.ACTIONABLE,
                    input.evidenceRefs,
                    notification = false
                )
            )
            if (input.repeatedFailureCount > 0) add(
                finding(
                    "Repeated failure",
                    "${input.repeatedFailureCount} repeated failures need diagnosis.",
                    if (input.repeatedFailureCount >= 3) {
                        FindingSeverity.URGENT
                    } else {
                        FindingSeverity.ACTIONABLE
                    },
                    input.evidenceRefs,
                    notification = input.repeatedFailureCount >= 3
                )
            )
            if (isEmpty()) add(
                finding(
                    "No meaningful change",
                    "Heartbeat completed without an actionable finding.",
                    FindingSeverity.QUIET,
                    input.evidenceRefs,
                    notification = false
                )
            )
        }
        val signals = findings.filter { finding ->
            finding.severity in setOf(FindingSeverity.ACTIONABLE, FindingSeverity.URGENT)
        }.map { finding ->
            FeedbackSignal(
                id = idGenerator.nextId("heartbeat-signal"),
                type = if (finding.title.contains("failure", true)) {
                    FeedbackSignalType.REPEATED_FAILURE
                } else if (finding.title.contains("Candidate", true)) {
                    FeedbackSignalType.CANDIDATE_BACKLOG
                } else {
                    FeedbackSignalType.TODO_CHANGED
                },
                source = FeedbackSignalSource.PRODUCT,
                summary = finding.summary,
                importance = if (finding.severity == FindingSeverity.URGENT) 95 else 80,
                evidenceRefs = finding.evidenceRefs,
                createdAtEpochMillis = clock.nowEpochMillis(),
                expiresAtEpochMillis = clock.nowEpochMillis() + 24 * 60 * 60 * 1_000L
            )
        }
        return HeartbeatReport(findings, signals)
    }

    private fun finding(
        title: String,
        summary: String,
        severity: FindingSeverity,
        evidence: List<String>,
        notification: Boolean
    ) = FeedbackFinding(
        idGenerator.nextId("finding"),
        title,
        summary,
        severity,
        evidence,
        notification
    )
}

enum class DreamProposalType {
    MEMORY,
    PERSONA,
    EXPERIENCE,
    TODO_DRAFT
}

data class DreamProposal(
    val type: DreamProposalType,
    val summary: String,
    val evidenceRefs: List<String>,
    val requiresCandidateReview: Boolean = true
)

data class DreamReport(
    val collectedEvidenceRefs: List<String>,
    val patterns: List<String>,
    val conflicts: List<String>,
    val proposals: List<DreamProposal>
)

class DreamEngine {
    fun reflect(
        outcomes: List<RunOutcomeRecord>,
        pendingCandidates: List<AgentAssetCandidate>
    ): DreamReport {
        val recent = outcomes.takeLast(100)
        val repeatedFailures = recent.filter { it.status == OutcomeStatus.FAILED }
            .groupingBy { it.errorCategory ?: it.resultSummary }.eachCount()
            .filterValues { it >= 2 }
        val corrections = recent.filter {
            it.userFeedback == FeedbackSignalType.USER_CORRECTED
        }
        val conflicts = pendingCandidates.flatMap(AgentAssetCandidate::conflictRefs).distinct()
        val proposals = buildList {
            repeatedFailures.forEach { (key, count) ->
                add(
                    DreamProposal(
                        DreamProposalType.EXPERIENCE,
                        "Review reusable guidance for repeated failure '$key' ($count times).",
                        recent.filter { (it.errorCategory ?: it.resultSummary) == key }
                            .flatMap(RunOutcomeRecord::evidenceRefs).distinct()
                    )
                )
            }
            if (corrections.isNotEmpty()) {
                add(
                    DreamProposal(
                        DreamProposalType.MEMORY,
                        "Evaluate ${corrections.size} user corrections as memory candidates.",
                        corrections.flatMap(RunOutcomeRecord::evidenceRefs).distinct()
                    )
                )
            }
        }
        return DreamReport(
            collectedEvidenceRefs = recent.flatMap(RunOutcomeRecord::evidenceRefs).distinct(),
            patterns = repeatedFailures.map { (key, count) -> "$key repeated $count times" },
            conflicts = conflicts,
            proposals = proposals
        )
    }
}

data class HomeBrief(
    val title: String,
    val summary: String,
    val overdueTodoCount: Int,
    val pendingCandidateCount: Int,
    val enabledScheduleCount: Int,
    val lastOutcome: OutcomeStatus?,
    val findings: List<FeedbackFinding>,
    val generatedAtEpochMillis: Long
)

class HomeBriefCompiler(
    private val clock: AgentClock = SystemAgentClock
) {
    fun compile(
        overdueTodoCount: Int,
        candidates: List<AgentAssetCandidate>,
        enabledScheduleCount: Int,
        outcomes: List<RunOutcomeRecord>,
        findings: List<FeedbackFinding>
    ): HomeBrief {
        val pending = candidates.count { candidate ->
            candidate.status in setOf(
                AgentCandidateStatus.PROPOSED,
                AgentCandidateStatus.VALIDATED,
                AgentCandidateStatus.EVALUATED,
                AgentCandidateStatus.WAITING_APPROVAL
            )
        }
        return HomeBrief(
            title = "Today",
            summary = "$overdueTodoCount overdue · $pending pending reviews · " +
                "$enabledScheduleCount automations enabled",
            overdueTodoCount = overdueTodoCount,
            pendingCandidateCount = pending,
            enabledScheduleCount = enabledScheduleCount,
            lastOutcome = outcomes.lastOrNull()?.status,
            findings = findings.filter { it.severity != FindingSeverity.QUIET }.take(5),
            generatedAtEpochMillis = clock.nowEpochMillis()
        )
    }
}

data class SelfCheckReport(
    val healthy: Boolean,
    val checks: Map<String, Boolean>,
    val diagnostics: List<String>
)

class AgentSelfCheck {
    fun run(
        sessionStoreReadable: Boolean,
        stateStoreReadable: Boolean,
        pendingApprovalCount: Int,
        stuckRunCount: Int,
        scheduleCount: Int,
        credentialAvailable: Boolean
    ): SelfCheckReport {
        val checks = linkedMapOf(
            "session_store" to sessionStoreReadable,
            "state_store" to stateStoreReadable,
            "approval_queue" to (pendingApprovalCount < 100),
            "stuck_runs" to (stuckRunCount == 0),
            "schedule_registry" to (scheduleCount >= 0),
            "credential" to credentialAvailable
        )
        return SelfCheckReport(
            healthy = checks.values.all { passed -> passed },
            checks = checks,
            diagnostics = checks.filterValues { passed -> !passed }.keys.map { key ->
                "Check '$key' needs attention."
            }
        )
    }
}
