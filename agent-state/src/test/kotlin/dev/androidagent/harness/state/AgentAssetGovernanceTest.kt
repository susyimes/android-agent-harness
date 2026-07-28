// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextTaskType
import dev.androidagent.harness.context.ContextTrust
import dev.androidagent.harness.context.RuleBasedContextNeedAnalyzer
import dev.androidagent.harness.AgentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAssetGovernanceTest {
    private val clock = FixedAgentClock(1_000L)
    private val ids = SequentialAgentIdGenerator("state")

    @Test
    fun candidateIsNotContextUntilHashEvalAndApprovalProduceRevision() {
        val vault = InMemoryAgentStateVault(clock)
        val governance = governance(vault, AgentApprovalDecision.APPROVED)
        val receipt = governance.memorySink.propose(memory("Concise answers are preferred."))

        assertEquals(AgentCandidateStatus.PROPOSED, receipt.status)
        assertTrue(approvedContext(vault).isEmpty())

        val report = governance.validateAndEvaluate(receipt.candidateId)
        assertEquals(AgentEvalVerdict.PASS, report.verdict)
        assertTrue(approvedContext(vault).isEmpty())

        val promotion = governance.promote(receipt.candidateId)

        assertTrue(promotion.promoted)
        val revision = requireNotNull(promotion.revision)
        assertEquals(receipt.candidateHash, revision.candidateHash)
        assertEquals(report.id, revision.evalReportId)
        assertTrue(revision.approvalId.isNotBlank())
        assertNull(revision.rollbackPointRevisionId)
        assertEquals(
            listOf("Concise answers are preferred."),
            approvedContext(vault).map { item -> item.body }
        )
    }

    @Test
    fun deniedPromotionFailsClosedAndLeavesNoDurableRevision() {
        val vault = InMemoryAgentStateVault(clock)
        val governance = governance(vault, AgentApprovalDecision.DENIED)
        val receipt = governance.memorySink.propose(memory("Do not persist without approval."))
        governance.validateAndEvaluate(receipt.candidateId)

        val result = governance.promote(receipt.candidateId)

        assertFalse(result.promoted)
        assertNull(result.revision)
        assertEquals(AgentStateEffectStatus.DENIED, result.effect.status)
        assertTrue(vault.read { revisions().isEmpty() })
        assertTrue(approvedContext(vault).isEmpty())
    }

    @Test
    fun supersedingPromotionHasRollbackPointAndRollbackIsAudited() {
        val vault = InMemoryAgentStateVault(clock)
        val governance = governance(vault, AgentApprovalDecision.APPROVED)
        val first = governance.memorySink.propose(memory("Answer briefly."))
        governance.validateAndEvaluate(first.candidateId)
        val firstRevision = requireNotNull(governance.promote(first.candidateId).revision)

        val second = governance.memorySink.propose(
            memory("Answer briefly, with implementation evidence.")
                .copy(
                    conflictRefs = listOf(firstRevision.id)
                )
        )
        governance.validateAndEvaluate(second.candidateId)
        val secondRevision = requireNotNull(governance.promote(second.candidateId).revision)

        assertEquals(firstRevision.id, secondRevision.rollbackPointRevisionId)
        assertEquals(secondRevision.id, vault.read { activeRevision(secondRevision.assetKey)!!.id })

        val rollback = governance.rollback(
            assetKey = secondRevision.assetKey,
            targetRevisionId = firstRevision.id,
            runId = "run-user",
            sessionId = "session-user",
            reason = "User restored the prior wording."
        )

        assertEquals(firstRevision.id, rollback.activeRevision.id)
        assertEquals(AgentStateEffectStatus.APPLIED, rollback.effect.status)
        assertNotNull(rollback.effect.approvalId)
        assertEquals(
            AgentAssetRevisionStatus.ROLLED_BACK,
            vault.read { revision(secondRevision.id)!!.status }
        )
        assertEquals(3, vault.read { effects() }.count { it.status == AgentStateEffectStatus.APPLIED })
    }

    @Test
    fun psycheObservationAndPendingPersonaStayOutOfApprovedContext() {
        val vault = InMemoryAgentStateVault(clock)
        val governance = governance(vault, AgentApprovalDecision.APPROVED)
        governance.recordPsycheObservation(
            dimension = PersonaDimension.INITIATIVE,
            observation = "The user appeared to value proactive summaries.",
            sourceRunId = "run-1",
            sourceSessionId = "session-1",
            evidenceRefs = listOf("evidence-1"),
            confidence = 0.6
        )
        governance.personaSink.propose(
            PersonaProposal(
                source = source(),
                dimension = PersonaDimension.INITIATIVE,
                proposedText = "Offer a concise next step after completed work.",
                evidenceRefs = listOf("evidence-1"),
                confidence = 0.7,
                observationWindow = "three completed runs"
            )
        )

        assertEquals(1, vault.read { psycheObservations() }.size)
        assertEquals(1, governance.inbox(AgentAssetKind.PERSONA).size)
        assertTrue(approvedContext(vault).isEmpty())
    }

    @Test
    fun credentialLikeCandidateNeverEntersVault() {
        val vault = InMemoryAgentStateVault(clock)
        val governance = governance(vault, AgentApprovalDecision.APPROVED)

        val failure = runCatching {
            governance.memorySink.propose(memory("API key: sk-should-not-be-stored"))
        }

        assertTrue(failure.isFailure)
        assertTrue(vault.read { candidates().isEmpty() })
    }

    private fun governance(
        vault: AgentStateVault,
        decision: AgentApprovalDecision
    ) = AgentAssetGovernance(
        vault = vault,
        approvals = AgentApprovalCoordinator(
            gate = AgentApprovalGate { decision },
            clock = clock,
            idGenerator = ids
        ),
        evaluator = RuleBasedAgentCandidateEvaluator { clock.nowEpochMillis() },
        clock = clock,
        idGenerator = ids
    )

    private fun source() = AgentCandidateSource(
        runId = "run-1",
        sessionId = "session-1"
    )

    private fun memory(
        text: String,
        dedupe: String = "response-style"
    ) = MemoryCandidate(
        source = source(),
        type = MemoryCandidateType.PREFERENCE,
        proposedText = text,
        trust = ContextTrust.USER_CONFIRMED,
        confidence = 0.9,
        evidenceRefs = listOf("evidence-1"),
        privacy = ContextPrivacy.INTERNAL,
        targetScope = "user",
        dedupeKey = dedupe
    )

    private fun approvedContext(vault: AgentStateVault) =
        AgentApprovedStateContextSource(vault).collect(
            ContextEngineRequest(
                session = AgentSession("session", 1L, 1L),
                userInput = "hello",
                taskType = ContextTaskType.CHAT,
                nowEpochMillis = 1_000L
            ),
            RuleBasedContextNeedAnalyzer().analyze(
                ContextEngineRequest(
                    session = AgentSession("session", 1L, 1L),
                    userInput = "hello",
                    taskType = ContextTaskType.CHAT,
                    nowEpochMillis = 1_000L
                )
            )
        )
}
