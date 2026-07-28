// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentEffectAuthorization
import dev.androidagent.harness.approval.AgentEffectHasher
import dev.androidagent.harness.approval.AgentEffectIntent

/**
 * Bounded retention for observational State Vault data.
 *
 * Durable documents, candidates, evaluations, effects, and asset revisions are
 * deliberately outside automatic retention: removing those records requires an
 * explicit domain deletion by the host. Evidence referenced by any retained
 * record is also preserved even after its validity window expires.
 */
data class AgentStateRetentionPolicy(
    val maxEvents: Int = 1_000,
    val maxBriefs: Int = 128,
    val maxPsycheObservations: Int = 512,
    val expiredEvidenceGraceMillis: Long = 7L * 24 * 60 * 60 * 1_000
) {
    init {
        require(maxEvents >= 0) { "Event retention limit must not be negative." }
        require(maxBriefs >= 0) { "Brief retention limit must not be negative." }
        require(maxPsycheObservations >= 0) {
            "Psyche observation retention limit must not be negative."
        }
        require(expiredEvidenceGraceMillis >= 0) {
            "Expired evidence grace period must not be negative."
        }
    }
}

data class AgentStateRetentionReport(
    val beforeRecords: Int,
    val afterRecords: Int,
    val removedEvents: Int,
    val removedBriefs: Int,
    val removedPsycheObservations: Int,
    val removedEvidence: Int
) {
    val removedRecords: Int
        get() = beforeRecords - afterRecords
}

data class AgentStateDeletionReport(
    val deletedRecords: Int,
    val completed: Boolean
)

data class AgentStateRetentionResult(
    val snapshot: AgentStateSnapshot,
    val report: AgentStateRetentionReport
)

/**
 * Optional maintenance port implemented by stores that support atomic snapshot
 * replacement. It stays separate from [AgentStateVault], so a runtime store can
 * remain read/transaction-only while product hosts opt into retention controls.
 */
interface AgentStateMaintenance {
    fun exportSnapshot(): AgentStateSnapshot

    fun applyRetention(
        policy: AgentStateRetentionPolicy = AgentStateRetentionPolicy()
    ): AgentStateRetentionReport

    fun deleteAll(): AgentStateDeletionReport
}

data class GovernedAgentStateDeletionResult(
    val applied: Boolean,
    val report: AgentStateDeletionReport?,
    val reason: String
)

class GovernedAgentStateMaintenance(
    private val vault: AgentStateVault,
    private val approvals: AgentApprovalCoordinator
) {
    fun deleteAll(
        runId: String,
        sessionId: String
    ): GovernedAgentStateDeletionResult {
        val maintenance = vault as? AgentStateMaintenance
            ?: return GovernedAgentStateDeletionResult(
                false,
                null,
                "State Vault does not support atomic domain deletion."
            )
        val snapshot = vault.snapshot()
        val snapshotHash = snapshot.maintenanceHash()
        val argumentHash = AgentEffectHasher.hash(
            "agent_state_delete_all",
            mapOf(
                "snapshotHash" to snapshotHash,
                "recordCount" to snapshot.recordCount().toString()
            )
        )
        val intent = AgentEffectIntent(
            runId = runId,
            sessionId = sessionId,
            toolCallId = "agent-state-delete-all:$snapshotHash",
            toolName = "agent_state_delete_all",
            capability = STATE_DELETE_CAPABILITY,
            targetRef = "agent-state:*",
            argumentHash = argumentHash,
            summary = "Delete all ${snapshot.recordCount()} State Vault records."
        )
        val token = (approvals.authorize(intent) as? AgentEffectAuthorization.Allowed)?.token
        if (token == null) {
            return GovernedAgentStateDeletionResult(
                false,
                null,
                "State Vault deletion approval was not granted."
            )
        }
        if (vault.snapshot().maintenanceHash() != snapshotHash) {
            return GovernedAgentStateDeletionResult(
                false,
                null,
                "State Vault changed while deletion approval was pending."
            )
        }
        if (!approvals.consume(token, intent)) {
            return GovernedAgentStateDeletionResult(
                false,
                null,
                "State Vault approval token expired, changed, or was already consumed."
            )
        }
        return runCatching { maintenance.deleteAll() }.fold(
            onSuccess = { report ->
                GovernedAgentStateDeletionResult(true, report, "State Vault deleted.")
            },
            onFailure = { error ->
                GovernedAgentStateDeletionResult(
                    false,
                    null,
                    error.message ?: "State Vault deletion failed."
                )
            }
        )
    }

    companion object {
        val STATE_DELETE_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DURABLE_WRITE,
            risk = AgentToolRisk.HIGH,
            dataScopes = setOf("agent-state"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY
        )
    }
}

class AgentStateRetentionEngine {
    fun retain(
        snapshot: AgentStateSnapshot,
        nowEpochMillis: Long,
        policy: AgentStateRetentionPolicy = AgentStateRetentionPolicy()
    ): AgentStateRetentionResult {
        require(nowEpochMillis >= 0) { "Retention time must not be negative." }

        val events = snapshot.events
            .sortedWith(
                compareBy<AgentStateEvent> { event -> event.createdAtEpochMillis }
                    .thenBy { event -> event.id }
            )
            .takeLast(policy.maxEvents)
        val briefs = snapshot.briefs
            .sortedWith(
                compareBy<AgentBrief> { brief -> brief.createdAtEpochMillis }
                    .thenBy { brief -> brief.id }
            )
            .takeLast(policy.maxBriefs)
        val psyche = snapshot.psycheObservations
            .sortedWith(
                compareBy<PsycheObservation> { value -> value.observedAtEpochMillis }
                    .thenBy { value -> value.id }
            )
            .takeLast(policy.maxPsycheObservations)

        val protectedEvidence = buildSet {
            snapshot.documents.flatMapTo(this, AgentStateDocument::evidenceRefs)
            events.flatMapTo(this, AgentStateEvent::evidenceRefs)
            briefs.flatMapTo(this, AgentBrief::evidenceRefs)
            psyche.flatMapTo(this, PsycheObservation::evidenceRefs)
            snapshot.candidates.flatMapTo(this, AgentAssetCandidate::evidenceRefs)
            snapshot.revisions.flatMapTo(this, AgentAssetRevision::evidenceRefs)
        }
        val evidence = snapshot.evidence.filter { item ->
            item.id in protectedEvidence ||
                item.validUntilEpochMillis == null ||
                item.validUntilEpochMillis >=
                nowEpochMillis - policy.expiredEvidenceGraceMillis
        }
        val retained = snapshot.copy(
            events = events,
            evidence = evidence,
            briefs = briefs,
            psycheObservations = psyche
        )
        return AgentStateRetentionResult(
            snapshot = retained,
            report = AgentStateRetentionReport(
                beforeRecords = snapshot.recordCount(),
                afterRecords = retained.recordCount(),
                removedEvents = snapshot.events.size - events.size,
                removedBriefs = snapshot.briefs.size - briefs.size,
                removedPsycheObservations =
                    snapshot.psycheObservations.size - psyche.size,
                removedEvidence = snapshot.evidence.size - evidence.size
            )
        )
    }
}

private fun AgentStateSnapshot.maintenanceHash(): String = CandidateHasher.hashParts(
    buildList {
        documents.sortedBy { value -> "${value.collection}:${value.id}" }.forEach { value ->
            add("document:${value.collection}:${value.id}:${value.revision}:${value.content}")
        }
        events.sortedBy(AgentStateEvent::id).forEach { value ->
            add("event:${value.id}:${value.createdAtEpochMillis}:${value.summary}")
        }
        evidence.sortedBy(AgentStateEvidence::id).forEach { value ->
            add("evidence:${value.id}:${value.contentHash}:${value.validUntilEpochMillis}")
        }
        effects.sortedBy(AgentStateEffect::id).forEach { value ->
            add("effect:${value.id}:${value.status}:${value.candidateHash}")
        }
        briefs.sortedBy(AgentBrief::id).forEach { value ->
            add("brief:${value.id}:${value.summary}")
        }
        psycheObservations.sortedBy(PsycheObservation::id).forEach { value ->
            add("psyche:${value.id}:${value.observation}:${value.confidence}")
        }
        candidates.sortedBy(AgentAssetCandidate::id).forEach { value ->
            add("candidate:${value.id}:${value.version}:${value.status}:${value.candidateHash}")
        }
        evalReports.sortedBy(AgentEvalReport::id).forEach { value ->
            add("eval:${value.id}:${value.verdict}:${value.candidateHash}")
        }
        revisions.sortedBy(AgentAssetRevision::id).forEach { value ->
            add("revision:${value.id}:${value.status}:${value.candidateHash}")
        }
    }
)

fun AgentStateSnapshot.recordCount(): Int =
    documents.size +
        events.size +
        evidence.size +
        effects.size +
        briefs.size +
        psycheObservations.size +
        candidates.size +
        evalReports.size +
        revisions.size

fun emptyAgentStateSnapshot(): AgentStateSnapshot = AgentStateSnapshot(
    documents = emptyList(),
    events = emptyList(),
    evidence = emptyList(),
    effects = emptyList(),
    briefs = emptyList(),
    psycheObservations = emptyList(),
    candidates = emptyList(),
    evalReports = emptyList(),
    revisions = emptyList()
)
