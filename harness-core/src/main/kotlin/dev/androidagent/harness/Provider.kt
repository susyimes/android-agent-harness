// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

data class AttachmentRef(
    val id: String,
    val mediaType: String,
    val displayName: String? = null,
    val byteSize: Long,
    val privacy: AgentPrivacyLabel = AgentPrivacyLabel.SENSITIVE,
    val contentRef: String
) {
    init {
        require(id.isNotBlank()) { "Attachment id must not be blank." }
        require(mediaType.isNotBlank()) { "Attachment media type must not be blank." }
        require(displayName == null || displayName.isNotBlank()) {
            "Attachment display name must not be blank."
        }
        require(byteSize in 0..MAX_ATTACHMENT_BYTES) {
            "Attachment byte size must be between 0 and $MAX_ATTACHMENT_BYTES."
        }
        require(contentRef.isNotBlank()) { "Attachment content ref must not be blank." }
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
    }
}

data class AgentAttachmentContent(
    val mediaType: String,
    val bytes: ByteArray
) {
    init {
        require(mediaType.isNotBlank()) { "Resolved attachment media type must not be blank." }
        require(bytes.size.toLong() <= AttachmentRef.MAX_ATTACHMENT_BYTES) {
            "Resolved attachment exceeds ${AttachmentRef.MAX_ATTACHMENT_BYTES} bytes."
        }
    }
}

fun interface AgentAttachmentResolver {
    /**
     * Resolves content only for the current provider turn.
     *
     * Implementations must enforce URI grants, size limits and temporary-data
     * cleanup. Raw bytes must never be placed in traces or durable sessions.
     */
    fun resolve(attachment: AttachmentRef): AgentAttachmentContent

    companion object {
        val NONE = AgentAttachmentResolver {
            throw IllegalStateException("No attachment resolver is configured.")
        }
    }
}

data class AgentProviderCapabilities(
    val streaming: Boolean = false,
    val acceptedInputMediaTypes: Set<String> = emptySet(),
    val audioInput: Boolean = false,
    val audioOutput: Boolean = false
) {
    init {
        require(acceptedInputMediaTypes.none(String::isBlank)) {
            "Provider input media types must not be blank."
        }
    }

    fun accepts(mediaType: String): Boolean {
        return mediaType in acceptedInputMediaTypes ||
            acceptedInputMediaTypes.any { accepted ->
                accepted.endsWith("/*") &&
                    mediaType.startsWith(accepted.removeSuffix("*"))
            }
    }
}

data class AgentProviderRequest(
    val session: AgentSession,
    val context: List<AgentContextItem>,
    val tools: List<AgentToolSpec>,
    val providerStep: Int,
    val attachments: List<AttachmentRef> = emptyList()
) {
    init {
        require(providerStep > 0) { "Provider step must be positive." }
        require(attachments.map { attachment -> attachment.id }.distinct().size == attachments.size) {
            "Attachment ids must be unique within a provider request."
        }
    }
}

sealed interface AgentProviderResponse {
    data class FinalText(val content: String) : AgentProviderResponse

    data class ToolRequests(val calls: List<AgentToolCall>) : AgentProviderResponse {
        init {
            require(calls.isNotEmpty()) { "A tool response must contain at least one call." }
        }
    }
}

interface AgentProvider {
    val id: String
    val capabilities: AgentProviderCapabilities
        get() = AgentProviderCapabilities()

    fun respond(request: AgentProviderRequest): AgentProviderResponse
}

sealed interface AgentProviderDisplayEvent {
    data class TextDelta(val text: String) : AgentProviderDisplayEvent {
        init {
            require(text.isNotEmpty()) { "Provider text delta must not be empty." }
        }
    }

    data class ActionNarration(val text: String) : AgentProviderDisplayEvent

    data class ToolStatus(
        val toolName: String,
        val status: String
    ) : AgentProviderDisplayEvent

    data class Usage(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null
    ) : AgentProviderDisplayEvent
}

fun interface AgentProviderDisplayObserver {
    fun onEvent(event: AgentProviderDisplayEvent)

    companion object {
        val NONE = AgentProviderDisplayObserver {}
    }
}

/**
 * Optional provider extension for display-safe streaming.
 *
 * Hidden reasoning is not part of this contract. Implementations return the
 * same terminal response as [AgentProvider.respond]; only that response can be
 * committed to the session.
 */
interface AgentStreamingProvider : AgentProvider {
    fun respondStreaming(
        request: AgentProviderRequest,
        observer: AgentProviderDisplayObserver
    ): AgentProviderResponse
}

/**
 * One turn-scoped provider plus the hook that aborts its current I/O.
 *
 * Provider implementations should make [cancel] idempotent. The SDK creates a
 * fresh connection per run, so cancellation never poisons a later run.
 */
data class AgentProviderConnection(
    val provider: AgentProvider,
    val cancel: () -> Unit = {}
) {
    init {
        require(provider.id.isNotBlank()) { "Provider id must not be blank." }
    }
}

/** Creates an isolated provider connection for each Agent run. */
fun interface AgentProviderFactory {
    fun connect(): AgentProviderConnection

    companion object {
        fun fixed(provider: AgentProvider): AgentProviderFactory {
            return AgentProviderFactory { AgentProviderConnection(provider) }
        }
    }
}
