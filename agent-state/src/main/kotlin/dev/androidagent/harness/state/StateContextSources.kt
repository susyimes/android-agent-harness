// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.AgentIdGenerator
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.UuidAgentIdGenerator
import dev.androidagent.harness.context.ContextCandidate
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextNeedSpec
import dev.androidagent.harness.context.ContextRiskFlag
import dev.androidagent.harness.context.ContextSource
import dev.androidagent.harness.context.ContextTrust

/**
 * Projects only active, approved revisions into CCP. Candidate inboxes,
 * evaluations, psyche observations, and superseded revisions never enter the
 * prompt through this source.
 */
class AgentApprovedStateContextSource(
    private val vault: AgentStateVault,
    private val sourceId: String = "agent-state-approved"
) : ContextSource {
    override fun collect(
        request: ContextEngineRequest,
        need: ContextNeedSpec
    ): List<ContextCandidate> {
        return vault.read {
            revisions()
                .filter { revision -> revision.status == AgentAssetRevisionStatus.ACTIVE }
                .filter { revision -> revision.kind in CONTEXT_ASSET_KINDS }
                .filterNot { revision -> revision.assetKey == "house:psyche" }
                .sortedWith(
                    compareBy<AgentAssetRevision> { revision -> kindPriority(revision.kind) }
                        .thenBy { revision -> revision.assetKey }
                )
                .map { revision ->
                    ContextCandidate(
                        id = "approved:${revision.id}",
                        logicalId = revision.assetKey,
                        sourceId = sourceId,
                        sourceRevision = revision.revision,
                        title = revision.title,
                        body = revision.content,
                        trust = ContextTrust.USER_CONFIRMED,
                        privacy = revision.privacy,
                        riskFlags = emptySet(),
                        createdAtEpochMillis = revision.createdAtEpochMillis,
                        evidenceRefs = revision.evidenceRefs,
                        estimatedTokens = 0,
                        relevance = relevance(revision.kind, need),
                        critical = revision.kind == AgentAssetKind.HOUSE_CORE &&
                            revision.assetKey in CRITICAL_HOUSE_KEYS,
                        conflictKey = revision.assetKey
                    )
                }
        }
    }

    private fun relevance(kind: AgentAssetKind, need: ContextNeedSpec): Int = when (kind) {
        AgentAssetKind.HOUSE_CORE -> 900
        AgentAssetKind.PERSONA -> 850
        AgentAssetKind.MEMORY -> if (need.taskType.name == "MEMORY") 900 else 700
        AgentAssetKind.SKILL -> 650
        AgentAssetKind.PROMPT_OVERLAY -> 600
        AgentAssetKind.EVALUATOR_CASE -> 0
    }

    private fun kindPriority(kind: AgentAssetKind): Int = when (kind) {
        AgentAssetKind.HOUSE_CORE -> 0
        AgentAssetKind.PERSONA -> 1
        AgentAssetKind.MEMORY -> 2
        AgentAssetKind.SKILL -> 3
        AgentAssetKind.PROMPT_OVERLAY -> 4
        AgentAssetKind.EVALUATOR_CASE -> 5
    }

    private companion object {
        val CONTEXT_ASSET_KINDS = setOf(
            AgentAssetKind.MEMORY,
            AgentAssetKind.SKILL,
            AgentAssetKind.PERSONA,
            AgentAssetKind.HOUSE_CORE,
            AgentAssetKind.PROMPT_OVERLAY
        )
        val CRITICAL_HOUSE_KEYS = setOf("house:rules", "house:soul", "house:user")
    }
}

/**
 * Optional source for explicitly selected State Vault collections. This source
 * never exposes candidate/eval/effect internals and marks model-derived state.
 */
class AgentVaultDocumentContextSource(
    private val vault: AgentStateVault,
    private val allowedCollections: Set<AgentStateCollection> = DEFAULT_COLLECTIONS,
    private val sourceId: String = "agent-state-documents"
) : ContextSource {
    override fun collect(
        request: ContextEngineRequest,
        need: ContextNeedSpec
    ): List<ContextCandidate> {
        return vault.read {
            documents()
                .filterNot(AgentStateDocument::tombstone)
                .filter { document -> document.collection in allowedCollections }
                .map { document ->
                    ContextCandidate(
                        id = "state:${document.collection.name}:${document.id}:${document.revision}",
                        logicalId = "state:${document.collection.name}:${document.id}",
                        sourceId = sourceId,
                        sourceRevision = document.revision,
                        title = document.title,
                        body = document.content,
                        trust = trust(document),
                        privacy = document.privacy,
                        riskFlags = if (document.source.startsWith("model:")) {
                            setOf(ContextRiskFlag.DERIVED_BY_MODEL)
                        } else {
                            emptySet()
                        },
                        createdAtEpochMillis = document.updatedAtEpochMillis,
                        evidenceRefs = document.evidenceRefs,
                        relevance = 500,
                        conflictKey = "state:${document.collection.name}:${document.id}"
                    )
                }
        }
    }

    private fun trust(document: AgentStateDocument): ContextTrust = when {
        document.source.startsWith("user:") -> ContextTrust.USER_CONFIRMED
        document.source.startsWith("host:") -> ContextTrust.HOST_POLICY
        document.source.startsWith("tool:") -> ContextTrust.TOOL_OBSERVED
        document.source.startsWith("model:") -> ContextTrust.MODEL_INFERRED
        else -> ContextTrust.APPLICATION_STATE
    }

    companion object {
        val DEFAULT_COLLECTIONS = setOf(
            AgentStateCollection.IDENTITY,
            AgentStateCollection.CURRENT_STATE,
            AgentStateCollection.CAPABILITIES,
            AgentStateCollection.PERMISSIONS,
            AgentStateCollection.OPEN_LOOPS,
            AgentStateCollection.BRIEFS
        )
    }
}

class AgentBriefCompiler(
    private val vault: AgentStateVault,
    private val clock: AgentClock = SystemAgentClock,
    private val idGenerator: AgentIdGenerator = UuidAgentIdGenerator()
) {
    fun compile(
        title: String = "Agent Brief",
        maxEvents: Int = 12,
        maxEvidence: Int = 12,
        maxOpenLoops: Int = 12,
        validityMillis: Long? = null
    ): AgentBrief {
        require(title.isNotBlank()) { "Brief title must not be blank." }
        require(maxEvents in 0..256 && maxEvidence in 0..256 && maxOpenLoops in 0..256) {
            "Brief list limits must be between 0 and 256."
        }
        require(validityMillis == null || validityMillis > 0) {
            "Brief validity must be positive."
        }
        return vault.transaction {
            val eventRefs = events().takeLast(maxEvents).map(AgentStateEvent::id)
            val evidenceRefs = evidence().takeLast(maxEvidence).map(AgentStateEvidence::id)
            val openLoops = documents(AgentStateCollection.OPEN_LOOPS)
                .filterNot(AgentStateDocument::tombstone)
                .takeLast(maxOpenLoops)
                .map(AgentStateDocument::id)
            val pending = candidates(statuses = AgentAssetGovernance.INBOX_STATUSES)
                .map(AgentAssetCandidate::id)
            val now = clock.nowEpochMillis()
            val brief = AgentBrief(
                id = idGenerator.nextId("brief"),
                title = title,
                summary = buildString {
                    append("${eventRefs.size} recent events, ")
                    append("${evidenceRefs.size} evidence items, ")
                    append("${openLoops.size} open loops, ")
                    append("${pending.size} pending asset candidates.")
                },
                eventRefs = eventRefs,
                evidenceRefs = evidenceRefs,
                openLoopRefs = openLoops,
                pendingCandidateRefs = pending,
                createdAtEpochMillis = now,
                validUntilEpochMillis = validityMillis?.let(now::plus)
            )
            putBrief(brief)
            writeDocument(
                AgentStateDocumentWrite(
                    id = brief.id,
                    collection = AgentStateCollection.BRIEFS,
                    title = brief.title,
                    content = brief.summary,
                    source = "host:agent-brief-compiler",
                    evidenceRefs = brief.evidenceRefs,
                    metadata = mapOf(
                        "eventCount" to brief.eventRefs.size.toString(),
                        "openLoopCount" to brief.openLoopRefs.size.toString(),
                        "pendingCandidateCount" to brief.pendingCandidateRefs.size.toString()
                    )
                )
            )
            brief
        }
    }
}
