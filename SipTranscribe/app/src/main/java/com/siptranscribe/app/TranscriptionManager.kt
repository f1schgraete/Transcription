package com.siptranscribe.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuous speech recognition for the remote party's voice.
 *
 * Strategy: Linphone's mic is muted -> the device microphone is free ->
 * SpeechRecognizer captures whatever comes through the loudspeaker.
 *
 * Callbacks are always delivered on the main thread.
 */
class TranscriptionManager(private val context: Context) {

    companion object {
        private const val TAG = "TranscriptionManager"
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var listening = false

    /** Called with (text, isFinal). Always on main thread. */
    var onTranscription: ((String, Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable) {
            onError?.invoke("Spracherkennung nicht verfuegbar auf diesem Geraet")
            return
        }
        active = true
        scheduleStart(0)
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
            listening = false
        }
    }

    private fun scheduleStart(delayMs: Long) {
        if (!active) return
        handler.postDelayed({ beginListening() }, delayMs)
    }

    private fun beginListening() {
        if (!active || listening) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onTranscription?.invoke(it, false) }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onTranscription?.invoke(it, true) }
                if (active) scheduleStart(150)
            }

            override fun onError(error: Int) {
                listening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null   // Normal silence, just restart
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Erkennungs-Dienst beschaeftigt"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Netzwerkfehler - Transkription pausiert"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt"
                    SpeechRecognizer.ERROR_AUDIO -> "Mikrofon wird von anderer App belegt"
                    else -> null
                }
                msg?.let {
                    Log.w(TAG, "STT error $error: $it")
                    onError?.invoke(it)
                }
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 300L
                if (active) scheduleStart(delay)
            }

            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() { listening = false }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Prefer on-device model (Android 13+)
            putExtra("android.speech.extra.PREFER_OFFLINE", true)
            // Silence thresholds
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1200L
            )
        }
        recognizer?.startListening(intent)
    }
}
