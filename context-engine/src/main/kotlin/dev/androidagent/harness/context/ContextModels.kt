// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.context

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentSession

enum class ContextTaskType {
    CHAT,
    DEVICE,
    LOCAL_WRITE,
    MEMORY,
    RESEARCH,
    BACKGROUND,
    DIAGNOSTIC
}

enum class ContextTrust(val authorityRank: Int) {
    HOST_POLICY(800),
    USER_CONFIRMED(700),
    CURRENT_USER_INPUT(650),
    APPLICATION_STATE(600),
    TOOL_OBSERVED(500),
    AGENT_PROPOSED(300),
    EXTERNAL_UNTRUSTED(100),
    MODEL_INFERRED(50)
}

enum class ContextPrivacy(val sensitivityRank: Int) {
    PUBLIC(0),
    INTERNAL(1),
    SENSITIVE(2),
    RESTRICTED(3)
}

enum class ContextRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class ContextRiskFlag {
    PROMPT_INJECTION_POSSIBLE,
    SENSITIVE_LOCAL_DATA,
    STALE,
    CONFLICTS_WITH_NEWER_FACT,
    REQUIRES_CONFIRMATION,
    EXTERNAL_INSTRUCTION,
    DERIVED_BY_MODEL,
    RETENTION_RESTRICTED
}

data class ContextTimeRange(
    val fromEpochMillis: Long?,
    val toEpochMillis: Long?
) {
    init {
        require(
            fromEpochMillis == null ||
                toEpochMillis == null ||
                fromEpochMillis <= toEpochMillis
        ) {
            "Context time range start cannot exceed end."
        }
    }
}

data class ContextNeedSpec(
    val taskType: ContextTaskType,
    val goal: String,
    val entities: List<String> = emptyList(),
    val timeRange: ContextTimeRange? = null,
    val requestedSourceIds: Set<String> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val riskLevel: ContextRiskLevel = ContextRiskLevel.LOW,
    val privacyCeiling: ContextPrivacy = ContextPrivacy.INTERNAL,
    val tokenBudget: Int = 4_000,
    val outputReserve: Int = 1_000,
    val maxItems: Int = 16
) {
    init {
        require(goal.isNotBlank()) { "Context goal must not be blank." }
        require(entities.none(String::isBlank)) { "Context entities must not be blank." }
        require(requestedSourceIds.none(String::isBlank)) {
            "Requested context source ids must not be blank."
        }
        require(requiredCapabilities.none(String::isBlank)) {
            "Required context capabilities must not be blank."
        }
        require(tokenBudget > 0) { "Context token budget must be positive." }
        require(outputReserve >= 0 && outputReserve < tokenBudget) {
            "Context output reserve must be non-negative and below the token budget."
        }
        require(maxItems in 1..256) { "Context max items must be between 1 and 256." }
    }

    val inputTokenBudget: Int
        get() = tokenBudget - outputReserve
}

data class ContextEngineRequest(
    val session: AgentSession,
    val userInput: String,
    val taskType: ContextTaskType = ContextTaskType.CHAT,
    val trigger: String = "USER",
    val requestedSourceIds: Set<String> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val entities: List<String> = emptyList(),
    val timeRange: ContextTimeRange? = null,
    val riskLevel: ContextRiskLevel = ContextRiskLevel.LOW,
    val privacyCeiling: ContextPrivacy = ContextPrivacy.INTERNAL,
    val tokenBudget: Int = 4_000,
    val outputReserve: Int = 1_000,
    val maxItems: Int = 16,
    val nowEpochMillis: Long = System.currentTimeMillis()
) {
    init {
        require(userInput.isNotBlank()) { "Context engine user input must not be blank." }
        require(trigger.isNotBlank()) { "Context trigger must not be blank." }
    }
}

data class ContextCandidate(
    val id: String,
    val logicalId: String = id,
    val sourceId: String,
    val sourceRevision: Long = 0,
    val title: String,
    val body: String,
    val trust: ContextTrust,
    val privacy: ContextPrivacy = ContextPrivacy.INTERNAL,
    val riskFlags: Set<ContextRiskFlag> = emptySet(),
    val createdAtEpochMillis: Long,
    val validFromEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
    val evidenceRefs: List<String> = emptyList(),
    val requiredCapabilities: Set<String> = emptySet(),
    val estimatedTokens: Int = 0,
    val relevance: Int = 0,
    val critical: Boolean = false,
    val conflictKey: String? = null
) {
    init {
        require(id.isNotBlank()) { "Context candidate id must not be blank." }
        require(logicalId.isNotBlank()) { "Context candidate logical id must not be blank." }
        require(sourceId.isNotBlank()) { "Context candidate source id must not be blank." }
        require(sourceRevision >= 0) { "Context source revision must not be negative." }
        require(title.isNotBlank()) { "Context candidate title must not be blank." }
        require(body.isNotBlank()) { "Context candidate body must not be blank." }
        require(evidenceRefs.none(String::isBlank)) {
            "Context evidence refs must not be blank."
        }
        require(requiredCapabilities.none(String::isBlank)) {
            "Context candidate capabilities must not be blank."
        }
        require(estimatedTokens >= 0) { "Estimated context tokens must not be negative." }
        require(relevance in 0..1_000) { "Context relevance must be between 0 and 1000." }
        require(conflictKey == null || conflictKey.isNotBlank()) {
            "Context conflict key must not be blank."
        }
        require(
            validFromEpochMillis == null ||
                validUntilEpochMillis == null ||
                validFromEpochMillis <= validUntilEpochMillis
        ) {
            "Context validity start cannot exceed end."
        }
    }

    fun tokenCost(): Int = if (estimatedTokens > 0) {
        estimatedTokens
    } else {
        maxOf(1, (title.length + body.length + 3) / 4)
    }
}

fun interface ContextSource {
    fun collect(request: ContextEngineRequest, need: ContextNeedSpec): List<ContextCandidate>
}

data class NamedContextSource(
    val id: String,
    val source: ContextSource
) {
    init {
        require(id.isNotBlank()) { "Context source id must not be blank." }
    }
}

enum class ContextDropReason {
    DUPLICATE_ID,
    SOURCE_NOT_REQUESTED,
    PRIVACY_CEILING,
    CAPABILITY_UNAVAILABLE,
    NOT_YET_VALID,
    EXPIRED,
    SUPERSEDED,
    CONFLICT_LOST,
    ITEM_LIMIT,
    TOKEN_BUDGET,
    SOURCE_FAILED
}

data class DroppedContextCandidate(
    val candidateId: String,
    val sourceId: String,
    val reason: ContextDropReason,
    val critical: Boolean,
    val detail: String
)

data class EvidenceItem(
    val id: String,
    val logicalId: String,
    val sourceId: String,
    val sourceRevision: Long,
    val title: String,
    val body: String,
    val trust: ContextTrust,
    val privacy: ContextPrivacy,
    val riskFlags: Set<ContextRiskFlag>,
    val createdAtEpochMillis: Long,
    val evidenceRefs: List<String>,
    val tokenCost: Int,
    val selectionReason: String,
    val critical: Boolean
)

data class EvidencePack(
    val need: ContextNeedSpec,
    val items: List<EvidenceItem>,
    val dropped: List<DroppedContextCandidate>,
    val usedTokens: Int,
    val availableTokens: Int
) {
    val droppedCritical: List<DroppedContextCandidate>
        get() = dropped.filter(DroppedContextCandidate::critical)
}

enum class ContextRouteAction {
    LOCAL_REPLY,
    CONTINUE_PROVIDER,
    ASK_USER,
    BLOCK
}

data class ContextRouteDecision(
    val action: ContextRouteAction,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "Context route reason must not be blank." }
    }
}

data class PromptBudgetReport(
    val selectedIds: List<String>,
    val compressedIds: List<String>,
    val filteredIds: List<String>,
    val droppedIds: List<String>,
    val usedTokens: Int,
    val availableTokens: Int
)

data class PromptBundle(
    val contextItems: List<AgentContextItem>,
    val budgetReport: PromptBudgetReport
)

data class ContextCompilation(
    val need: ContextNeedSpec,
    val evidencePack: EvidencePack,
    val route: ContextRouteDecision,
    val promptBundle: PromptBundle
)
