// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.voice.android

enum class VoiceOperationState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    COMPLETED,
    CANCELLED,
    FAILED
}

enum class VoiceUnavailableReason {
    PERMISSION_NOT_GRANTED,
    ENGINE_UNAVAILABLE,
    BUSY,
    AUDIO_TOO_LARGE,
    PLATFORM_ERROR
}

class VoiceUnavailableException(
    val reason: VoiceUnavailableReason,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

data class VoiceTranscript(
    val id: String,
    val sessionId: String,
    val text: String,
    val createdAtEpochMillis: Long,
    val isFinal: Boolean,
    val localeTag: String? = null
) {
    init {
        require(id.isNotBlank()) { "Transcript id must not be blank." }
        require(sessionId.isNotBlank()) { "Transcript session id must not be blank." }
        require(text.isNotBlank()) { "Transcript text must not be blank." }
    }
}

data class EphemeralAudio(
    val id: String,
    val mediaType: String,
    val bytes: ByteArray,
    val durationMillis: Long,
    val createdAtEpochMillis: Long
) {
    init {
        require(id.isNotBlank()) { "Audio id must not be blank." }
        require(mediaType.isNotBlank()) { "Audio media type must not be blank." }
        require(bytes.isNotEmpty()) { "Audio bytes must not be empty." }
        require(durationMillis >= 0) { "Audio duration must not be negative." }
    }
}

fun interface VoiceOperation {
    fun cancel()
}

interface SpeechToTextListener {
    fun onStateChanged(state: VoiceOperationState)
    fun onPartialTranscript(text: String)
    fun onFinalTranscript(text: String)
    fun onError(error: VoiceUnavailableException)
}

interface SpeechToTextEngine {
    fun start(
        sessionId: String,
        localeTag: String? = null,
        listener: SpeechToTextListener
    ): VoiceOperation
}

interface VoiceRecording {
    val state: VoiceOperationState
    fun stop(): EphemeralAudio
    fun cancel()
}

interface VoiceRecorder {
    fun start(): VoiceRecording
}

interface StreamingTranscriptionEngine {
    fun transcribe(
        sessionId: String,
        audio: EphemeralAudio,
        listener: SpeechToTextListener
    ): VoiceOperation
}

interface SpeechOutputListener {
    fun onStateChanged(state: VoiceOperationState)
    fun onError(error: VoiceUnavailableException)
}

interface SpeechOutputEngine {
    fun speak(text: String, listener: SpeechOutputListener): VoiceOperation
    fun stop()
}

interface VoiceSessionRepository {
    val persistenceEnabled: Boolean
    fun save(transcript: VoiceTranscript)
    fun list(sessionId: String): List<VoiceTranscript>
    fun deleteSession(sessionId: String): Int
    fun clear(): Int
}

/** Default transcript store: process-local and opt-in at construction time. */
class InMemoryVoiceSessionRepository(
    override val persistenceEnabled: Boolean = false
) : VoiceSessionRepository {
    private val lock = Any()
    private val transcripts = linkedMapOf<String, MutableList<VoiceTranscript>>()

    override fun save(transcript: VoiceTranscript) {
        if (!persistenceEnabled) return
        synchronized(lock) {
            transcripts.getOrPut(transcript.sessionId, ::mutableListOf).add(transcript)
        }
    }

    override fun list(sessionId: String): List<VoiceTranscript> = synchronized(lock) {
        transcripts[sessionId].orEmpty().toList()
    }

    override fun deleteSession(sessionId: String): Int = synchronized(lock) {
        transcripts.remove(sessionId)?.size ?: 0
    }

    override fun clear(): Int = synchronized(lock) {
        val count = transcripts.values.sumOf(List<VoiceTranscript>::size)
        transcripts.clear()
        count
    }
}
