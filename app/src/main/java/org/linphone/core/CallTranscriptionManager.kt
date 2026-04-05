/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import org.linphone.core.tools.Log

/**
 * Manages live speech recognition during a call using the device microphone.
 *
 * Android 10+ supports concurrent capture from AudioSource.MIC (used by SpeechRecognizer)
 * and AudioSource.VOICE_COMMUNICATION (used by Linphone), so both can run simultaneously.
 * This captures the local user's voice; it may also capture the remote caller's voice when
 * the audio is loud enough (e.g., speakerphone mode).
 *
 * Must be created, started, and stopped on the main thread.
 */
class CallTranscriptionManager(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val onPartialResult: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "[Call Transcription]"
        private const val SILENCE_TIMEOUT_MS = 15_000L
        private const val POSSIBLY_DONE_SILENCE_MS = 8_000L

        // SpeechRecognizer error codes added in API 28+ not available as named constants
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
        private const val ERROR_CANNOT_CHECK_SUPPORT = 14
        private const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var active = false
    /** True when on-device recogniser failed due to language issues; fall back to network. */
    private var forceNetworkRecogniser = false

    private val lines = mutableListOf<String>()

    // ─── Public API ──────────────────────────────────────────────────────────

    @MainThread
    fun start() {
        if (active) return // already running — StreamsRunning can fire multiple times
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("$TAG Speech recognition not available on this device")
            onStatus("Speech recognition unavailable")
            return
        }
        active = true
        Log.i("$TAG Starting mic-based transcription")
        createAndListen()
    }

    @MainThread
    fun stop() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.i("$TAG Stopped")
    }

    fun hasTranscript(): Boolean = lines.isNotEmpty()

    fun getFullTranscript(): String = lines.joinToString("\n")

    // ─── Internal ────────────────────────────────────────────────────────────

    @MainThread
    private fun createAndListen() {
        speechRecognizer?.destroy()

        val useOnDevice = !forceNetworkRecogniser &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        speechRecognizer = if (useOnDevice) {
            Log.i("$TAG Using on-device recogniser (API 33+)")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.i("$TAG Using network recogniser")
            SpeechRecognizer.createSpeechRecognizer(context)
        }.also { it.setRecognitionListener(listener) }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_DONE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
        }
        speechRecognizer?.startListening(intent)
        Log.d("$TAG startListening called")
    }

    @MainThread
    private fun restart(delayMs: Long = 200L) {
        mainHandler.postDelayed({
            if (active) createAndListen()
        }, delayMs)
    }

    // ─── RecognitionListener ─────────────────────────────────────────────────

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("$TAG onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d("$TAG onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d("$TAG onEndOfSpeech")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                Log.d("$TAG Partial: $text")
                onPartialResult(text)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                Log.d("$TAG Recognised: $text")
                lines.add(text)
                onResult(text)
            } else {
                Log.d("$TAG onResults: empty result, restarting")
            }
            restart()
        }

        override fun onError(error: Int) {
            val errorName = when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT(1)"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK(2)"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO(3)"
                SpeechRecognizer.ERROR_SERVER -> "SERVER(4)"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT(5)"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT(6)"
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH(7)"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY(8)"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS(9)"
                ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS(10)"
                ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED(11)"
                ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED(12)"
                ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE(13)"
                ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT(14)"
                ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "CANNOT_LISTEN_DOWNLOAD(15)"
                else -> "UNKNOWN($error)"
            }
            Log.w("$TAG Recognition error: $errorName")

            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e("$TAG Microphone permission denied — stopping")
                    onStatus("Mic permission denied")
                    active = false
                    return
                }
                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                    // On-device recogniser doesn't have the language pack — switch to network.
                    Log.w("$TAG On-device recogniser has no language model, falling back to network")
                    forceNetworkRecogniser = true
                }
            }

            onStatus("err:$errorName — retrying…")

            val delay = when (error) {
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                ERROR_TOO_MANY_REQUESTS -> 1_500L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                ERROR_SERVER_DISCONNECTED -> 3_000L
                else -> 500L
            }
            restart(delay)
        }
    }
}
