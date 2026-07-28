// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.context

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust

fun interface ContextNeedAnalyzer {
    fun analyze(request: ContextEngineRequest): ContextNeedSpec
}

class RuleBasedContextNeedAnalyzer : ContextNeedAnalyzer {
    override fun analyze(request: ContextEngineRequest): ContextNeedSpec {
        return ContextNeedSpec(
            taskType = request.taskType,
            goal = request.userInput.trim(),
            entities = request.entities,
            timeRange = request.timeRange,
            requestedSourceIds = request.requestedSourceIds,
            requiredCapabilities = request.requiredCapabilities,
            riskLevel = request.riskLevel,
            privacyCeiling = request.privacyCeiling,
            tokenBudget = request.tokenBudget,
            outputReserve = request.outputReserve,
            maxItems = request.maxItems
        )
    }
}

fun interface ContextRouteGate {
    fun decide(pack: EvidencePack): ContextRouteDecision
}

class DeterministicContextRouteGate : ContextRouteGate {
    override fun decide(pack: EvidencePack): ContextRouteDecision {
        if (pack.droppedCritical.isNotEmpty()) {
            return ContextRouteDecision(
                ContextRouteAction.ASK_USER,
                "Critical context was unavailable or did not fit the approved budget."
            )
        }
        if (pack.need.riskLevel == ContextRiskLevel.HIGH && pack.items.isEmpty()) {
            return ContextRouteDecision(
                ContextRouteAction.ASK_USER,
                "High-risk work has no usable evidence."
            )
        }
        return ContextRouteDecision(
            ContextRouteAction.CONTINUE_PROVIDER,
            "Selected evidence is within trust, privacy, validity, and budget policy."
        )
    }
}

fun interface PromptBundleRenderer {
    fun render(pack: EvidencePack): PromptBundle
}

class DelimitedPromptBundleRenderer : PromptBundleRenderer {
    override fun render(pack: EvidencePack): PromptBundle {
        val items = pack.items.map { evidence ->
            AgentContextItem(
                id = evidence.id,
                source = evidence.sourceId,
                content = renderEvidence(evidence),
                trust = evidence.trust.toLegacyTrust(),
                priority = evidencePriority(evidence)
            )
        }
        return PromptBundle(
            contextItems = items,
            budgetReport = PromptBudgetReport(
                selectedIds = pack.items.map(EvidenceItem::id),
                compressedIds = emptyList(),
                filteredIds = pack.dropped
                    .filter { dropped ->
                        dropped.reason in setOf(
                            ContextDropReason.PRIVACY_CEILING,
                            ContextDropReason.CAPABILITY_UNAVAILABLE,
                            ContextDropReason.NOT_YET_VALID,
                            ContextDropReason.EXPIRED
                        )
                    }
                    .map(DroppedContextCandidate::candidateId),
                droppedIds = pack.dropped.map(DroppedContextCandidate::candidateId),
                usedTokens = pack.usedTokens,
                availableTokens = pack.availableTokens
            )
        )
    }

    private fun renderEvidence(evidence: EvidenceItem): String {
        val label = if (evidence.trust == ContextTrust.HOST_POLICY) {
            "policy-context"
        } else {
            "context-data"
        }
        val risks = evidence.riskFlags.joinToString(",") { risk -> risk.name.lowercase() }
            .ifBlank { "none" }
        return buildString {
            append('<').append(label)
            append(" id=\"").append(safeAttribute(evidence.id)).append('"')
            append(" trust=\"").append(evidence.trust.name).append('"')
            append(" risks=\"").append(risks).append("\">\n")
            append(evidence.title.trim()).append('\n')
            append(evidence.body.trim()).append('\n')
            append("</").append(label).append('>')
        }
    }

    private fun safeAttribute(value: String): String {
        return value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun evidencePriority(evidence: EvidenceItem): Int {
        val criticalBoost = if (evidence.critical) 10_000 else 0
        return criticalBoost + evidence.trust.authorityRank + minOf(evidence.tokenCost, 100)
    }

    private fun ContextTrust.toLegacyTrust(): AgentContextTrust {
        return when (this) {
            ContextTrust.HOST_POLICY,
            ContextTrust.APPLICATION_STATE -> AgentContextTrust.APPLICATION

            ContextTrust.USER_CONFIRMED,
            ContextTrust.CURRENT_USER_INPUT -> AgentContextTrust.USER

            ContextTrust.TOOL_OBSERVED,
            ContextTrust.AGENT_PROPOSED,
            ContextTrust.MODEL_INFERRED -> AgentContextTrust.AGENT

            ContextTrust.EXTERNAL_UNTRUSTED -> AgentContextTrust.EXTERNAL
        }
    }
}

class CcpV2ContextEngine(
    sources: List<NamedContextSource>,
    private val analyzer: ContextNeedAnalyzer = RuleBasedContextNeedAnalyzer(),
    private val routeGate: ContextRouteGate = DeterministicContextRouteGate(),
    private val renderer: PromptBundleRenderer = DelimitedPromptBundleRenderer()
) {
    private val sources = sources.toList()

    init {
        val duplicateIds = sources.groupingBy(NamedContextSource::id)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Duplicate context source ids: ${duplicateIds.sorted().joinToString()}."
        }
    }

    fun compile(request: ContextEngineRequest): ContextCompilation {
        val need = analyzer.analyze(request)
        val dropped = mutableListOf<DroppedContextCandidate>()
        val collected = collectCandidates(request, need, dropped)
        val filtered = filterCandidates(collected, request, need, dropped)
        val resolved = resolveDuplicatesAndConflicts(filtered, dropped)
        val selected = selectWithinBudget(resolved, need, dropped)
        val usedTokens = selected.sumOf(ContextCandidate::tokenCost)
        val pack = EvidencePack(
            need = need,
            items = selected.map { candidate ->
                EvidenceItem(
                    id = candidate.id,
                    logicalId = candidate.logicalId,
                    sourceId = candidate.sourceId,
                    sourceRevision = candidate.sourceRevision,
                    title = candidate.title,
                    body = candidate.body,
                    trust = candidate.trust,
                    privacy = candidate.privacy,
                    riskFlags = candidate.riskFlags,
                    createdAtEpochMillis = candidate.createdAtEpochMillis,
                    evidenceRefs = candidate.evidenceRefs,
                    tokenCost = candidate.tokenCost(),
                    selectionReason = selectionReason(candidate),
                    critical = candidate.critical
                )
            },
            dropped = dropped.toList(),
            usedTokens = usedTokens,
            availableTokens = need.inputTokenBudget
        )
        return ContextCompilation(
            need = need,
            evidencePack = pack,
            route = routeGate.decide(pack),
            promptBundle = renderer.render(pack)
        )
    }

    private fun collectCandidates(
        request: ContextEngineRequest,
        need: ContextNeedSpec,
        dropped: MutableList<DroppedContextCandidate>
    ): List<ContextCandidate> {
        val candidates = mutableListOf<ContextCandidate>()
        sources.forEach { named ->
            if (need.requestedSourceIds.isNotEmpty() && named.id !in need.requestedSourceIds) {
                return@forEach
            }
            try {
                candidates += named.source.collect(request, need)
            } catch (error: RuntimeException) {
                dropped += DroppedContextCandidate(
                    candidateId = "source:${named.id}",
                    sourceId = named.id,
                    reason = ContextDropReason.SOURCE_FAILED,
                    critical = named.id in need.requestedSourceIds,
                    detail = error.message ?: error.javaClass.simpleName
                )
            }
        }
        return candidates
    }

    private fun filterCandidates(
        candidates: List<ContextCandidate>,
        request: ContextEngineRequest,
        need: ContextNeedSpec,
        dropped: MutableList<DroppedContextCandidate>
    ): List<ContextCandidate> {
        return candidates.filter { candidate ->
            val rejection = when {
                candidate.privacy.sensitivityRank > need.privacyCeiling.sensitivityRank ->
                    ContextDropReason.PRIVACY_CEILING
                !need.requiredCapabilities.containsAll(candidate.requiredCapabilities) ->
                    ContextDropReason.CAPABILITY_UNAVAILABLE
                candidate.validFromEpochMillis?.let { request.nowEpochMillis < it } == true ->
                    ContextDropReason.NOT_YET_VALID
                candidate.validUntilEpochMillis?.let { request.nowEpochMillis > it } == true ->
                    ContextDropReason.EXPIRED
                else -> null
            }
            if (rejection != null) {
                dropped += candidate.drop(
                    rejection,
                    "Candidate failed privacy, capability, or validity policy."
                )
                false
            } else {
                true
            }
        }
    }

    private fun resolveDuplicatesAndConflicts(
        candidates: List<ContextCandidate>,
        dropped: MutableList<DroppedContextCandidate>
    ): List<ContextCandidate> {
        val byId = candidates.groupBy(ContextCandidate::id)
        val unique = byId.values.map { group ->
            val winner = group.maxWith(candidateComparator())
            group.filterNot { it === winner }.forEach { duplicate ->
                dropped += duplicate.drop(
                    ContextDropReason.DUPLICATE_ID,
                    "A higher-authority candidate used the same id."
                )
            }
            winner
        }
        val byLogical = unique.groupBy(ContextCandidate::logicalId)
        val current = byLogical.values.map { group ->
            val winner = group.maxWith(candidateComparator())
            group.filterNot { it === winner }.forEach { old ->
                dropped += old.drop(
                    ContextDropReason.SUPERSEDED,
                    "A newer or higher-authority logical revision was selected."
                )
            }
            winner
        }
        val conflicts = current.groupBy { candidate ->
            candidate.conflictKey ?: "__no_conflict__:${candidate.id}"
        }
        return conflicts.values.map { group ->
            val winner = group.maxWith(candidateComparator())
            group.filterNot { it === winner }.forEach { loser ->
                dropped += loser.drop(
                    ContextDropReason.CONFLICT_LOST,
                    "A higher-authority candidate won the conflict group."
                )
            }
            winner
        }
    }

    private fun selectWithinBudget(
        candidates: List<ContextCandidate>,
        need: ContextNeedSpec,
        dropped: MutableList<DroppedContextCandidate>
    ): List<ContextCandidate> {
        val ordered = candidates.sortedWith(candidateComparator().reversed())
        val selected = mutableListOf<ContextCandidate>()
        var usedTokens = 0
        ordered.forEach { candidate ->
            val reason = when {
                selected.size >= need.maxItems -> ContextDropReason.ITEM_LIMIT
                usedTokens + candidate.tokenCost() > need.inputTokenBudget ->
                    ContextDropReason.TOKEN_BUDGET
                else -> null
            }
            if (reason == null) {
                selected += candidate
                usedTokens += candidate.tokenCost()
            } else {
                dropped += candidate.drop(
                    reason,
                    "Candidate did not fit the item or token budget."
                )
            }
        }
        return selected
    }

    private fun candidateComparator(): Comparator<ContextCandidate> {
        return compareBy<ContextCandidate>(
            ContextCandidate::critical,
            { candidate -> candidate.trust.authorityRank },
            ContextCandidate::relevance,
            ContextCandidate::sourceRevision,
            ContextCandidate::createdAtEpochMillis,
            ContextCandidate::id
        )
    }

    private fun selectionReason(candidate: ContextCandidate): String {
        return buildString {
            append("selected authority=").append(candidate.trust.name)
            append(" relevance=").append(candidate.relevance)
            append(" revision=").append(candidate.sourceRevision)
            if (candidate.critical) append(" critical=true")
        }
    }

    private fun ContextCandidate.drop(
        reason: ContextDropReason,
        detail: String
    ): DroppedContextCandidate {
        return DroppedContextCandidate(
            candidateId = id,
            sourceId = sourceId,
            reason = reason,
            critical = critical,
            detail = detail
        )
    }
}
