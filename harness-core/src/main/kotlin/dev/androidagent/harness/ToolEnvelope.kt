// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

/**
 * Declares the externally observable effect of a tool.
 *
 * A prompt can choose only from capabilities registered by the host; it cannot
 * change this declaration at runtime.
 */
enum class AgentToolSideEffect {
    NONE,
    LOCAL_READ,
    LOCAL_DRAFT_WRITE,
    LOCAL_DURABLE_WRITE,
    EXTERNAL_WRITE,
    DEVICE_ACTION
}

enum class AgentToolRisk {
    LOW,
    MEDIUM,
    HIGH,
    CONTEXTUAL
}

enum class AgentToolIdempotency {
    IDEMPOTENT,
    IDEMPOTENT_WITH_KEY,
    NON_IDEMPOTENT,
    UNKNOWN
}

data class AgentToolCapability(
    val sideEffect: AgentToolSideEffect = AgentToolSideEffect.NONE,
    val risk: AgentToolRisk = AgentToolRisk.LOW,
    val dataScopes: Set<String> = emptySet(),
    val requiresForeground: Boolean = false,
    val idempotency: AgentToolIdempotency = AgentToolIdempotency.UNKNOWN,
    val supportsCancellation: Boolean = false,
    val targetArgumentNames: Set<String> = emptySet()
) {
    init {
        require(dataScopes.none(String::isBlank)) { "Tool data scopes must not be blank." }
        require(targetArgumentNames.none(String::isBlank)) {
            "Tool target argument names must not be blank."
        }
        require(
            sideEffect != AgentToolSideEffect.NONE ||
                risk == AgentToolRisk.LOW
        ) {
            "A no-effect tool cannot declare elevated risk."
        }
    }

    val mayMutate: Boolean
        get() = sideEffect in setOf(
            AgentToolSideEffect.LOCAL_DRAFT_WRITE,
            AgentToolSideEffect.LOCAL_DURABLE_WRITE,
            AgentToolSideEffect.EXTERNAL_WRITE,
            AgentToolSideEffect.DEVICE_ACTION
        )

    companion object {
        fun none(): AgentToolCapability = AgentToolCapability()

        fun localRead(vararg scopes: String): AgentToolCapability = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_READ,
            dataScopes = scopes.toSet(),
            idempotency = AgentToolIdempotency.IDEMPOTENT
        )
    }
}

enum class AgentToolResultStatus {
    SUCCESS,
    FAILURE,
    DENIED,
    CANCELLED,
    UNAVAILABLE
}

enum class AgentPrivacyLabel {
    PUBLIC,
    INTERNAL,
    SENSITIVE,
    RESTRICTED
}

data class AgentEvidenceRef(
    val id: String,
    val source: String,
    val summary: String? = null
) {
    init {
        require(id.isNotBlank()) { "Evidence id must not be blank." }
        require(id.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Evidence id is too long."
        }
        require(source.isNotBlank()) { "Evidence source must not be blank." }
        require(source.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Evidence source is too long."
        }
        require(summary == null || summary.isNotBlank()) {
            "Evidence summary must not be blank."
        }
        require(summary == null || summary.length <= MAX_REFERENCE_SUMMARY_CHARS) {
            "Evidence summary is too long."
        }
    }

    companion object {
        const val MAX_REFERENCE_SUMMARY_CHARS = 4 * 1024
    }
}

data class AgentArtifactRef(
    val id: String,
    val mediaType: String,
    val displayName: String? = null,
    val byteSize: Long? = null
) {
    init {
        require(id.isNotBlank()) { "Artifact id must not be blank." }
        require(id.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Artifact id is too long."
        }
        require(mediaType.isNotBlank()) { "Artifact media type must not be blank." }
        require(mediaType.length <= MAX_MEDIA_TYPE_CHARS) {
            "Artifact media type is too long."
        }
        require(displayName == null || displayName.isNotBlank()) {
            "Artifact display name must not be blank."
        }
        require(
            displayName == null ||
                displayName.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS
        ) { "Artifact display name is too long." }
        require(byteSize == null || byteSize >= 0) { "Artifact byte size must not be negative." }
    }

    companion object {
        const val MAX_MEDIA_TYPE_CHARS = 255
    }
}

data class AgentCandidateRef(
    val id: String,
    val type: String
) {
    init {
        require(id.isNotBlank()) { "Candidate id must not be blank." }
        require(id.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Candidate id is too long."
        }
        require(type.isNotBlank()) { "Candidate type must not be blank." }
        require(type.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Candidate type is too long."
        }
    }
}

data class AgentToolEffectRecord(
    val effectId: String,
    val sideEffect: AgentToolSideEffect,
    val targetRef: String? = null,
    val argumentHash: String? = null,
    val idempotencyKey: String? = null,
    val occurred: Boolean
) {
    init {
        require(effectId.isNotBlank()) { "Effect id must not be blank." }
        require(effectId.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Effect id is too long."
        }
        require(targetRef == null || targetRef.isNotBlank()) {
            "Effect target reference must not be blank."
        }
        require(
            targetRef == null ||
                targetRef.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS
        ) { "Effect target reference is too long." }
        require(argumentHash == null || argumentHash.isNotBlank()) {
            "Effect argument hash must not be blank."
        }
        require(
            argumentHash == null ||
                argumentHash.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS
        ) { "Effect argument hash is too long." }
        require(idempotencyKey == null || idempotencyKey.isNotBlank()) {
            "Effect idempotency key must not be blank."
        }
        require(
            idempotencyKey == null ||
                idempotencyKey.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS
        ) { "Effect idempotency key is too long." }
    }
}

data class AgentRetryAdvice(
    val retryable: Boolean,
    val reason: String,
    val retryAfterMillis: Long? = null
) {
    init {
        require(reason.isNotBlank()) { "Retry reason must not be blank." }
        require(reason.length <= AgentEvidenceRef.MAX_REFERENCE_SUMMARY_CHARS) {
            "Retry reason is too long."
        }
        require(retryAfterMillis == null || retryAfterMillis >= 0) {
            "Retry delay must not be negative."
        }
    }
}

/**
 * Bounded, provider-safe result. Large or sensitive payloads stay behind an
 * opaque [rawPayloadRef] owned by the host.
 */
data class AgentToolResultEnvelope(
    val status: AgentToolResultStatus,
    val summary: String,
    val dataJson: String? = null,
    val evidence: List<AgentEvidenceRef> = emptyList(),
    val artifacts: List<AgentArtifactRef> = emptyList(),
    val candidates: List<AgentCandidateRef> = emptyList(),
    val rawPayloadRef: String? = null,
    val effect: AgentToolEffectRecord? = null,
    val retryAdvice: AgentRetryAdvice? = null,
    val privacy: AgentPrivacyLabel = AgentPrivacyLabel.INTERNAL,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null
) {
    init {
        require(summary.isNotBlank()) { "Tool result summary must not be blank." }
        require(summary.length <= MAX_SUMMARY_CHARS) {
            "Tool result summary exceeds $MAX_SUMMARY_CHARS characters."
        }
        require(dataJson == null || dataJson.isNotBlank()) {
            "Tool result data JSON must not be blank."
        }
        require(dataJson == null || dataJson.length <= MAX_DATA_JSON_CHARS) {
            "Tool result data JSON exceeds $MAX_DATA_JSON_CHARS characters."
        }
        require(evidence.size <= MAX_REFERENCES) {
            "Tool result has more than $MAX_REFERENCES evidence references."
        }
        require(artifacts.size <= MAX_REFERENCES) {
            "Tool result has more than $MAX_REFERENCES artifact references."
        }
        require(candidates.size <= MAX_REFERENCES) {
            "Tool result has more than $MAX_REFERENCES candidate references."
        }
        require(rawPayloadRef == null || rawPayloadRef.isNotBlank()) {
            "Raw payload reference must not be blank."
        }
        require(rawPayloadRef == null || rawPayloadRef.length <= MAX_REFERENCE_CHARS) {
            "Raw payload reference exceeds $MAX_REFERENCE_CHARS characters."
        }
        require(createdAtEpochMillis >= 0) { "Tool result creation time must not be negative." }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= createdAtEpochMillis) {
            "Tool result expiry cannot precede creation."
        }
    }

    val isError: Boolean
        get() = status != AgentToolResultStatus.SUCCESS

    companion object {
        fun fromLegacy(
            result: AgentToolResult,
            createdAtEpochMillis: Long,
            privacy: AgentPrivacyLabel = AgentPrivacyLabel.INTERNAL
        ): AgentToolResultEnvelope {
            return result.envelope ?: AgentToolResultEnvelope(
                status = if (result.isError) {
                    AgentToolResultStatus.FAILURE
                } else {
                    AgentToolResultStatus.SUCCESS
                },
                summary = boundedProviderContent(result),
                privacy = privacy,
                createdAtEpochMillis = createdAtEpochMillis
            )
        }

        fun boundedProviderContent(result: AgentToolResult): String {
            val value = result.content.ifBlank {
                if (result.isError) "Tool failed without a message." else "Tool completed."
            }
            if (value.length <= MAX_PROVIDER_CONTENT_CHARS) return value
            return value.take(MAX_PROVIDER_CONTENT_CHARS - 1) + "…"
        }

        const val MAX_SUMMARY_CHARS = 16 * 1024
        const val MAX_PROVIDER_CONTENT_CHARS = MAX_SUMMARY_CHARS
        const val MAX_DATA_JSON_CHARS = 64 * 1024
        const val MAX_REFERENCES = 64
        const val MAX_REFERENCE_CHARS = 1_024
    }
}

data class AgentRawPayloadScope(
    val runId: String,
    val sessionId: String,
    val toolCallId: String
) {
    init {
        require(runId.isNotBlank()) { "Raw payload run id must not be blank." }
        require(runId.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Raw payload run id is too long."
        }
        require(sessionId.isNotBlank()) { "Raw payload session id must not be blank." }
        require(sessionId.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Raw payload session id is too long."
        }
        require(toolCallId.isNotBlank()) { "Raw payload tool call id must not be blank." }
        require(toolCallId.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Raw payload tool call id is too long."
        }
    }
}

data class AgentRawPayload(
    val ref: String,
    val content: ByteArray,
    val mediaType: String,
    val privacy: AgentPrivacyLabel,
    val scope: AgentRawPayloadScope,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    init {
        require(ref.isNotBlank()) { "Raw payload ref must not be blank." }
        require(ref.length <= AgentToolResultEnvelope.MAX_REFERENCE_CHARS) {
            "Raw payload ref is too long."
        }
        require(content.isNotEmpty()) { "Raw payload content must not be empty." }
        require(content.size <= MAX_CONTENT_BYTES) {
            "Raw payload exceeds $MAX_CONTENT_BYTES bytes."
        }
        require(mediaType.isNotBlank()) { "Raw payload media type must not be blank." }
        require(mediaType.length <= AgentArtifactRef.MAX_MEDIA_TYPE_CHARS) {
            "Raw payload media type is too long."
        }
        require(createdAtEpochMillis >= 0) { "Raw payload creation time must not be negative." }
        require(expiresAtEpochMillis >= createdAtEpochMillis) {
            "Raw payload expiry cannot precede creation."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentRawPayload) return false
        return ref == other.ref &&
            content.contentEquals(other.content) &&
            mediaType == other.mediaType &&
            privacy == other.privacy &&
            scope == other.scope &&
            createdAtEpochMillis == other.createdAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis
    }

    override fun hashCode(): Int {
        var result = ref.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + privacy.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + createdAtEpochMillis.hashCode()
        result = 31 * result + expiresAtEpochMillis.hashCode()
        return result
    }

    companion object {
        const val MAX_CONTENT_BYTES = 16 * 1024 * 1024
    }
}

interface AgentRawPayloadStore {
    fun put(payload: AgentRawPayload)

    fun get(
        ref: String,
        scope: AgentRawPayloadScope,
        nowEpochMillis: Long
    ): AgentRawPayload?

    fun delete(ref: String, scope: AgentRawPayloadScope): Boolean
}
