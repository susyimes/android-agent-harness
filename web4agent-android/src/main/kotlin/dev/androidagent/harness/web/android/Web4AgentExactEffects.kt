// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

/**
 * Host-issued binding copied from an observe or inspect result.
 *
 * The values are model-visible, but only the session's private observation
 * ledger can turn them into a one-use effect lease.
 */
internal data class Web4AgentExpectedBinding(
    val pageEpoch: Long,
    val observationId: String,
    val targetFingerprint: String?
)

internal enum class Web4AgentEffectKind {
    EVALUATE,
    ACTION
}

internal data class Web4AgentPreparedEffect(
    val leaseId: String,
    val sessionId: String,
    val kind: Web4AgentEffectKind,
    val pageEpoch: Long,
    val observationId: String,
    val documentFingerprint: String,
    val targetFingerprint: String?,
    val documentMaterial: String,
    val targetMaterial: String?,
    val createdAtEpochMillis: Long
)

internal sealed interface Web4AgentEffectPreparation {
    data class Ready(val lease: Web4AgentPreparedEffect) : Web4AgentEffectPreparation

    data class Rejected(
        val code: String,
        val summary: String
    ) : Web4AgentEffectPreparation
}

internal data class Web4AgentExactJsonExecution(
    val result: Web4AgentJsonResult,
    val occurred: Boolean
)

internal data class Web4AgentExactActionExecution(
    val result: Web4AgentActionResult,
    val occurred: Boolean
)

/**
 * Internal capability required by governed Web4Agent eval/action tools.
 *
 * Keeping this separate from [Web4AgentSession] preserves the published
 * session ABI. A custom session provider that cannot issue and atomically
 * revalidate leases is intentionally rejected by the governed effect tools.
 */
internal interface Web4AgentExactEffectSession : Web4AgentSession {
    fun prepareExactEffect(
        kind: Web4AgentEffectKind,
        binding: Web4AgentExpectedBinding,
        requireTarget: Boolean
    ): Web4AgentEffectPreparation

    fun evaluatePrepared(
        lease: Web4AgentPreparedEffect,
        request: Web4AgentEvalRequest
    ): Web4AgentExactJsonExecution

    fun actPrepared(
        lease: Web4AgentPreparedEffect,
        action: Web4AgentAction
    ): Web4AgentExactActionExecution
}

internal object Web4AgentExactEffectErrors {
    const val EXACT_BINDING_REQUIRED = "EXACT_BINDING_REQUIRED"
    const val STALE_TARGET = "STALE_TARGET"
    const val SESSION_CLOSED = "SESSION_CLOSED"
    const val UNSUPPORTED_SESSION = "EXACT_EFFECT_UNSUPPORTED"

    fun json(code: String, summary: String): String = buildString {
        append('{')
        append("\"ok\":false,")
        append("\"code\":").append(Web4AgentJson.quote(code)).append(',')
        append("\"occurred\":false,")
        append("\"error\":").append(Web4AgentJson.quote(summary))
        append('}')
    }
}
