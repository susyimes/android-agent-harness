// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.approval

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.AgentIdGenerator
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.UuidAgentIdGenerator
import java.util.concurrent.ConcurrentHashMap

enum class AgentApprovalDecision {
    APPROVED,
    DENIED,
    TIMEOUT,
    UNAVAILABLE
}

enum class AgentApprovalRequirement {
    NOT_REQUIRED,
    REQUIRED,
    FORBIDDEN
}

data class AgentEffectIntent(
    val runId: String,
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val capability: AgentToolCapability,
    val targetRef: String?,
    val argumentHash: String,
    val summary: String,
    val evidenceRefs: List<String> = emptyList()
) {
    init {
        require(runId.isNotBlank()) { "Effect run id must not be blank." }
        require(sessionId.isNotBlank()) { "Effect session id must not be blank." }
        require(toolCallId.isNotBlank()) { "Effect tool call id must not be blank." }
        require(toolName.isNotBlank()) { "Effect tool name must not be blank." }
        require(targetRef == null || targetRef.isNotBlank()) {
            "Effect target reference must not be blank."
        }
        require(argumentHash.isNotBlank()) { "Effect argument hash must not be blank." }
        require(summary.isNotBlank()) { "Effect summary must not be blank." }
        require(evidenceRefs.none(String::isBlank)) { "Effect evidence refs must not be blank." }
    }
}

data class AgentApprovalRequest(
    val id: String,
    val runId: String,
    val sessionId: String,
    val toolCallId: String,
    val capabilityId: String,
    val risk: AgentToolRisk,
    val effectSummary: String,
    val targetRef: String?,
    val argumentHash: String,
    val evidenceRefs: List<String>,
    val expiresAtEpochMillis: Long
) {
    init {
        require(id.isNotBlank()) { "Approval id must not be blank." }
        require(runId.isNotBlank()) { "Approval run id must not be blank." }
        require(sessionId.isNotBlank()) { "Approval session id must not be blank." }
        require(toolCallId.isNotBlank()) { "Approval tool call id must not be blank." }
        require(capabilityId.isNotBlank()) { "Approval capability id must not be blank." }
        require(effectSummary.isNotBlank()) { "Approval effect summary must not be blank." }
        require(targetRef == null || targetRef.isNotBlank()) {
            "Approval target reference must not be blank."
        }
        require(argumentHash.isNotBlank()) { "Approval argument hash must not be blank." }
        require(evidenceRefs.none(String::isBlank)) { "Approval evidence refs must not be blank." }
    }
}

fun interface AgentApprovalGate {
    fun decide(request: AgentApprovalRequest): AgentApprovalDecision

    companion object {
        val DENY: AgentApprovalGate = AgentApprovalGate { AgentApprovalDecision.DENIED }
    }
}

fun interface AgentApprovalPolicy {
    fun requirement(intent: AgentEffectIntent): AgentApprovalRequirement

    companion object {
        /**
         * Reads and local drafts can proceed under host policy. Durable,
         * external, and device effects require a concrete approval.
         */
        fun conservative(): AgentApprovalPolicy = AgentApprovalPolicy { intent ->
            when (intent.capability.sideEffect) {
                AgentToolSideEffect.NONE,
                AgentToolSideEffect.LOCAL_READ,
                AgentToolSideEffect.LOCAL_DRAFT_WRITE -> AgentApprovalRequirement.NOT_REQUIRED

                AgentToolSideEffect.LOCAL_DURABLE_WRITE,
                AgentToolSideEffect.EXTERNAL_WRITE,
                AgentToolSideEffect.DEVICE_ACTION -> AgentApprovalRequirement.REQUIRED
            }
        }
    }
}

data class AgentApprovalToken(
    val approvalId: String,
    val runId: String,
    val sessionId: String,
    val toolCallId: String,
    val argumentHash: String,
    val intentHash: String,
    val grantedScope: String,
    val expiresAtEpochMillis: Long
) {
    init {
        require(approvalId.isNotBlank()) { "Approval token id must not be blank." }
        require(runId.isNotBlank()) { "Approval token run id must not be blank." }
        require(sessionId.isNotBlank()) { "Approval token session id must not be blank." }
        require(toolCallId.isNotBlank()) { "Approval token tool call id must not be blank." }
        require(argumentHash.isNotBlank()) { "Approval token argument hash must not be blank." }
        require(intentHash.isNotBlank()) { "Approval token intent hash must not be blank." }
        require(grantedScope.isNotBlank()) { "Approval token scope must not be blank." }
    }

    fun isValidFor(intent: AgentEffectIntent, nowEpochMillis: Long): Boolean {
        return runId == intent.runId &&
            sessionId == intent.sessionId &&
            toolCallId == intent.toolCallId &&
            argumentHash == intent.argumentHash &&
            intentHash == AgentEffectHasher.hashIntent(intent) &&
            grantedScope == intent.capability.sideEffect.name &&
            nowEpochMillis <= expiresAtEpochMillis
    }
}

sealed interface AgentEffectAuthorization {
    data class Allowed(val token: AgentApprovalToken?) : AgentEffectAuthorization

    data class Rejected(
        val decision: AgentApprovalDecision,
        val message: String
    ) : AgentEffectAuthorization {
        init {
            require(decision != AgentApprovalDecision.APPROVED) {
                "A rejected authorization cannot carry APPROVED."
            }
            require(message.isNotBlank()) { "Approval rejection message must not be blank." }
        }
    }
}

data class AgentApprovalRecord(
    val request: AgentApprovalRequest?,
    val intent: AgentEffectIntent,
    val requirement: AgentApprovalRequirement,
    val decision: AgentApprovalDecision?,
    val decidedAtEpochMillis: Long
)

fun interface AgentApprovalJournal {
    fun record(record: AgentApprovalRecord)

    companion object {
        val NONE = AgentApprovalJournal {}
    }
}

interface AgentApprovalObserver {
    fun onRequested(request: AgentApprovalRequest)

    fun onResolved(
        request: AgentApprovalRequest,
        decision: AgentApprovalDecision
    )

    companion object {
        val NONE: AgentApprovalObserver = object : AgentApprovalObserver {
            override fun onRequested(request: AgentApprovalRequest) = Unit

            override fun onResolved(
                request: AgentApprovalRequest,
                decision: AgentApprovalDecision
            ) = Unit
        }
    }
}

class InMemoryAgentApprovalJournal : AgentApprovalJournal {
    private val records = mutableListOf<AgentApprovalRecord>()

    @Synchronized
    override fun record(record: AgentApprovalRecord) {
        records += record
    }

    @Synchronized
    fun snapshot(): List<AgentApprovalRecord> = records.toList()

    @Synchronized
    fun clear(): Int {
        val count = records.size
        records.clear()
        return count
    }
}

class AgentApprovalCoordinator(
    private val gate: AgentApprovalGate = AgentApprovalGate.DENY,
    private val policy: AgentApprovalPolicy = AgentApprovalPolicy.conservative(),
    private val journal: AgentApprovalJournal = AgentApprovalJournal.NONE,
    private val clock: AgentClock = SystemAgentClock,
    private val idGenerator: AgentIdGenerator = UuidAgentIdGenerator(),
    private val requestTtlMillis: Long = DEFAULT_REQUEST_TTL_MILLIS,
    private val observer: AgentApprovalObserver = AgentApprovalObserver.NONE
) {
    private var tokenLedger = AgentApprovalTokenLedger()

    private constructor(
        gate: AgentApprovalGate,
        policy: AgentApprovalPolicy,
        journal: AgentApprovalJournal,
        clock: AgentClock,
        idGenerator: AgentIdGenerator,
        requestTtlMillis: Long,
        observer: AgentApprovalObserver,
        tokenLedger: AgentApprovalTokenLedger
    ) : this(
        gate = gate,
        policy = policy,
        journal = journal,
        clock = clock,
        idGenerator = idGenerator,
        requestTtlMillis = requestTtlMillis,
        observer = observer
    ) {
        this.tokenLedger = tokenLedger
    }

    init {
        require(requestTtlMillis > 0) { "Approval request TTL must be positive." }
    }

    fun authorize(intent: AgentEffectIntent): AgentEffectAuthorization {
        val requirement = try {
            policy.requirement(intent)
        } catch (_: RuntimeException) {
            AgentApprovalRequirement.FORBIDDEN
        }
        val now = clock.nowEpochMillis()
        if (requirement == AgentApprovalRequirement.NOT_REQUIRED) {
            journalSafely(
                AgentApprovalRecord(
                    request = null,
                    intent = intent,
                    requirement = requirement,
                    decision = null,
                    decidedAtEpochMillis = now
                )
            )
            return AgentEffectAuthorization.Allowed(
                token = issueToken(
                    intent = intent,
                    approvalId = idGenerator.nextId("policy-grant"),
                    expiresAtEpochMillis = now + requestTtlMillis
                )
            )
        }
        if (requirement == AgentApprovalRequirement.FORBIDDEN) {
            val rejected = AgentEffectAuthorization.Rejected(
                AgentApprovalDecision.DENIED,
                "Effect is forbidden by the host approval policy."
            )
            journalSafely(
                AgentApprovalRecord(
                    request = null,
                    intent = intent,
                    requirement = requirement,
                    decision = rejected.decision,
                    decidedAtEpochMillis = now
                )
            )
            return rejected
        }

        val request = AgentApprovalRequest(
            id = idGenerator.nextId("approval"),
            runId = intent.runId,
            sessionId = intent.sessionId,
            toolCallId = intent.toolCallId,
            capabilityId = intent.capability.sideEffect.name,
            risk = intent.capability.risk,
            effectSummary = intent.summary,
            targetRef = intent.targetRef,
            argumentHash = intent.argumentHash,
            evidenceRefs = intent.evidenceRefs,
            expiresAtEpochMillis = now + requestTtlMillis
        )
        observeSafely { observer.onRequested(request) }
        val decision = try {
            gate.decide(request)
        } catch (_: RuntimeException) {
            AgentApprovalDecision.UNAVAILABLE
        }
        val decidedAt = clock.nowEpochMillis()
        val effectiveDecision = if (
            decision == AgentApprovalDecision.APPROVED &&
            decidedAt > request.expiresAtEpochMillis
        ) {
            AgentApprovalDecision.TIMEOUT
        } else {
            decision
        }
        observeSafely { observer.onResolved(request, effectiveDecision) }
        journalSafely(
            AgentApprovalRecord(
                request = request,
                intent = intent,
                requirement = requirement,
                decision = effectiveDecision,
                decidedAtEpochMillis = decidedAt
            )
        )
        if (effectiveDecision != AgentApprovalDecision.APPROVED) {
            return AgentEffectAuthorization.Rejected(
                decision = effectiveDecision,
                message = effectiveDecision.rejectionMessage()
            )
        }
        val token = issueToken(
            intent = intent,
            approvalId = request.id,
            expiresAtEpochMillis = request.expiresAtEpochMillis
        )
        return if (token.isValidFor(intent, decidedAt)) {
            AgentEffectAuthorization.Allowed(token)
        } else {
            AgentEffectAuthorization.Rejected(
                AgentApprovalDecision.UNAVAILABLE,
                "Approval token did not match the pending effect."
            )
        }
    }

    private fun issueToken(
        intent: AgentEffectIntent,
        approvalId: String,
        expiresAtEpochMillis: Long
    ) = AgentApprovalToken(
        approvalId = approvalId,
        runId = intent.runId,
        sessionId = intent.sessionId,
        toolCallId = intent.toolCallId,
        argumentHash = intent.argumentHash,
        intentHash = AgentEffectHasher.hashIntent(intent),
        grantedScope = intent.capability.sideEffect.name,
        expiresAtEpochMillis = expiresAtEpochMillis
    )

    /**
     * Returns an equivalent coordinator that also reports request lifecycle.
     *
     * The gate, policy, journal, clock, id generator, and TTL are preserved.
     * Observer failures never alter the authorization decision.
     */
    fun observedBy(additional: AgentApprovalObserver): AgentApprovalCoordinator {
        val existing = observer
        return AgentApprovalCoordinator(
            gate = gate,
            policy = policy,
            journal = journal,
            clock = clock,
            idGenerator = idGenerator,
            requestTtlMillis = requestTtlMillis,
            observer = object : AgentApprovalObserver {
                override fun onRequested(request: AgentApprovalRequest) {
                    observeSafely { existing.onRequested(request) }
                    observeSafely { additional.onRequested(request) }
                }

                override fun onResolved(
                    request: AgentApprovalRequest,
                    decision: AgentApprovalDecision
                ) {
                    observeSafely { existing.onResolved(request, decision) }
                    observeSafely { additional.onResolved(request, decision) }
                }
            },
            tokenLedger = tokenLedger
        )
    }

    /**
     * Atomically consumes a token immediately before its bound effect.
     *
     * Tokens are one-use even when a caller retains the public value.
     */
    fun consume(
        token: AgentApprovalToken,
        intent: AgentEffectIntent
    ): Boolean {
        if (!token.isValidFor(intent, clock.nowEpochMillis())) return false
        return tokenLedger.consumedApprovalIds.add(token.approvalId)
    }

    fun nowEpochMillis(): Long = clock.nowEpochMillis()

    private fun journalSafely(record: AgentApprovalRecord) {
        try {
            journal.record(record)
        } catch (_: RuntimeException) {
            // Observability cannot grant or revoke an effect.
        }
    }

    private fun observeSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // Approval observability cannot grant or revoke an effect.
        }
    }

    private fun AgentApprovalDecision.rejectionMessage(): String {
        return when (this) {
            AgentApprovalDecision.APPROVED -> "Effect was approved."
            AgentApprovalDecision.DENIED -> "Effect was denied."
            AgentApprovalDecision.TIMEOUT -> "Approval timed out."
            AgentApprovalDecision.UNAVAILABLE -> "Approval surface was unavailable."
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TTL_MILLIS = 60_000L
    }
}

private class AgentApprovalTokenLedger {
    val consumedApprovalIds = ConcurrentHashMap.newKeySet<String>()
}
