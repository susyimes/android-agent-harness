// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import dev.androidagent.harness.AgentArtifactRef
import dev.androidagent.harness.AgentPrivacyLabel
import dev.androidagent.harness.AgentRawPayload
import dev.androidagent.harness.AgentRawPayloadScope
import dev.androidagent.harness.AgentRawPayloadStore
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolResultEnvelope
import dev.androidagent.harness.AgentToolResultStatus
import dev.androidagent.harness.AgentToolSpec
import java.util.UUID

data class EphemeralVisualObservation(
    val id: String,
    val mediaType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val redactionSummary: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    init {
        require(id.isNotBlank())
        require(mediaType.startsWith("image/"))
        require(bytes.isNotEmpty())
        require(width > 0 && height > 0)
        require(redactionSummary.isNotBlank())
        require(expiresAtEpochMillis >= createdAtEpochMillis)
    }
}

fun interface VisualObservationSource {
    /**
     * Captures one already-redacted image after the host explicitly enables
     * visual observe. Implementations own platform consent and masking.
     */
    fun capture(): EphemeralVisualObservation
}

data class LocalUnderstandingRequest(
    val goal: String,
    val maxLabels: Int = 32
) {
    init {
        require(goal.isNotBlank())
        require(maxLabels in 1..256)
    }
}

data class LocalUnderstandingResult(
    val summary: String,
    val labels: List<String>,
    val confidence: Double,
    val engineId: String
) {
    init {
        require(summary.isNotBlank())
        require(labels.none(String::isBlank))
        require(confidence in 0.0..1.0)
        require(engineId.isNotBlank())
    }
}

fun interface LocalUnderstandingEngine {
    /** Optional on-device interpretation; it never receives Agent credentials. */
    fun understand(
        observation: EphemeralVisualObservation,
        request: LocalUnderstandingRequest
    ): LocalUnderstandingResult
}

/**
 * Visual observation tool with a bounded, opaque raw payload envelope.
 *
 * The image itself is never put in the provider-visible content or trace.
 */
class DeviceVisualObserveTool(
    private val source: VisualObservationSource,
    private val rawPayloadStore: AgentRawPayloadStore,
    private val localEngine: LocalUnderstandingEngine? = null,
    private val enabled: () -> Boolean = { false },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : AgentTool {
    override val spec = AgentToolSpec(
        name = "device_visual_observe",
        description = "Captures one temporary, redacted visual observation when host-enabled.",
        optionalArguments = setOf("goal"),
        capability = AgentToolCapability.localRead("device-screen-image")
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        if (!enabled()) {
            return unavailable("Visual observe is disabled by the user.")
        }
        val observation = try {
            source.capture()
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: "Visual capture is unavailable.")
        }
        if (observation.expiresAtEpochMillis < nowEpochMillis()) {
            return unavailable("Visual observation expired before it could be used.")
        }
        val rawRef = "visual:${observation.id}:${UUID.randomUUID()}"
        rawPayloadStore.put(
            AgentRawPayload(
                ref = rawRef,
                content = observation.bytes,
                mediaType = observation.mediaType,
                privacy = AgentPrivacyLabel.RESTRICTED,
                scope = AgentRawPayloadScope(
                    runId = invocation.runId,
                    sessionId = invocation.sessionId,
                    toolCallId = invocation.callId
                ),
                createdAtEpochMillis = observation.createdAtEpochMillis,
                expiresAtEpochMillis = observation.expiresAtEpochMillis
            )
        )
        val local = localEngine?.let { engine ->
            runCatching {
                engine.understand(
                    observation,
                    LocalUnderstandingRequest(
                        goal = invocation.arguments["goal"]?.takeIf(String::isNotBlank)
                            ?: "Describe actionable visible UI."
                    )
                )
            }.getOrNull()
        }
        val summary = buildString {
            append("Captured temporary redacted screen ${observation.width}x${observation.height}.")
            append(" ${observation.redactionSummary}")
            local?.let { result ->
                append(" Local understanding: ${result.summary}")
            }
        }
        return AgentToolResult.success(
            summary,
            AgentToolResultEnvelope(
                status = AgentToolResultStatus.SUCCESS,
                summary = summary,
                dataJson = local?.let { result ->
                    """{"engine":"${escapeJson(result.engineId)}","confidence":${result.confidence},"labels":[${
                        result.labels.joinToString(",") { label -> "\"${escapeJson(label)}\"" }
                    }]}"""
                },
                artifacts = listOf(
                    AgentArtifactRef(
                        id = observation.id,
                        mediaType = observation.mediaType,
                        displayName = "temporary-redacted-screen",
                        byteSize = observation.bytes.size.toLong()
                    )
                ),
                rawPayloadRef = rawRef,
                privacy = AgentPrivacyLabel.RESTRICTED,
                createdAtEpochMillis = observation.createdAtEpochMillis,
                expiresAtEpochMillis = observation.expiresAtEpochMillis
            )
        )
    }

    private fun unavailable(message: String): AgentToolResult = AgentToolResult.failure(
        message,
        AgentToolResultEnvelope(
            status = AgentToolResultStatus.UNAVAILABLE,
            summary = message,
            privacy = AgentPrivacyLabel.RESTRICTED,
            createdAtEpochMillis = nowEpochMillis()
        )
    )

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

data class ExperimentalSensorSnapshot(
    val adapterId: String,
    val values: Map<String, Double>,
    val collectedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    init {
        require(adapterId.isNotBlank())
        require(values.keys.none(String::isBlank))
        require(expiresAtEpochMillis >= collectedAtEpochMillis)
    }
}

interface ExperimentalSensorAdapter {
    val id: String
    val enabled: Boolean

    /** Returns short-lived aggregate values only; raw streams stay platform-side. */
    fun snapshot(): ExperimentalSensorSnapshot?
}
