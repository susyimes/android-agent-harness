// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.voice.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Platform speech recognizer. The host must call it from an Android lifecycle
 * that can keep the recognizer alive and must declare/grant RECORD_AUDIO.
 */
class AndroidSpeechToTextEngine(
    private val context: Context
) : SpeechToTextEngine {
    override fun start(
        sessionId: String,
        localeTag: String?,
        listener: SpeechToTextListener
    ): VoiceOperation {
        require(sessionId.isNotBlank()) { "Voice session id must not be blank." }
        requireAudioPermission(context)
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw VoiceUnavailableException(
                VoiceUnavailableReason.ENGINE_UNAVAILABLE,
                "Android speech recognition is unavailable."
            )
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val terminal = AtomicBoolean(false)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listener.onStateChanged(VoiceOperationState.LISTENING)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listener.onStateChanged(VoiceOperationState.PROCESSING)
            }

            override fun onError(error: Int) {
                if (terminal.compareAndSet(false, true)) {
                    listener.onStateChanged(VoiceOperationState.FAILED)
                    listener.onError(
                        VoiceUnavailableException(
                            VoiceUnavailableReason.PLATFORM_ERROR,
                            "Android speech recognition failed with code $error."
                        )
                    )
                    recognizer.destroy()
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results.bestTranscript()
                if (terminal.compareAndSet(false, true)) {
                    if (text == null) {
                        listener.onError(
                            VoiceUnavailableException(
                                VoiceUnavailableReason.PLATFORM_ERROR,
                                "Android speech recognition returned no transcript."
                            )
                        )
                    } else {
                        listener.onFinalTranscript(text)
                        listener.onStateChanged(VoiceOperationState.COMPLETED)
                    }
                    recognizer.destroy()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.bestTranscript()?.let(listener::onPartialTranscript)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            localeTag?.takeIf(String::isNotBlank)?.let { tag ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            }
        }
        recognizer.startListening(intent)
        return VoiceOperation {
            if (terminal.compareAndSet(false, true)) {
                recognizer.cancel()
                recognizer.destroy()
                listener.onStateChanged(VoiceOperationState.CANCELLED)
            }
        }
    }

    private fun Bundle?.bestTranscript(): String? {
        return this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }
}

/**
 * Recorder whose only file lives in app cache and is deleted on stop/cancel.
 * [stop] returns bounded in-memory bytes; no raw-audio path escapes the adapter.
 */
class AndroidEphemeralVoiceRecorder(
    private val context: Context,
    private val maxBytes: Int = DEFAULT_MAX_AUDIO_BYTES,
    private val clock: () -> Long = System::currentTimeMillis
) : VoiceRecorder {
    init {
        require(maxBytes in 1..MAX_AUDIO_BYTES) {
            "Voice byte limit must be between 1 and $MAX_AUDIO_BYTES."
        }
    }

    @Suppress("DEPRECATION")
    override fun start(): VoiceRecording {
        requireAudioPermission(context)
        val file = File.createTempFile("agent-voice-", ".m4a", context.cacheDir)
        val startedAt = clock()
        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        return object : VoiceRecording {
            private val stateRef = AtomicReference(VoiceOperationState.LISTENING)

            override val state: VoiceOperationState
                get() = stateRef.get()

            override fun stop(): EphemeralAudio {
                check(stateRef.compareAndSet(
                    VoiceOperationState.LISTENING,
                    VoiceOperationState.PROCESSING
                )) { "Voice recording is not active." }
                try {
                    recorder.stop()
                    recorder.release()
                    val length = file.length()
                    if (length <= 0L || length > maxBytes) {
                        throw VoiceUnavailableException(
                            VoiceUnavailableReason.AUDIO_TOO_LARGE,
                            "Recorded audio size $length is outside the allowed range."
                        )
                    }
                    val bytes = file.readBytes()
                    stateRef.set(VoiceOperationState.COMPLETED)
                    return EphemeralAudio(
                        id = UUID.randomUUID().toString(),
                        mediaType = "audio/mp4",
                        bytes = bytes,
                        durationMillis = maxOf(0L, clock() - startedAt),
                        createdAtEpochMillis = startedAt
                    )
                } finally {
                    file.delete()
                }
            }

            override fun cancel() {
                if (!stateRef.compareAndSet(
                        VoiceOperationState.LISTENING,
                        VoiceOperationState.CANCELLED
                    )
                ) return
                runCatching(recorder::stop)
                runCatching(recorder::release)
                file.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_AUDIO_BYTES = 10 * 1024 * 1024
        const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
    }
}

class AndroidTextToSpeechEngine(
    context: Context,
    locale: Locale = Locale.getDefault()
) : SpeechOutputEngine, TextToSpeech.OnInitListener {
    private val listenerRef = AtomicReference<SpeechOutputListener?>()
    private val ready = AtomicBoolean(false)
    private val engine = TextToSpeech(context.applicationContext, this)
    private val requestedLocale = locale

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            engine.language = requestedLocale
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    listenerRef.getAndSet(null)
                        ?.onStateChanged(VoiceOperationState.COMPLETED)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    listenerRef.getAndSet(null)?.let { listener ->
                        listener.onStateChanged(VoiceOperationState.FAILED)
                        listener.onError(
                            VoiceUnavailableException(
                                VoiceUnavailableReason.PLATFORM_ERROR,
                                "Android text-to-speech playback failed."
                            )
                        )
                    }
                }
            })
            ready.set(true)
        }
    }

    override fun speak(text: String, listener: SpeechOutputListener): VoiceOperation {
        require(text.isNotBlank()) { "Speech output text must not be blank." }
        if (!ready.get()) {
            throw VoiceUnavailableException(
                VoiceUnavailableReason.ENGINE_UNAVAILABLE,
                "Android text-to-speech is not initialized."
            )
        }
        if (!listenerRef.compareAndSet(null, listener)) {
            throw VoiceUnavailableException(
                VoiceUnavailableReason.BUSY,
                "Text-to-speech is already active."
            )
        }
        listener.onStateChanged(VoiceOperationState.SPEAKING)
        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "agent-tts-${UUID.randomUUID()}"
        )
        if (result == TextToSpeech.ERROR) {
            listenerRef.set(null)
            throw VoiceUnavailableException(
                VoiceUnavailableReason.PLATFORM_ERROR,
                "Android text-to-speech rejected the request."
            )
        }
        return VoiceOperation { stop() }
    }

    override fun stop() {
        engine.stop()
        listenerRef.getAndSet(null)?.onStateChanged(VoiceOperationState.CANCELLED)
    }

    fun close() {
        stop()
        engine.shutdown()
        ready.set(false)
    }
}

private fun requireAudioPermission(context: Context) {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        throw VoiceUnavailableException(
            VoiceUnavailableReason.PERMISSION_NOT_GRANTED,
            "RECORD_AUDIO is not granted. The host must declare and request it explicitly."
        )
    }
}
