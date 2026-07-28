// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.AgentIdGenerator
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.UuidAgentIdGenerator
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentEffectAuthorization
import dev.androidagent.harness.approval.AgentEffectIntent
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextTrust
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class AgentEvalDraft(
    val evaluatorId: String,
    val verdict: AgentEvalVerdict,
    val checks: List<AgentEvalCheck>,
    val summary: String
)

fun interface AgentCandidateEvaluator {
    fun evaluate(
        candidate: AgentAssetCandidate,
        activeRevision: AgentAssetRevision?
    ): AgentEvalDraft
}

class RuleBasedAgentCandidateEvaluator(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : AgentCandidateEvaluator {
    override fun evaluate(
        candidate: AgentAssetCandidate,
        activeRevision: AgentAssetRevision?
    ): AgentEvalDraft {
        val checks = buildList {
            add(
                AgentEvalCheck(
                    "content",
                    candidate.proposedContent.isNotBlank() &&
                        candidate.proposedContent.length <= MAX_CONTENT_CHARS,
                    "Candidate content is present and within the local size limit."
                )
            )
            add(
                AgentEvalCheck(
                    "evidence",
                    candidate.evidenceRefs.isNotEmpty(),
                    "At least one evidence reference is required for durable promotion."
                )
            )
            add(
                AgentEvalCheck(
                    "privacy",
                    !CandidateSafety.containsCredential(candidate.proposedContent),
                    "Credential-like content is forbidden in durable Agent state."
                )
            )
            add(
                AgentEvalCheck(
                    "expiry",
                    candidate.expiresAtEpochMillis == null ||
                        candidate.expiresAtEpochMillis >= nowEpochMillis(),
                    "Candidate has not expired."
                )
            )
            add(
                AgentEvalCheck(
                    "base-revision",
                    candidate.baseRevisionId == null ||
                        candidate.baseRevisionId == activeRevision?.id,
                    "The candidate was evaluated against the current durable revision."
                )
            )
            if (candidate.kind == AgentAssetKind.SKILL) {
                add(
                    AgentEvalCheck(
                        "skill-disabled",
                        true,
                        "A candidate is inert; promotion is the only enable path."
                    )
                )
                add(
                    AgentEvalCheck(
                        "capability-claims",
                        candidate.capabilityClaims.isEmpty(),
                        if (candidate.capabilityClaims.isEmpty()) {
                            "The skill does not claim runtime capabilities."
                        } else {
                            "Skill capability claims require explicit host review and cannot grant access."
                        }
                    )
                )
            }
            if (candidate.kind == AgentAssetKind.PERSONA) {
                add(
                    AgentEvalCheck(
                        "persona-evidence-window",
                        candidate.diff.isNotBlank(),
                        "Persona proposals identify their observation window."
                    )
                )
            }
        }
        val failed = checks.filterNot(AgentEvalCheck::passed)
        val verdict = when {
            failed.any { check -> check.id in HARD_FAILURE_CHECKS } -> AgentEvalVerdict.FAIL
            failed.isNotEmpty() -> AgentEvalVerdict.NEEDS_REVIEW
            else -> AgentEvalVerdict.PASS
        }
        return AgentEvalDraft(
            evaluatorId = "rule-based-agent-state-v1",
            verdict = verdict,
            checks = checks,
            summary = when (verdict) {
                AgentEvalVerdict.PASS -> "Candidate passed deterministic validation."
                AgentEvalVerdict.FAIL -> "Candidate failed: ${failed.joinToString { it.id }}."
                AgentEvalVerdict.NEEDS_REVIEW ->
                    "Candidate needs explicit review: ${failed.joinToString { it.id }}."
            }
        )
    }

    private companion object {
        const val MAX_CONTENT_CHARS = 256_000
        val HARD_FAILURE_CHECKS = setOf("content", "evidence", "privacy", "expiry", "base-revision")
    }
}

data class AgentPromotionResult(
    val candidate: AgentAssetCandidate,
    val revision: AgentAssetRevision?,
    val effect: AgentStateEffect
) {
    val promoted: Boolean
        get() = revision != null && effect.status == AgentStateEffectStatus.APPLIED
}

data class AgentRollbackResult(
    val activeRevision: AgentAssetRevision,
    val effect: AgentStateEffect
)

/**
 * One local governance spine for memory, skill, persona, and House assets.
 *
 * Proposals are inert. A durable revision is created only after immutable hash
 * validation, evaluation, and a matching approval token.
 */
class AgentAssetGovernance(
    private val vault: AgentStateVault,
    private val approvals: AgentApprovalCoordinator = AgentApprovalCoordinator(),
    private val evaluator: AgentCandidateEvaluator = RuleBasedAgentCandidateEvaluator(),
    private val clock: AgentClock = SystemAgentClock,
    private val idGenerator: AgentIdGenerator = UuidAgentIdGenerator()
) {
    val memorySink: MemoryCandidateSink = MemoryCandidateSink(::proposeMemory)
    val skillSink: SkillDraftSink = SkillDraftSink(::proposeSkill)
    val personaSink: PersonaProposalSink = PersonaProposalSink(::proposePersona)

    fun proposeMemory(candidate: MemoryCandidate): CandidateReceipt {
        require(candidate.proposedText.isNotBlank()) { "Memory candidate text must not be blank." }
        require(candidate.confidence in 0.0..1.0) {
            "Memory candidate confidence must be between 0 and 1."
        }
        require(candidate.targetScope.isNotBlank()) { "Memory target scope must not be blank." }
        require(candidate.dedupeKey.isNotBlank()) { "Memory dedupe key must not be blank." }
        require(candidate.evidenceRefs.none(String::isBlank)) {
            "Memory evidence refs must not be blank."
        }
        rejectCredential(candidate.proposedText)
        val now = clock.nowEpochMillis()
        val proposed = AgentAssetCandidate(
                id = idGenerator.nextId("memory-candidate"),
                assetKey = "memory:${candidate.targetScope}:${candidate.dedupeKey}",
                kind = AgentAssetKind.MEMORY,
                source = candidate.source,
                title = "${candidate.type.name.lowercase(Locale.ROOT)} memory",
                proposedContent = candidate.proposedText.trim(),
                trust = candidate.trust,
                confidence = candidate.confidence,
                evidenceRefs = candidate.evidenceRefs.distinct(),
                privacy = candidate.privacy,
                dedupeKey = candidate.dedupeKey,
                conflictRefs = candidate.conflictRefs.distinct(),
                createdAtEpochMillis = now,
                expiresAtEpochMillis = candidate.ttlMillis?.let { ttl ->
                    require(ttl > 0) { "Memory candidate TTL must be positive." }
                    now + ttl
                },
                candidateHash = UNCOMPUTED_HASH,
                status = AgentCandidateStatus.PROPOSED,
                memoryType = candidate.type
            )
        return propose(proposed.copy(candidateHash = CandidateHasher.hash(proposed)))
    }

    fun proposeSkill(draft: SkillDraft): CandidateReceipt {
        require(CandidateSafety.ID_PATTERN.matches(draft.skillId)) {
            "Skill id must match ${CandidateSafety.ID_PATTERN.pattern}."
        }
        require(draft.name.isNotBlank()) { "Skill name must not be blank." }
        require(draft.content.isNotBlank()) { "Skill content must not be blank." }
        require(draft.evidenceRefs.none(String::isBlank)) {
            "Skill evidence refs must not be blank."
        }
        rejectCredential(draft.content)
        val now = clock.nowEpochMillis()
        val proposed = AgentAssetCandidate(
                id = idGenerator.nextId("skill-candidate"),
                assetKey = "skill:${draft.skillId}",
                kind = AgentAssetKind.SKILL,
                source = draft.source,
                title = draft.name.trim(),
                proposedContent = draft.content.trim(),
                trust = ContextTrust.AGENT_PROPOSED,
                confidence = 1.0,
                evidenceRefs = draft.evidenceRefs.distinct(),
                privacy = draft.privacy,
                dedupeKey = draft.skillId,
                conflictRefs = emptyList(),
                createdAtEpochMillis = now,
                expiresAtEpochMillis = null,
                candidateHash = UNCOMPUTED_HASH,
                status = AgentCandidateStatus.PROPOSED,
                baseRevisionId = draft.baseRevisionId,
                diff = draft.diff,
                capabilityClaims = draft.capabilityClaims
            )
        return propose(proposed.copy(candidateHash = CandidateHasher.hash(proposed)))
    }

    fun proposePersona(proposal: PersonaProposal): CandidateReceipt {
        require(proposal.proposedText.isNotBlank()) { "Persona proposal must not be blank." }
        require(proposal.evidenceRefs.none(String::isBlank)) {
            "Persona evidence refs must not be blank."
        }
        require(proposal.confidence in 0.0..1.0) {
            "Persona proposal confidence must be between 0 and 1."
        }
        require(proposal.observationWindow.isNotBlank()) {
            "Persona observation window must not be blank."
        }
        rejectCredential(proposal.proposedText)
        val now = clock.nowEpochMillis()
        val proposed = AgentAssetCandidate(
                id = idGenerator.nextId("persona-candidate"),
                assetKey = "persona:${proposal.dimension.name.lowercase(Locale.ROOT)}",
                kind = AgentAssetKind.PERSONA,
                source = proposal.source,
                title = "${proposal.dimension.name.lowercase(Locale.ROOT)} persona proposal",
                proposedContent = proposal.proposedText.trim(),
                trust = ContextTrust.AGENT_PROPOSED,
                confidence = proposal.confidence,
                evidenceRefs = proposal.evidenceRefs.distinct(),
                privacy = proposal.privacy,
                dedupeKey = proposal.dimension.name,
                conflictRefs = proposal.conflictRefs.distinct(),
                createdAtEpochMillis = now,
                expiresAtEpochMillis = null,
                candidateHash = UNCOMPUTED_HASH,
                status = AgentCandidateStatus.PROPOSED,
                baseRevisionId = proposal.baseRevisionId,
                diff = proposal.observationWindow,
                personaDimension = proposal.dimension
            )
        return propose(proposed.copy(candidateHash = CandidateHasher.hash(proposed)))
    }

    fun proposeHouseCore(
        source: AgentCandidateSource,
        key: String,
        title: String,
        content: String,
        evidenceRefs: List<String>,
        baseRevisionId: String? = null,
        diff: String = ""
    ): CandidateReceipt {
        require(CandidateSafety.ID_PATTERN.matches(key)) { "Invalid House core key '$key'." }
        require(content.isNotBlank()) { "House candidate content must not be blank." }
        rejectCredential(content)
        val now = clock.nowEpochMillis()
        val proposed = AgentAssetCandidate(
                id = idGenerator.nextId("house-candidate"),
                assetKey = "house:$key",
                kind = AgentAssetKind.HOUSE_CORE,
                source = source,
                title = title,
                proposedContent = content,
                trust = ContextTrust.USER_CONFIRMED,
                confidence = 1.0,
                evidenceRefs = evidenceRefs.distinct(),
                privacy = ContextPrivacy.INTERNAL,
                dedupeKey = key,
                conflictRefs = emptyList(),
                createdAtEpochMillis = now,
                expiresAtEpochMillis = null,
                candidateHash = UNCOMPUTED_HASH,
                status = AgentCandidateStatus.PROPOSED,
                baseRevisionId = baseRevisionId,
                diff = diff
            )
        return propose(proposed.copy(candidateHash = CandidateHasher.hash(proposed)))
    }

    fun validate(candidateId: String): AgentAssetCandidate {
        return vault.transaction {
            val candidate = requireNotNull(candidate(candidateId)) {
                "Unknown candidate '$candidateId'."
            }
            require(candidate.status == AgentCandidateStatus.PROPOSED) {
                "Candidate '$candidateId' is ${candidate.status}, expected PROPOSED."
            }
            require(candidate.candidateHash == CandidateHasher.hash(candidate)) {
                "Candidate '$candidateId' hash no longer matches its immutable content."
            }
            val next = candidate.copy(
                status = AgentCandidateStatus.VALIDATED,
                statusReason = "Immutable hash and local schema validated.",
                version = candidate.version + 1
            )
            putCandidate(next, candidate.version)
            next
        }
    }

    fun evaluate(candidateId: String): AgentEvalReport {
        val candidate = vault.read {
            requireNotNull(candidate(candidateId)) { "Unknown candidate '$candidateId'." }
        }
        require(candidate.status == AgentCandidateStatus.VALIDATED) {
            "Candidate '$candidateId' is ${candidate.status}, expected VALIDATED."
        }
        val active = vault.read { activeRevision(candidate.assetKey) }
        val draft = evaluator.evaluate(candidate, active)
        require(draft.checks.isNotEmpty()) { "Candidate evaluator returned no checks." }
        val report = AgentEvalReport(
            id = idGenerator.nextId("eval"),
            candidateId = candidate.id,
            candidateHash = candidate.candidateHash,
            evaluatorId = draft.evaluatorId,
            verdict = draft.verdict,
            checks = draft.checks,
            summary = draft.summary,
            createdAtEpochMillis = clock.nowEpochMillis()
        )
        return vault.transaction {
            val current = requireNotNull(candidate(candidateId))
            require(current.version == candidate.version) {
                "Candidate '$candidateId' changed during evaluation."
            }
            putEvalReport(report)
            val next = current.copy(
                status = AgentCandidateStatus.EVALUATED,
                statusReason = report.summary,
                version = current.version + 1
            )
            putCandidate(next, current.version)
            report
        }
    }

    fun validateAndEvaluate(candidateId: String): AgentEvalReport {
        val current = vault.read {
            requireNotNull(candidate(candidateId)) { "Unknown candidate '$candidateId'." }
        }
        if (current.status == AgentCandidateStatus.PROPOSED) validate(candidateId)
        return evaluate(candidateId)
    }

    fun promote(candidateId: String): AgentPromotionResult {
        val prepared = vault.transaction {
            val candidate = requireNotNull(candidate(candidateId)) {
                "Unknown candidate '$candidateId'."
            }
            require(
                candidate.status == AgentCandidateStatus.EVALUATED ||
                    candidate.status == AgentCandidateStatus.WAITING_APPROVAL
            ) {
                "Candidate '$candidateId' is ${candidate.status}, expected EVALUATED."
            }
            val report = evalReports(candidate.id).lastOrNull()
                ?: error("Candidate '$candidateId' has no evaluation.")
            require(report.candidateHash == candidate.candidateHash) {
                "Evaluation does not match the current candidate hash."
            }
            require(report.verdict != AgentEvalVerdict.FAIL) {
                "Candidate '$candidateId' failed evaluation."
            }
            val waiting = if (candidate.status == AgentCandidateStatus.EVALUATED) {
                candidate.copy(
                    status = AgentCandidateStatus.WAITING_APPROVAL,
                    statusReason = "Waiting for durable asset approval.",
                    version = candidate.version + 1
                ).also { next -> putCandidate(next, candidate.version) }
            } else {
                candidate
            }
            val effect = AgentStateEffect(
                id = idGenerator.nextId("asset-effect"),
                kind = "PROMOTION",
                assetKey = waiting.assetKey,
                candidateId = waiting.id,
                fromRevisionId = activeRevision(waiting.assetKey)?.id,
                toRevisionId = null,
                candidateHash = waiting.candidateHash,
                evalReportId = report.id,
                approvalId = null,
                status = AgentStateEffectStatus.PENDING,
                summary = "Promote ${waiting.kind.name.lowercase()} '${waiting.title}'.",
                createdAtEpochMillis = clock.nowEpochMillis()
            )
            putEffect(effect)
            PreparedPromotion(waiting, report, effect)
        }

        val intent = AgentEffectIntent(
            runId = prepared.candidate.source.runId,
            sessionId = prepared.candidate.source.sessionId,
            toolCallId = "promote:${prepared.candidate.id}",
            toolName = "agent_asset_promote",
            capability = DURABLE_ASSET_CAPABILITY,
            targetRef = prepared.candidate.assetKey,
            argumentHash = prepared.candidate.candidateHash,
            summary = prepared.effect.summary,
            evidenceRefs = prepared.candidate.evidenceRefs
        )
        val authorization = approvals.authorize(intent)
        val token = (authorization as? AgentEffectAuthorization.Allowed)?.token
        if (token == null) {
            return recordPromotionRejection(prepared, authorization)
        }

        return vault.transaction {
            val candidate = requireNotNull(candidate(prepared.candidate.id))
            require(candidate.status == AgentCandidateStatus.WAITING_APPROVAL) {
                "Candidate '${candidate.id}' left the approval state."
            }
            require(candidate.candidateHash == token.argumentHash) {
                "Approval token does not match candidate hash."
            }
            if (!approvals.consume(token, intent)) {
                throw AgentAssetApprovalException(
                    AgentApprovalDecision.UNAVAILABLE,
                    "Promotion approval token expired, changed, or was already consumed."
                )
            }
            val previous = activeRevision(candidate.assetKey)
            if (previous != null) {
                updateRevisionStatus(previous.id, AgentAssetRevisionStatus.SUPERSEDED)
                candidate(previous.candidateId)?.let { previousCandidate ->
                    if (previousCandidate.status == AgentCandidateStatus.PROMOTED) {
                        putCandidate(
                            previousCandidate.copy(
                                status = AgentCandidateStatus.SUPERSEDED,
                                statusReason = "Superseded by candidate '${candidate.id}'.",
                                version = previousCandidate.version + 1
                            ),
                            previousCandidate.version
                        )
                    }
                }
            }
            val revisionNumber = revisions(candidate.assetKey)
                .maxOfOrNull(AgentAssetRevision::revision)
                ?.plus(1L) ?: 1L
            val revision = AgentAssetRevision(
                id = idGenerator.nextId("asset-revision"),
                assetKey = candidate.assetKey,
                kind = candidate.kind,
                revision = revisionNumber,
                title = candidate.title,
                content = candidate.proposedContent,
                candidateId = candidate.id,
                candidateHash = candidate.candidateHash,
                evalReportId = prepared.report.id,
                approvalId = token.approvalId,
                previousRevisionId = previous?.id,
                rollbackPointRevisionId = previous?.id,
                evidenceRefs = candidate.evidenceRefs,
                privacy = candidate.privacy,
                status = AgentAssetRevisionStatus.ACTIVE,
                createdAtEpochMillis = clock.nowEpochMillis()
            )
            putRevision(revision)
            val promoted = candidate.copy(
                status = AgentCandidateStatus.PROMOTED,
                statusReason = "Promoted as revision '${revision.id}'.",
                version = candidate.version + 1
            )
            putCandidate(promoted, candidate.version)
            val effect = prepared.effect.copy(
                toRevisionId = revision.id,
                approvalId = token.approvalId,
                status = AgentStateEffectStatus.APPLIED,
                summary = "Promoted '${candidate.title}' as revision ${revision.revision}.",
                completedAtEpochMillis = clock.nowEpochMillis()
            )
            putEffect(effect)
            appendEvent(
                AgentStateEvent(
                    id = idGenerator.nextId("state-event"),
                    type = "ASSET_PROMOTED",
                    source = "agent-state",
                    summary = effect.summary,
                    runId = candidate.source.runId,
                    sessionId = candidate.source.sessionId,
                    evidenceRefs = candidate.evidenceRefs,
                    privacy = candidate.privacy,
                    createdAtEpochMillis = effect.completedAtEpochMillis!!
                )
            )
            AgentPromotionResult(promoted, revision, effect)
        }
    }

    fun reject(candidateId: String, reason: String): AgentAssetCandidate {
        require(reason.isNotBlank()) { "Candidate rejection reason must not be blank." }
        return transitionTerminal(candidateId, AgentCandidateStatus.REJECTED, reason)
    }

    fun expire(candidateId: String, reason: String = "Candidate expired."): AgentAssetCandidate {
        return transitionTerminal(candidateId, AgentCandidateStatus.EXPIRED, reason)
    }

    fun rollback(
        assetKey: String,
        targetRevisionId: String,
        runId: String,
        sessionId: String,
        reason: String
    ): AgentRollbackResult {
        require(reason.isNotBlank()) { "Rollback reason must not be blank." }
        val currentAndTarget = vault.read {
            val current = requireNotNull(activeRevision(assetKey)) {
                "Asset '$assetKey' has no active revision."
            }
            val target = requireNotNull(revision(targetRevisionId)) {
                "Unknown rollback revision '$targetRevisionId'."
            }
            require(target.assetKey == assetKey) {
                "Rollback revision belongs to '${target.assetKey}', not '$assetKey'."
            }
            require(target.id != current.id) { "Rollback target is already active." }
            current to target
        }
        val (current, target) = currentAndTarget
        val effectId = idGenerator.nextId("asset-effect")
        val argumentHash = CandidateHasher.hashParts(
            listOf(assetKey, current.id, target.id, reason)
        )
        val pending = AgentStateEffect(
            id = effectId,
            kind = "ROLLBACK",
            assetKey = assetKey,
            candidateId = target.candidateId,
            fromRevisionId = current.id,
            toRevisionId = target.id,
            candidateHash = argumentHash,
            evalReportId = target.evalReportId,
            approvalId = null,
            status = AgentStateEffectStatus.PENDING,
            summary = "Rollback '$assetKey' from ${current.revision} to ${target.revision}: $reason",
            createdAtEpochMillis = clock.nowEpochMillis()
        )
        vault.transaction { putEffect(pending) }
        val intent = AgentEffectIntent(
            runId = runId,
            sessionId = sessionId,
            toolCallId = "rollback:$effectId",
            toolName = "agent_asset_rollback",
            capability = DURABLE_ASSET_CAPABILITY,
            targetRef = assetKey,
            argumentHash = argumentHash,
            summary = pending.summary,
            evidenceRefs = target.evidenceRefs
        )
        val authorization = approvals.authorize(intent)
        val token = (authorization as? AgentEffectAuthorization.Allowed)?.token
            ?: throw AgentAssetApprovalException(
                (authorization as? AgentEffectAuthorization.Rejected)?.decision
                    ?: AgentApprovalDecision.UNAVAILABLE,
                "Rollback approval was not granted."
            )
        return vault.transaction {
            val latestCurrent = requireNotNull(activeRevision(assetKey))
            require(latestCurrent.id == current.id) {
                "Asset '$assetKey' changed while rollback was waiting."
            }
            if (!approvals.consume(token, intent)) {
                throw AgentAssetApprovalException(
                    AgentApprovalDecision.UNAVAILABLE,
                    "Rollback approval token expired, changed, or was already consumed."
                )
            }
            updateRevisionStatus(current.id, AgentAssetRevisionStatus.ROLLED_BACK)
            updateRevisionStatus(target.id, AgentAssetRevisionStatus.ACTIVE)
            candidate(current.candidateId)?.let { activeCandidate ->
                if (activeCandidate.status == AgentCandidateStatus.PROMOTED) {
                    putCandidate(
                        activeCandidate.copy(
                            status = AgentCandidateStatus.ROLLED_BACK,
                            statusReason = "Rolled back to revision '${target.id}'.",
                            version = activeCandidate.version + 1
                        ),
                        activeCandidate.version
                    )
                }
            }
            val applied = pending.copy(
                approvalId = token.approvalId,
                status = AgentStateEffectStatus.APPLIED,
                completedAtEpochMillis = clock.nowEpochMillis()
            )
            putEffect(applied)
            appendEvent(
                AgentStateEvent(
                    id = idGenerator.nextId("state-event"),
                    type = "ASSET_ROLLED_BACK",
                    source = "agent-state",
                    summary = applied.summary,
                    runId = runId,
                    sessionId = sessionId,
                    evidenceRefs = target.evidenceRefs,
                    privacy = target.privacy,
                    createdAtEpochMillis = applied.completedAtEpochMillis!!
                )
            )
            AgentRollbackResult(
                activeRevision = requireNotNull(revision(target.id)),
                effect = applied
            )
        }
    }

    fun recordPsycheObservation(
        dimension: PersonaDimension,
        observation: String,
        sourceRunId: String?,
        sourceSessionId: String?,
        evidenceRefs: List<String>,
        confidence: Double
    ): PsycheObservation {
        require(observation.isNotBlank()) { "Psyche observation must not be blank." }
        rejectCredential(observation)
        val value = PsycheObservation(
            id = idGenerator.nextId("psyche"),
            dimension = dimension,
            observation = observation,
            sourceRunId = sourceRunId,
            sourceSessionId = sourceSessionId,
            evidenceRefs = evidenceRefs.distinct(),
            confidence = confidence,
            observedAtEpochMillis = clock.nowEpochMillis()
        )
        vault.transaction { putPsycheObservation(value) }
        return value
    }

    fun inbox(
        kind: AgentAssetKind? = null
    ): List<AgentAssetCandidate> = vault.read {
        candidates(kind, INBOX_STATUSES)
    }

    private fun propose(candidate: AgentAssetCandidate): CandidateReceipt {
        return vault.transaction {
            val duplicate = candidates(candidate.kind, INBOX_STATUSES)
                .firstOrNull { existing ->
                    existing.dedupeKey == candidate.dedupeKey &&
                        existing.candidateHash == candidate.candidateHash
                }
            if (duplicate != null) {
                return@transaction CandidateReceipt(
                    duplicate.id,
                    duplicate.candidateHash,
                    duplicate.status,
                    duplicateOf = duplicate.id
                )
            }
            putCandidate(candidate)
            appendEvent(
                AgentStateEvent(
                    id = idGenerator.nextId("state-event"),
                    type = "ASSET_CANDIDATE_PROPOSED",
                    source = candidate.source.author,
                    summary = "Proposed ${candidate.kind.name.lowercase()} '${candidate.title}'.",
                    runId = candidate.source.runId,
                    sessionId = candidate.source.sessionId,
                    evidenceRefs = candidate.evidenceRefs,
                    privacy = candidate.privacy,
                    createdAtEpochMillis = candidate.createdAtEpochMillis
                )
            )
            CandidateReceipt(candidate.id, candidate.candidateHash, candidate.status)
        }
    }

    private fun recordPromotionRejection(
        prepared: PreparedPromotion,
        authorization: AgentEffectAuthorization
    ): AgentPromotionResult {
        val decision = (authorization as? AgentEffectAuthorization.Rejected)?.decision
            ?: AgentApprovalDecision.UNAVAILABLE
        return vault.transaction {
            val current = requireNotNull(candidate(prepared.candidate.id))
            val status = when (decision) {
                AgentApprovalDecision.TIMEOUT -> AgentCandidateStatus.EXPIRED
                AgentApprovalDecision.UNAVAILABLE -> AgentCandidateStatus.WAITING_APPROVAL
                AgentApprovalDecision.DENIED,
                AgentApprovalDecision.APPROVED -> AgentCandidateStatus.REJECTED
            }
            val next = current.copy(
                status = status,
                statusReason = "Promotion approval ${decision.name.lowercase()}.",
                version = current.version + 1
            )
            putCandidate(next, current.version)
            val effect = prepared.effect.copy(
                status = when (decision) {
                    AgentApprovalDecision.DENIED -> AgentStateEffectStatus.DENIED
                    else -> AgentStateEffectStatus.FAILED
                },
                summary = "Promotion not applied: approval ${decision.name.lowercase()}.",
                completedAtEpochMillis = clock.nowEpochMillis()
            )
            putEffect(effect)
            AgentPromotionResult(next, null, effect)
        }
    }

    private fun transitionTerminal(
        candidateId: String,
        status: AgentCandidateStatus,
        reason: String
    ): AgentAssetCandidate = vault.transaction {
        val candidate = requireNotNull(candidate(candidateId)) {
            "Unknown candidate '$candidateId'."
        }
        require(candidate.status in INBOX_STATUSES) {
            "Candidate '$candidateId' is already terminal."
        }
        val next = candidate.copy(
            status = status,
            statusReason = reason,
            version = candidate.version + 1
        )
        putCandidate(next, candidate.version)
        next
    }

    private fun rejectCredential(text: String) {
        require(!CandidateSafety.containsCredential(text)) {
            "Credential-like content cannot enter Agent State Vault."
        }
    }

    private data class PreparedPromotion(
        val candidate: AgentAssetCandidate,
        val report: AgentEvalReport,
        val effect: AgentStateEffect
    )

    companion object {
        private const val UNCOMPUTED_HASH = "uncomputed"

        val INBOX_STATUSES = setOf(
            AgentCandidateStatus.PROPOSED,
            AgentCandidateStatus.VALIDATED,
            AgentCandidateStatus.EVALUATED,
            AgentCandidateStatus.WAITING_APPROVAL
        )

        val DURABLE_ASSET_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DURABLE_WRITE,
            risk = AgentToolRisk.MEDIUM,
            dataScopes = setOf("agent-state"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY,
            targetArgumentNames = setOf("assetKey")
        )
    }
}

class AgentAssetApprovalException(
    val decision: AgentApprovalDecision,
    message: String
) : IllegalStateException(message)

object CandidateHasher {
    fun hashMemory(candidate: MemoryCandidate): String = hashParts(
        listOf(
            "MEMORY",
            candidate.type.name,
            candidate.proposedText.trim(),
            candidate.trust.name,
            candidate.confidence.toString(),
            candidate.privacy.name,
            candidate.targetScope,
            candidate.dedupeKey,
            candidate.ttlMillis?.toString().orEmpty()
        ) + candidate.evidenceRefs.sorted() + candidate.conflictRefs.sorted()
    )

    fun hashSkill(draft: SkillDraft): String = hashParts(
        listOf(
            "SKILL",
            draft.skillId,
            draft.name.trim(),
            draft.description.trim(),
            draft.content.trim(),
            draft.baseRevisionId.orEmpty(),
            draft.diff,
            draft.privacy.name
        ) + draft.evidenceRefs.sorted() + draft.capabilityClaims.sorted()
    )

    fun hashPersona(proposal: PersonaProposal): String = hashParts(
        listOf(
            "PERSONA",
            proposal.dimension.name,
            proposal.proposedText.trim(),
            proposal.confidence.toString(),
            proposal.observationWindow,
            proposal.baseRevisionId.orEmpty(),
            proposal.privacy.name
        ) + proposal.evidenceRefs.sorted() + proposal.conflictRefs.sorted()
    )

    fun hash(candidate: AgentAssetCandidate): String = hashParts(
        listOf(
            candidate.kind.name,
            candidate.assetKey.removePrefix(
                when (candidate.kind) {
                    AgentAssetKind.MEMORY -> "memory:"
                    AgentAssetKind.SKILL -> "skill:"
                    AgentAssetKind.PERSONA -> "persona:"
                    AgentAssetKind.HOUSE_CORE -> "house:"
                    AgentAssetKind.PROMPT_OVERLAY -> "prompt-overlay:"
                    AgentAssetKind.EVALUATOR_CASE -> "evaluator-case:"
                }
            ),
            candidate.title,
            candidate.proposedContent,
            candidate.trust.name,
            candidate.confidence.toString(),
            candidate.privacy.name,
            candidate.dedupeKey,
            candidate.baseRevisionId.orEmpty(),
            candidate.diff,
            candidate.memoryType?.name.orEmpty(),
            candidate.personaDimension?.name.orEmpty(),
            candidate.expiresAtEpochMillis?.let {
                (it - candidate.createdAtEpochMillis).toString()
            }.orEmpty()
        ) + candidate.evidenceRefs.sorted() +
            candidate.conflictRefs.sorted() +
            candidate.capabilityClaims.sorted()
    )

    fun hashParts(parts: List<String>): String {
        val canonical = buildString {
            parts.forEach { part ->
                append(part.length)
                append(':')
                append(part)
                append('|')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal object CandidateSafety {
    val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val credentialPatterns = listOf(
        Regex("(?i)\\bapi[ _-]?key\\b\\s*[:=]"),
        Regex("(?i)\\b(access|refresh)[ _-]?token\\b\\s*[:=]"),
        Regex("(?i)\\bpassword\\b\\s*[:=]"),
        Regex("(?i)\\bbearer\\s+[a-z0-9._~+/-]{16,}")
    )

    fun containsCredential(text: String): Boolean =
        credentialPatterns.any { pattern -> pattern.containsMatchIn(text) }
}
