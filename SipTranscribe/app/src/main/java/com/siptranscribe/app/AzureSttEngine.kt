package com.siptranscribe.app

import android.util.Log
import com.microsoft.cognitiveservices.speech.CancellationReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriber
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

    override var onResult: ((String, Boolean, String?) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    @Volatile private var pushStream: PushAudioInputStream? = null
    @Volatile private var transcriber: ConversationTranscriber? = null

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
        val tx = ConversationTranscriber(speechConfig, audioConfig).also { transcriber = it }

        tx.transcribing.addEventListener { _, e ->
            val text = e.result.text
            val speakerId = e.result.speakerId
            if (text.isNotBlank()) {
                Log.d(TAG, "Partial[$speakerId]: \"$text\"")
                onResult?.invoke(text, false, speakerId)
            }
        }
        tx.transcribed.addEventListener { _, e ->
            val text = e.result.text
            val speakerId = e.result.speakerId
            if (text.isNotBlank()) {
                Log.i(TAG, "Final[$speakerId]: \"$text\"")
                onResult?.invoke(text, true, speakerId)
            }
        }
        tx.canceled.addEventListener { _, e ->
            if (e.reason == CancellationReason.Error) {
                val msg = "Azure STT Fehler ${e.errorCode}: ${e.errorDetails}"
                Log.e(TAG, msg)
                onError?.invoke(msg)
            } else {
                Log.d(TAG, "Canceled: ${e.reason}")
            }
        }

        tx.startTranscribingAsync()
        Log.i(TAG, "conversation transcription started")
    }

    override fun feed(pcm: ByteArray, length: Int) {
        // PushAudioInputStream.write() consumes the entire array, so trim if needed.
        pushStream?.write(if (length == pcm.size) pcm else pcm.copyOf(length))
    }

    /**
     * The Speech SDK accepts a region (it builds the WSS URL itself) or a full
     * WSS URL. The user pastes one of:
     *   - https://<region>.stt.speech.microsoft.com         (legacy Speech-only)
     *   - https://<resource>.cognitiveservices.azure.com    (multi-service / Foundry)
     *   - https://<resource>.services.ai.azure.com          (newer AI Services)
     *   - any other host                                    (custom)
     *
     * The first form gives us a region directly. The unified Foundry/AI Services
     * hosts expose Speech under `/stt/speech/universal/v2` over WSS. Anything
     * else falls through with a scheme-normalised endpoint.
     */
    private fun buildSpeechConfig(raw: String, key: String): SpeechConfig? {
        val trimmed = raw.trim().trimEnd('/')

        Regex(
            "^(?:https?://|wss?://)?([a-z0-9-]+)\\.stt\\.speech\\.microsoft\\.com/?$",
            RegexOption.IGNORE_CASE
        ).matchEntire(trimmed)?.let { match ->
            val region = match.groupValues[1].lowercase()
            Log.i(TAG, "Using fromSubscription with region=$region")
            return SpeechConfig.fromSubscription(key, region)
        }

        Regex(
            "^(?:https?://|wss?://)?([a-z0-9-]+\\.(?:cognitiveservices\\.azure\\.com|services\\.ai\\.azure\\.com))/?$",
            RegexOption.IGNORE_CASE
        ).matchEntire(trimmed)?.let { match ->
            val host = match.groupValues[1].lowercase()
            val wsUrl = "wss://$host/stt/speech/universal/v2"
            Log.i(TAG, "Using fromEndpoint with unified host: $wsUrl")
            return try {
                SpeechConfig.fromEndpoint(URI(wsUrl), key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build SpeechConfig from $wsUrl", e)
                null
            }
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
        val rec = transcriber
        pushStream = null
        transcriber = null

        // Signal end-of-stream and stop on a background thread to avoid blocking the caller.
        Thread({
            try {
                ps?.close()                                // signals EOF to Azure
                rec?.stopTranscribingAsync()?.get()
                rec?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error during stop", e)
            }
        }, "AzureSttStop").start()
    }
}
