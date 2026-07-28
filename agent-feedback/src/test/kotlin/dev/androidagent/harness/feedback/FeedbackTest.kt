// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.feedback

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.sdk.AgentRunTrigger
import dev.androidagent.harness.state.AgentAssetKind
import dev.androidagent.harness.state.AgentCandidateSource
import dev.androidagent.harness.state.AgentCandidateStatus
import dev.androidagent.harness.state.AgentAssetCandidate
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {
    private val clock = AgentClock { 1_000L }
    private val ids = SequentialAgentIdGenerator("feedback")

    @Test
    fun proactiveDefaultsOffAndQuietHoursDefersWithoutActivationRequest() {
        val signals = InMemorySignalJournal()
        signals.append(signal(FeedbackSignalType.TODO_CHANGED, 95))
        val arbiter = ProactiveArbiter(
            signals,
            InMemoryOutcomeJournal(),
            clock = clock,
            idGenerator = ids
        )

        val off = arbiter.evaluate(ProactivePolicy(initiative = InitiativeLevel.OFF)).single()
        val quiet = arbiter.evaluate(
            ProactivePolicy(
                initiative = InitiativeLevel.HIGH,
                minimumScore = 0,
                quietHours = QuietHours("00:00", "23:59", "UTC")
            )
        ).single()

        assertEquals(ActivationDisposition.SUPPRESS, off.disposition)
        assertNull(off.request)
        assertEquals(ActivationDisposition.DEFER, quiet.disposition)
        assertNull(quiet.request)
    }

    @Test
    fun acceptedOpportunityProducesConstrainedRequestNotEffectAuthorization() {
        val signals = InMemorySignalJournal()
        signals.append(signal(FeedbackSignalType.REPEATED_FAILURE, 100))
        val result = ProactiveArbiter(
            signals,
            InMemoryOutcomeJournal(),
            clock = clock,
            idGenerator = ids
        ).evaluate(
            ProactivePolicy(
                initiative = InitiativeLevel.HIGH,
                minimumScore = 50,
                cooldownMillis = 0
            )
        ).single()

        assertEquals(ActivationDisposition.ACTIVATE, result.disposition)
        assertEquals(AgentRunTrigger.PROACTIVE, result.request!!.triggerType)
        assertEquals("proactive-readonly", result.request.toolProfileId)
        assertTrue(result.request.suggestedBudget.maxToolCalls <= 4)
        assertEquals(
            1,
            signals.query().count { it.type == FeedbackSignalType.ACTIVATION_EMITTED }
        )
    }

    @Test
    fun oneEvaluationCannotExceedDailyActivationCap() {
        val signals = InMemorySignalJournal()
        repeat(5) { index ->
            signals.append(
                signal(FeedbackSignalType.TODO_CHANGED, 100).copy(
                    id = "signal-$index"
                )
            )
        }

        val decisions = ProactiveArbiter(
            signals,
            InMemoryOutcomeJournal(),
            clock = clock,
            idGenerator = ids
        ).evaluate(
            ProactivePolicy(
                initiative = InitiativeLevel.HIGH,
                minimumScore = 0,
                dailyActivationCap = 2,
                cooldownMillis = 0
            )
        )

        assertEquals(
            2,
            decisions.count { it.disposition == ActivationDisposition.ACTIVATE }
        )
        assertEquals(
            2,
            signals.query().count { it.type == FeedbackSignalType.ACTIVATION_EMITTED }
        )
    }

    @Test
    fun heartbeatOnlyCreatesFindingsAndSignals() {
        val report = HeartbeatEngine(clock, ids).inspect(
            HeartbeatInput(
                overdueTodoCount = 2,
                permissionProblemCount = 1,
                pendingCandidateCount = 4,
                repeatedFailureCount = 0,
                evidenceRefs = listOf("todo-summary", "permission-snapshot")
            )
        )

        assertTrue(report.findings.any { it.title == "Overdue Todo" })
        assertTrue(report.activationSignals.isNotEmpty())
        assertTrue(
            report.activationSignals.all {
                it.type in setOf(
                    FeedbackSignalType.TODO_CHANGED,
                    FeedbackSignalType.CANDIDATE_BACKLOG,
                    FeedbackSignalType.REPEATED_FAILURE
                )
            }
        )
        assertTrue(report.findings.any { it.title == "Candidate review" })
    }

    @Test
    fun dreamReturnsReviewableProposalsWithoutPromotingCandidates() {
        val outcomes = listOf(
            outcome("one", OutcomeStatus.FAILED, "network"),
            outcome("two", OutcomeStatus.FAILED, "network"),
            outcome(
                "three",
                OutcomeStatus.COMPLETED,
                null,
                FeedbackSignalType.USER_CORRECTED
            )
        )
        val pending = listOf(candidate())

        val report = DreamEngine().reflect(outcomes, pending)

        assertTrue(report.patterns.any { it.contains("network") })
        assertTrue(report.proposals.any { it.type == DreamProposalType.EXPERIENCE })
        assertTrue(report.proposals.any { it.type == DreamProposalType.MEMORY })
        assertEquals(AgentCandidateStatus.PROPOSED, pending.single().status)
    }

    @Test
    fun homeBriefAndSelfCheckExposeProductState() {
        val brief = HomeBriefCompiler(clock).compile(
            overdueTodoCount = 1,
            candidates = listOf(candidate()),
            enabledScheduleCount = 2,
            outcomes = listOf(outcome("one", OutcomeStatus.COMPLETED, null)),
            findings = emptyList()
        )
        val check = AgentSelfCheck().run(
            sessionStoreReadable = true,
            stateStoreReadable = true,
            pendingApprovalCount = 0,
            stuckRunCount = 1,
            scheduleCount = 2,
            credentialAvailable = true
        )

        assertTrue(brief.summary.contains("1 pending"))
        assertFalse(check.healthy)
        assertTrue(check.diagnostics.single().contains("stuck_runs"))
    }

    private fun signal(type: FeedbackSignalType, importance: Int) = FeedbackSignal(
        id = "signal-${type.name}",
        type = type,
        source = FeedbackSignalSource.PRODUCT,
        summary = "Something changed.",
        importance = importance,
        evidenceRefs = listOf("evidence"),
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 2_000L
    )

    private fun outcome(
        id: String,
        status: OutcomeStatus,
        error: String?,
        feedback: FeedbackSignalType? = null
    ) = RunOutcomeRecord(
        id,
        "run-$id",
        AgentRunTrigger.USER,
        "Goal",
        "Result",
        status,
        userFeedback = feedback,
        evidenceRefs = listOf("evidence-$id"),
        errorCategory = error,
        createdAtEpochMillis = 1_000L
    )

    private fun candidate() = AgentAssetCandidate(
        id = "candidate",
        assetKey = "memory:user:test",
        kind = AgentAssetKind.MEMORY,
        source = AgentCandidateSource("run", "session"),
        title = "Candidate",
        proposedContent = "Proposed memory.",
        trust = ContextTrust.AGENT_PROPOSED,
        confidence = 0.7,
        evidenceRefs = listOf("evidence"),
        privacy = ContextPrivacy.INTERNAL,
        dedupeKey = "test",
        conflictRefs = listOf("revision-old"),
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = null,
        candidateHash = "hash",
        status = AgentCandidateStatus.PROPOSED
    )
}
