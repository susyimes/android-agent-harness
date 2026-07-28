// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextTrust
import java.io.Serializable

enum class AgentStateCollection {
    IDENTITY,
    CURRENT_STATE,
    CAPABILITIES,
    PERMISSIONS,
    EVENTS,
    EVIDENCE,
    OPEN_LOOPS,
    BRIEFS,
    EFFECTS,
    CANDIDATES,
    EVAL_EPISODES
}

data class AgentStateDocument(
    val id: String,
    val collection: AgentStateCollection,
    val revision: Long,
    val title: String,
    val content: String,
    val source: String,
    val privacy: ContextPrivacy = ContextPrivacy.INTERNAL,
    val evidenceRefs: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val tombstone: Boolean = false
) : Serializable {
    init {
        require(id.isNotBlank()) { "State document id must not be blank." }
        require(revision > 0) { "State document revision must be positive." }
        require(title.isNotBlank()) { "State document title must not be blank." }
        require(source.isNotBlank()) { "State document source must not be blank." }
        require(evidenceRefs.none(String::isBlank)) { "Evidence refs must not be blank." }
        require(metadata.keys.none(String::isBlank)) { "Metadata keys must not be blank." }
        require(createdAtEpochMillis <= updatedAtEpochMillis) {
            "State document creation time cannot exceed update time."
        }
    }
}

data class AgentStateDocumentWrite(
    val id: String,
    val collection: AgentStateCollection,
    val title: String,
    val content: String,
    val source: String,
    val privacy: ContextPrivacy = ContextPrivacy.INTERNAL,
    val evidenceRefs: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val expectedRevision: Long? = null,
    val tombstone: Boolean = false
) : Serializable {
    init {
        require(id.isNotBlank()) { "State document id must not be blank." }
        require(title.isNotBlank()) { "State document title must not be blank." }
        require(source.isNotBlank()) { "State document source must not be blank." }
        require(expectedRevision == null || expectedRevision >= 0) {
            "Expected document revision must not be negative."
        }
    }
}

data class AgentStateEvent(
    val id: String,
    val type: String,
    val source: String,
    val summary: String,
    val runId: String? = null,
    val sessionId: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val privacy: ContextPrivacy = ContextPrivacy.INTERNAL,
    val createdAtEpochMillis: Long
) : Serializable {
    init {
        require(id.isNotBlank()) { "State event id must not be blank." }
        require(type.isNotBlank()) { "State event type must not be blank." }
        require(source.isNotBlank()) { "State event source must not be blank." }
        require(summary.isNotBlank()) { "State event summary must not be blank." }
        require(runId == null || runId.isNotBlank()) { "Event run id must not be blank." }
        require(sessionId == null || sessionId.isNotBlank()) {
            "Event session id must not be blank."
        }
        require(evidenceRefs.none(String::isBlank)) { "Event evidence refs must not be blank." }
    }
}

data class AgentStateEvidence(
    val id: String,
    val source: String,
    val summary: String,
    val locator: String? = null,
    val contentHash: String,
    val privacy: ContextPrivacy = ContextPrivacy.INTERNAL,
    val trust: ContextTrust,
    val observedAtEpochMillis: Long,
    val validUntilEpochMillis: Long? = null
) : Serializable {
    init {
        require(id.isNotBlank()) { "Evidence id must not be blank." }
        require(source.isNotBlank()) { "Evidence source must not be blank." }
        require(summary.isNotBlank()) { "Evidence summary must not be blank." }
        require(locator == null || locator.isNotBlank()) { "Evidence locator must not be blank." }
        require(contentHash.isNotBlank()) { "Evidence content hash must not be blank." }
        require(validUntilEpochMillis == null || validUntilEpochMillis >= observedAtEpochMillis) {
            "Evidence validity cannot end before it was observed."
        }
    }
}

enum class AgentStateEffectStatus {
    PENDING,
    APPLIED,
    DENIED,
    FAILED,
    ROLLED_BACK
}

data class AgentStateEffect(
    val id: String,
    val kind: String,
    val assetKey: String?,
    val candidateId: String?,
    val fromRevisionId: String?,
    val toRevisionId: String?,
    val candidateHash: String?,
    val evalReportId: String?,
    val approvalId: String?,
    val status: AgentStateEffectStatus,
    val summary: String,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long? = null
) : Serializable {
    init {
        require(id.isNotBlank()) { "State effect id must not be blank." }
        require(kind.isNotBlank()) { "State effect kind must not be blank." }
        require(assetKey == null || assetKey.isNotBlank()) { "Effect asset key must not be blank." }
        require(candidateId == null || candidateId.isNotBlank()) {
            "Effect candidate id must not be blank."
        }
        require(candidateHash == null || candidateHash.isNotBlank()) {
            "Effect candidate hash must not be blank."
        }
        require(summary.isNotBlank()) { "State effect summary must not be blank." }
        require(completedAtEpochMillis == null || completedAtEpochMillis >= createdAtEpochMillis) {
            "Effect completion cannot predate creation."
        }
    }
}

data class AgentBrief(
    val id: String,
    val title: String,
    val summary: String,
    val eventRefs: List<String>,
    val evidenceRefs: List<String>,
    val openLoopRefs: List<String>,
    val pendingCandidateRefs: List<String>,
    val createdAtEpochMillis: Long,
    val validUntilEpochMillis: Long? = null
) : Serializable {
    init {
        require(id.isNotBlank()) { "Brief id must not be blank." }
        require(title.isNotBlank()) { "Brief title must not be blank." }
        require(summary.isNotBlank()) { "Brief summary must not be blank." }
        require(
            (eventRefs + evidenceRefs + openLoopRefs + pendingCandidateRefs).none(String::isBlank)
        ) { "Brief references must not be blank." }
        require(validUntilEpochMillis == null || validUntilEpochMillis >= createdAtEpochMillis) {
            "Brief validity cannot end before creation."
        }
    }
}

data class PsycheObservation(
    val id: String,
    val dimension: PersonaDimension,
    val observation: String,
    val sourceRunId: String?,
    val sourceSessionId: String?,
    val evidenceRefs: List<String>,
    val confidence: Double,
    val observedAtEpochMillis: Long
) : Serializable {
    init {
        require(id.isNotBlank()) { "Psyche observation id must not be blank." }
        require(observation.isNotBlank()) { "Psyche observation must not be blank." }
        require(evidenceRefs.none(String::isBlank)) {
            "Psyche observation evidence refs must not be blank."
        }
        require(confidence in 0.0..1.0) { "Psyche confidence must be between 0 and 1." }
    }
}

enum class AgentAssetKind {
    MEMORY,
    SKILL,
    PERSONA,
    HOUSE_CORE,
    PROMPT_OVERLAY,
    EVALUATOR_CASE
}

enum class AgentCandidateStatus {
    PROPOSED,
    VALIDATED,
    EVALUATED,
    WAITING_APPROVAL,
    PROMOTED,
    REJECTED,
    EXPIRED,
    SUPERSEDED,
    ROLLED_BACK
}

enum class MemoryCandidateType {
    FACT,
    PREFERENCE,
    TASK_STATE,
    RUNBOOK,
    REFLECTION,
    CORRECTION,
    DELETION,
    EXPIRY
}

enum class PersonaDimension {
    TONE,
    INITIATIVE,
    COLLABORATION,
    BOUNDARIES,
    OTHER
}

data class AgentCandidateSource(
    val runId: String,
    val sessionId: String,
    val author: String = "agent",
    val trigger: String = "run"
) : Serializable {
    init {
        require(runId.isNotBlank()) { "Candidate source run id must not be blank." }
        require(sessionId.isNotBlank()) { "Candidate source session id must not be blank." }
        require(author.isNotBlank()) { "Candidate author must not be blank." }
        require(trigger.isNotBlank()) { "Candidate trigger must not be blank." }
    }
}

data class MemoryCandidate(
    val source: AgentCandidateSource,
    val type: MemoryCandidateType,
    val proposedText: String,
    val trust: ContextTrust,
    val confidence: Double,
    val evidenceRefs: List<String>,
    val privacy: ContextPrivacy,
    val targetScope: String,
    val dedupeKey: String,
    val conflictRefs: List<String> = emptyList(),
    val ttlMillis: Long? = null
) : Serializable

data class SkillDraft(
    val source: AgentCandidateSource,
    val skillId: String,
    val name: String,
    val description: String,
    val content: String,
    val evidenceRefs: List<String>,
    val baseRevisionId: String? = null,
    val diff: String = "",
    val capabilityClaims: Set<String> = emptySet(),
    val privacy: ContextPrivacy = ContextPrivacy.INTERNAL
) : Serializable

data class PersonaProposal(
    val source: AgentCandidateSource,
    val dimension: PersonaDimension,
    val proposedText: String,
    val evidenceRefs: List<String>,
    val confidence: Double,
    val observationWindow: String,
    val conflictRefs: List<String> = emptyList(),
    val baseRevisionId: String? = null,
    val privacy: ContextPrivacy = ContextPrivacy.INTERNAL
) : Serializable

data class AgentAssetCandidate(
    val id: String,
    val assetKey: String,
    val kind: AgentAssetKind,
    val source: AgentCandidateSource,
    val title: String,
    val proposedContent: String,
    val trust: ContextTrust,
    val confidence: Double,
    val evidenceRefs: List<String>,
    val privacy: ContextPrivacy,
    val dedupeKey: String,
    val conflictRefs: List<String>,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val candidateHash: String,
    val status: AgentCandidateStatus,
    val statusReason: String? = null,
    val baseRevisionId: String? = null,
    val diff: String = "",
    val memoryType: MemoryCandidateType? = null,
    val personaDimension: PersonaDimension? = null,
    val capabilityClaims: Set<String> = emptySet(),
    val version: Long = 1
) : Serializable {
    init {
        require(id.isNotBlank()) { "Candidate id must not be blank." }
        require(assetKey.isNotBlank()) { "Candidate asset key must not be blank." }
        require(title.isNotBlank()) { "Candidate title must not be blank." }
        require(proposedContent.isNotBlank()) { "Candidate content must not be blank." }
        require(confidence in 0.0..1.0) { "Candidate confidence must be between 0 and 1." }
        require(evidenceRefs.none(String::isBlank)) { "Candidate evidence refs must not be blank." }
        require(dedupeKey.isNotBlank()) { "Candidate dedupe key must not be blank." }
        require(conflictRefs.none(String::isBlank)) { "Candidate conflicts must not be blank." }
        require(candidateHash.isNotBlank()) { "Candidate hash must not be blank." }
        require(statusReason == null || statusReason.isNotBlank()) {
            "Candidate status reason must not be blank."
        }
        require(baseRevisionId == null || baseRevisionId.isNotBlank()) {
            "Candidate base revision must not be blank."
        }
        require(capabilityClaims.none(String::isBlank)) {
            "Candidate capability claims must not be blank."
        }
        require(version > 0) { "Candidate version must be positive." }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= createdAtEpochMillis) {
            "Candidate expiry cannot predate creation."
        }
    }
}

data class CandidateReceipt(
    val candidateId: String,
    val candidateHash: String,
    val status: AgentCandidateStatus,
    val duplicateOf: String? = null
) : Serializable

fun interface MemoryCandidateSink {
    fun propose(candidate: MemoryCandidate): CandidateReceipt
}

fun interface SkillDraftSink {
    fun propose(candidate: SkillDraft): CandidateReceipt
}

fun interface PersonaProposalSink {
    fun propose(candidate: PersonaProposal): CandidateReceipt
}

enum class AgentEvalVerdict {
    PASS,
    FAIL,
    NEEDS_REVIEW
}

data class AgentEvalCheck(
    val id: String,
    val passed: Boolean,
    val detail: String
) : Serializable {
    init {
        require(id.isNotBlank()) { "Eval check id must not be blank." }
        require(detail.isNotBlank()) { "Eval check detail must not be blank." }
    }
}

data class AgentEvalReport(
    val id: String,
    val candidateId: String,
    val candidateHash: String,
    val evaluatorId: String,
    val verdict: AgentEvalVerdict,
    val checks: List<AgentEvalCheck>,
    val summary: String,
    val createdAtEpochMillis: Long
) : Serializable {
    init {
        require(id.isNotBlank()) { "Eval report id must not be blank." }
        require(candidateId.isNotBlank()) { "Eval candidate id must not be blank." }
        require(candidateHash.isNotBlank()) { "Eval candidate hash must not be blank." }
        require(evaluatorId.isNotBlank()) { "Evaluator id must not be blank." }
        require(checks.isNotEmpty()) { "Eval report must include checks." }
        require(summary.isNotBlank()) { "Eval summary must not be blank." }
    }
}

enum class AgentAssetRevisionStatus {
    ACTIVE,
    SUPERSEDED,
    ROLLED_BACK
}

data class AgentAssetRevision(
    val id: String,
    val assetKey: String,
    val kind: AgentAssetKind,
    val revision: Long,
    val title: String,
    val content: String,
    val candidateId: String,
    val candidateHash: String,
    val evalReportId: String,
    val approvalId: String,
    val previousRevisionId: String?,
    val rollbackPointRevisionId: String?,
    val evidenceRefs: List<String>,
    val privacy: ContextPrivacy,
    val status: AgentAssetRevisionStatus,
    val createdAtEpochMillis: Long
) : Serializable {
    init {
        require(id.isNotBlank()) { "Asset revision id must not be blank." }
        require(assetKey.isNotBlank()) { "Asset key must not be blank." }
        require(revision > 0) { "Asset revision number must be positive." }
        require(title.isNotBlank()) { "Asset revision title must not be blank." }
        require(content.isNotBlank()) { "Asset revision content must not be blank." }
        require(candidateId.isNotBlank()) { "Asset revision candidate must not be blank." }
        require(candidateHash.isNotBlank()) { "Asset revision hash must not be blank." }
        require(evalReportId.isNotBlank()) { "Asset revision eval report must not be blank." }
        require(approvalId.isNotBlank()) { "Asset revision approval must not be blank." }
        require(evidenceRefs.none(String::isBlank)) {
            "Asset revision evidence refs must not be blank."
        }
    }
}

data class AgentStateSnapshot(
    val documents: List<AgentStateDocument>,
    val events: List<AgentStateEvent>,
    val evidence: List<AgentStateEvidence>,
    val effects: List<AgentStateEffect>,
    val briefs: List<AgentBrief>,
    val psycheObservations: List<PsycheObservation>,
    val candidates: List<AgentAssetCandidate>,
    val evalReports: List<AgentEvalReport>,
    val revisions: List<AgentAssetRevision>
) : Serializable
