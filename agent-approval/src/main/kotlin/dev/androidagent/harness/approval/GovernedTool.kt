// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.approval

import dev.androidagent.harness.AgentPrivacyLabel
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolEffectRecord
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolResultEnvelope
import dev.androidagent.harness.AgentToolResultStatus
import dev.androidagent.harness.AgentToolSideEffect
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A tool that must validate a runtime target before it can form its exact
 * [AgentEffectIntent]. AgentSdk binds the run-scoped coordinator instead of
 * authorizing the static tool spec ahead of that validation.
 */
interface AgentApprovalAwareTool : AgentTool {
    fun bindApprovalCoordinator(approvals: AgentApprovalCoordinator): AgentTool
}

/**
 * Applies the generic approval layer before one tool invocation.
 */
class GovernedAgentTool(
    private val delegate: AgentTool,
    private val approvals: AgentApprovalCoordinator
) : AgentTool {
    override val spec = delegate.spec

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val boundInvocation = invocation.copy(arguments = invocation.arguments.toSortedMap())
        val intent = effectIntent(boundInvocation)
        val authorization = approvals.authorize(intent)
        if (authorization is AgentEffectAuthorization.Rejected) {
            val status = when (authorization.decision) {
                AgentApprovalDecision.DENIED -> AgentToolResultStatus.DENIED
                AgentApprovalDecision.TIMEOUT -> AgentToolResultStatus.DENIED
                AgentApprovalDecision.UNAVAILABLE -> AgentToolResultStatus.UNAVAILABLE
                AgentApprovalDecision.APPROVED -> error("Approved cannot be rejected.")
            }
            val summary = "APPROVAL_${authorization.decision.name}: ${authorization.message}"
            return AgentToolResult.failure(
                content = summary,
                envelope = AgentToolResultEnvelope(
                    status = status,
                    summary = summary,
                    privacy = AgentPrivacyLabel.INTERNAL,
                    createdAtEpochMillis = approvals.nowEpochMillis()
                )
            )
        }
        val token = (authorization as AgentEffectAuthorization.Allowed).token
        if (token != null && !approvals.consume(token, intent)) {
            val summary =
                "APPROVAL_TOKEN_MISMATCH: approval expired, changed, or was already consumed."
            return AgentToolResult.failure(
                content = summary,
                envelope = AgentToolResultEnvelope(
                    status = AgentToolResultStatus.DENIED,
                    summary = summary,
                    privacy = AgentPrivacyLabel.INTERNAL,
                    createdAtEpochMillis = approvals.nowEpochMillis()
                )
            )
        }

        val result = delegate.execute(boundInvocation)
        val now = approvals.nowEpochMillis()
        val legacyEnvelope = AgentToolResultEnvelope.fromLegacy(result, now)
        val effect = if (spec.capability.sideEffect == AgentToolSideEffect.NONE) {
            null
        } else {
            AgentToolEffectRecord(
                effectId = "effect-${boundInvocation.callId}",
                sideEffect = spec.capability.sideEffect,
                targetRef = intent.targetRef,
                argumentHash = intent.argumentHash,
                idempotencyKey = when (spec.capability.idempotency) {
                    AgentToolIdempotency.IDEMPOTENT_WITH_KEY ->
                        "${boundInvocation.runId}:${boundInvocation.callId}"
                    else -> null
                },
                occurred = !result.isError
            )
        }
        val envelope = if (legacyEnvelope.effect == null && effect != null) {
            legacyEnvelope.copy(effect = effect)
        } else {
            legacyEnvelope
        }
        return result.copy(envelope = envelope)
    }

    private fun effectIntent(invocation: AgentToolInvocation): AgentEffectIntent {
        val targets = spec.capability.targetArgumentNames
            .sorted()
            .mapNotNull { name ->
                invocation.arguments[name]?.takeIf(String::isNotBlank)?.let { value -> "$name=$value" }
            }
        val targetRef = targets.takeIf { values -> values.isNotEmpty() }?.joinToString(",")
        return AgentEffectIntent(
            runId = invocation.runId,
            sessionId = invocation.sessionId,
            toolCallId = invocation.callId,
            toolName = spec.name,
            capability = spec.capability,
            targetRef = targetRef,
            argumentHash = AgentEffectHasher.hash(spec.name, invocation.arguments),
            summary = "Run '${spec.name}' with ${spec.capability.sideEffect.name} effect."
        )
    }
}

object AgentEffectHasher {
    fun hash(toolName: String, arguments: Map<String, String>): String {
        require(toolName.isNotBlank()) { "Tool name must not be blank." }
        val canonical = buildString {
            appendPart(toolName)
            arguments.toSortedMap().forEach { (name, value) ->
                appendPart(name)
                appendPart(value)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Binds an approval token to every security-relevant field shown to policy
     * and approval surfaces, not only the provider-supplied arguments.
     */
    fun hashIntent(intent: AgentEffectIntent): String {
        return hash(
            "agent-effect-intent-v1",
            mapOf(
                "runId" to intent.runId,
                "sessionId" to intent.sessionId,
                "toolCallId" to intent.toolCallId,
                "toolName" to intent.toolName,
                "sideEffect" to intent.capability.sideEffect.name,
                "risk" to intent.capability.risk.name,
                "dataScopes" to canonicalValues(intent.capability.dataScopes.sorted()),
                "requiresForeground" to intent.capability.requiresForeground.toString(),
                "idempotency" to intent.capability.idempotency.name,
                "supportsCancellation" to intent.capability.supportsCancellation.toString(),
                "targetArguments" to
                    canonicalValues(intent.capability.targetArgumentNames.sorted()),
                "targetRef" to (intent.targetRef ?: "<none>"),
                "argumentHash" to intent.argumentHash,
                "summary" to intent.summary,
                "evidenceRefs" to canonicalValues(intent.evidenceRefs)
            )
        )
    }

    private fun StringBuilder.appendPart(value: String) {
        append(value.length)
        append(':')
        append(value)
        append('|')
    }

    private fun canonicalValues(values: Iterable<String>): String = buildString {
        values.forEach { value -> appendPart(value) }
    }
}

fun AgentTool.governedBy(approvals: AgentApprovalCoordinator): AgentTool {
    return when (this) {
        is AgentApprovalAwareTool -> bindApprovalCoordinator(approvals)
        is GovernedAgentTool -> this
        else -> GovernedAgentTool(this, approvals)
    }
}
