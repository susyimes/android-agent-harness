// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentToolResultEnvelope
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextRiskLevel
import dev.androidagent.harness.context.ContextTaskType
import dev.androidagent.harness.context.ContextTimeRange
import java.io.Serializable

enum class AgentRunTrigger {
    USER,
    HEARTBEAT,
    DREAM,
    PROACTIVE,
    CRON,
    LONG_TASK,
    SELF_CHECK
}

data class AgentRunBudget(
    val maxProviderSteps: Int,
    val maxToolCalls: Int,
    val maxWallClockMillis: Long,
    val maxRepeatedFailures: Int,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null
) : Serializable {
    init {
        require(maxProviderSteps in 1..AgentHarnessConfig.MAX_PROVIDER_STEPS) {
            "Run provider steps must be between 1 and ${AgentHarnessConfig.MAX_PROVIDER_STEPS}."
        }
        require(maxToolCalls in 0..MAX_TOOL_CALLS) {
            "Run tool calls must be between 0 and $MAX_TOOL_CALLS."
        }
        require(maxWallClockMillis in 1..MAX_WALL_CLOCK_MILLIS) {
            "Run wall-clock budget must be between 1 and $MAX_WALL_CLOCK_MILLIS milliseconds."
        }
        require(maxRepeatedFailures in 1..MAX_REPEATED_FAILURES) {
            "Repeated failures must be between 1 and $MAX_REPEATED_FAILURES."
        }
        require(maxInputTokens == null || maxInputTokens > 0) {
            "Input token budget must be positive."
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "Output token budget must be positive."
        }
    }

    companion object {
        const val MAX_TOOL_CALLS = 10_000
        const val MAX_WALL_CLOCK_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAX_REPEATED_FAILURES = 100

        fun compatibility(config: AgentHarnessConfig): AgentRunBudget {
            val providerSteps = maxOf(
                config.maxProviderSteps,
                config.toolLoopActivation?.maxProviderSteps ?: config.maxProviderSteps
            )
            return AgentRunBudget(
                maxProviderSteps = providerSteps,
                maxToolCalls = config.maxToolCallsTotal,
                maxWallClockMillis = 5 * 60_000L,
                maxRepeatedFailures = config.maxRepeatedFailures
            )
        }
    }
}

data class AgentRunPolicy(
    val trigger: AgentRunTrigger = AgentRunTrigger.USER,
    val budget: AgentRunBudget,
    val toolProfileId: String,
    val contextPolicyId: String = "default",
    val writePolicyId: String = "candidate-only",
    val approvalPolicyId: String = "conservative"
) {
    init {
        require(toolProfileId.isNotBlank()) { "Tool profile id must not be blank." }
        require(contextPolicyId.isNotBlank()) { "Context policy id must not be blank." }
        require(writePolicyId.isNotBlank()) { "Write policy id must not be blank." }
        require(approvalPolicyId.isNotBlank()) { "Approval policy id must not be blank." }
    }

    companion object {
        fun compatibility(
            config: AgentHarnessConfig,
            toolProfileId: String
        ): AgentRunPolicy {
            return AgentRunPolicy(
                budget = AgentRunBudget.compatibility(config),
                toolProfileId = toolProfileId
            )
        }
    }
}

enum class AgentRunState {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    CHECKPOINTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED
}

data class AgentContextEngineOptions(
    val taskType: ContextTaskType? = null,
    val requestedSourceIds: Set<String> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val entities: List<String> = emptyList(),
    val timeRange: ContextTimeRange? = null,
    val riskLevel: ContextRiskLevel = ContextRiskLevel.LOW,
    val privacyCeiling: ContextPrivacy = ContextPrivacy.INTERNAL,
    val tokenBudget: Int? = null,
    val outputReserve: Int = 1_000
) {
    init {
        require(requestedSourceIds.none(String::isBlank)) {
            "Requested context source ids must not be blank."
        }
        require(requiredCapabilities.none(String::isBlank)) {
            "Context capability ids must not be blank."
        }
        require(entities.none(String::isBlank)) { "Context entities must not be blank." }
        require(tokenBudget == null || tokenBudget > 0) {
            "Context token budget must be positive."
        }
        require(outputReserve >= 0) { "Context output reserve must not be negative." }
    }

    fun resolvedTaskType(trigger: AgentRunTrigger): ContextTaskType {
        return taskType ?: when (trigger) {
            AgentRunTrigger.USER -> ContextTaskType.CHAT
            AgentRunTrigger.HEARTBEAT,
            AgentRunTrigger.DREAM,
            AgentRunTrigger.PROACTIVE,
            AgentRunTrigger.CRON,
            AgentRunTrigger.LONG_TASK -> ContextTaskType.BACKGROUND
            AgentRunTrigger.SELF_CHECK -> ContextTaskType.DIAGNOSTIC
        }
    }
}

sealed interface AgentEvent {
    val runId: String
    val sessionId: String
    val occurredAtEpochMillis: Long

    data class RunStateChanged(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val previous: AgentRunState,
        val current: AgentRunState
    ) : AgentEvent

    data class RunStarted(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val trigger: AgentRunTrigger,
        val providerId: String,
        val budget: AgentRunBudget
    ) : AgentEvent

    data class ContextCompiled(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val selectedIds: List<String>,
        val droppedIds: List<String>,
        val totalContentChars: Int
    ) : AgentEvent

    data class RouteDecided(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val action: String,
        val reason: String
    ) : AgentEvent

    data class ProviderStarted(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val step: Int,
        val providerId: String,
        val toolNames: List<String>
    ) : AgentEvent

    data class ProviderDelta(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val step: Int,
        val text: String
    ) : AgentEvent

    data class ProviderDisplay(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val step: Int,
        val kind: String,
        val text: String? = null,
        val toolName: String? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null
    ) : AgentEvent

    data class ProviderCompleted(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val step: Int,
        val responseKind: String
    ) : AgentEvent

    data class ToolRequested(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val step: Int,
        val callId: String,
        val toolName: String,
        val argumentNames: Set<String>
    ) : AgentEvent

    data class ApprovalRequested(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val approvalId: String,
        val effectSummary: String
    ) : AgentEvent

    data class ApprovalResolved(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val approvalId: String,
        val decision: String
    ) : AgentEvent

    data class ToolCompleted(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val step: Int,
        val callId: String,
        val toolName: String,
        val envelope: AgentToolResultEnvelope
    ) : AgentEvent

    data class DeviceLoopActivated(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val step: Int,
        val toolName: String,
        val maxProviderSteps: Int
    ) : AgentEvent

    data class CheckpointSaved(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val revision: Long
    ) : AgentEvent

    data class CandidateProduced(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val candidateId: String,
        val candidateType: String
    ) : AgentEvent

    data class RunFinished(
        override val runId: String,
        override val sessionId: String,
        override val occurredAtEpochMillis: Long,
        val state: AgentRunState,
        val summary: String
    ) : AgentEvent
}

fun interface TraceSink {
    fun emit(event: AgentEvent)

    companion object {
        val NONE = TraceSink {}
    }
}

class CompositeTraceSink(sinks: List<TraceSink>) : TraceSink {
    private val sinks = sinks.toList()

    override fun emit(event: AgentEvent) {
        sinks.forEach { sink ->
            try {
                sink.emit(event)
            } catch (_: RuntimeException) {
                // One observability sink cannot prevent delivery to another.
            }
        }
    }
}

class RedactingTraceSink(
    private val delegate: TraceSink,
    private val redact: (AgentEvent) -> AgentEvent?
) : TraceSink {
    override fun emit(event: AgentEvent) {
        redact(event)?.let(delegate::emit)
    }
}
