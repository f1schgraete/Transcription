package com.siptranscribe.app

import android.util.Log
import com.microsoft.cognitiveservices.speech.CancellationReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechRecognizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import java.net.URI

/**
 * Azure Cognitive Services / AI Foundry implementation of [SttEngine].
 *
 * Credentials are read from SharedPreferences via [MainActivity.KEY_AZURE_ENDPOINT]
 * and [MainActivity.KEY_AZURE_KEY], saved when the user taps "Registrieren".
 *
 * PCM is pushed into a [PushAudioInputStream]; the SDK streams it to Azure and fires
 * partial ("recognizing") and final ("recognized") results via event listeners.
 */
class AzureSttEngine(
    private val endpoint: String,
    private val apiKey: String
) : SttEngine {

    companion object {
        private const val TAG = "AzureSttEngine"
        private const val LANGUAGE = "de-DE"
    }

    override var onResult: ((String, Boolean) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    @Volatile private var pushStream: PushAudioInputStream? = null
    @Volatile private var recognizer: SpeechRecognizer? = null

    override fun prepare(sampleRate: Int) {
        if (endpoint.isBlank() || apiKey.isBlank()) {
            Log.e(TAG, "prepare: Azure endpoint or key not configured")
            onError?.invoke("Azure-Endpunkt oder API-Schlüssel nicht konfiguriert")
            return
        }
        Log.i(TAG, "prepare($sampleRate Hz) endpoint=$endpoint")

        val format = AudioStreamFormat.getWaveFormatPCM(sampleRate.toLong(), 16.toShort(), 1.toShort())
        val stream = PushAudioInputStream.create(format).also { pushStream = it }

        val speechConfig = buildSpeechConfig(endpoint, apiKey) ?: run {
            onError?.invoke("Azure-Endpunkt ungültig: $endpoint")
            return
        }
        speechConfig.speechRecognitionLanguage = LANGUAGE

        val audioConfig = AudioConfig.fromStreamInput(stream)
        val rec = SpeechRecognizer(speechConfig, audioConfig).also { recognizer = it }

        rec.recognizing.addEventListener { _, e ->
            val text = e.result.text
            if (text.isNotBlank()) {
                Log.d(TAG, "Partial: \"$text\"")
                onResult?.invoke(text, false)
            }
        }
        rec.recognized.addEventListener { _, e ->
            val text = e.result.text
            if (text.isNotBlank()) {
                Log.i(TAG, "Final: \"$text\"")
                onResult?.invoke(text, true)
            }
        }
        rec.canceled.addEventListener { _, e ->
            if (e.reason == CancellationReason.Error) {
                val msg = "Azure STT Fehler ${e.errorCode}: ${e.errorDetails}"
                Log.e(TAG, msg)
                onError?.invoke(msg)
            } else {
                Log.d(TAG, "Canceled: ${e.reason}")
            }
        }

        rec.startContinuousRecognitionAsync()
        Log.i(TAG, "continuous recognition started")
    }

    override fun feed(pcm: ByteArray, length: Int) {
        // PushAudioInputStream.write() consumes the entire array, so trim if needed.
        pushStream?.write(if (length == pcm.size) pcm else pcm.copyOf(length))
    }

    /**
     * The Speech SDK needs either a region (it builds the WSS URL itself) or a
     * full `wss://…/speech/recognition/conversation/cognitiveservices/v1` URL.
     * Users typically paste the resource URL from the Azure portal, e.g.
     * `https://swedencentral.stt.speech.microsoft.com` — that's neither, so we
     * extract the region from `<region>.stt.speech.microsoft.com` and use
     * [SpeechConfig.fromSubscription]. Anything else is treated as a raw endpoint
     * with the scheme normalised to `wss://`.
     */
    private fun buildSpeechConfig(raw: String, key: String): SpeechConfig? {
        val trimmed = raw.trim().trimEnd('/')
        val hostMatch = Regex(
            "^(?:https?://|wss?://)?([a-z0-9-]+)\\.stt\\.speech\\.microsoft\\.com/?$",
            RegexOption.IGNORE_CASE
        ).matchEntire(trimmed)
        if (hostMatch != null) {
            val region = hostMatch.groupValues[1].lowercase()
            Log.i(TAG, "Using fromSubscription with region=$region")
            return SpeechConfig.fromSubscription(key, region)
        }
        val wsEndpoint = trimmed
            .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
        return try {
            Log.i(TAG, "Using fromEndpoint with $wsEndpoint")
            SpeechConfig.fromEndpoint(URI(wsEndpoint), key)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build SpeechConfig from $wsEndpoint", e)
            null
        }
    }

    override fun stop() {
        Log.i(TAG, "stop()")
        val ps = pushStream
        val rec = recognizer
        pushStream = null
        recognizer = null

        // Signal end-of-stream and stop on a background thread to avoid blocking the caller.
        Thread({
            try {
                ps?.close()                                // signals EOF to Azure
                rec?.stopContinuousRecognitionAsync()?.get()
                rec?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error during stop", e)
            }
        }, "AzureSttStop").start()
    }
}
