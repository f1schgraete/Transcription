package com.siptranscribe.app

import android.util.Log

/**
 * Skeleton implementation of an [SttEngine] backed by Google Cloud
 * Speech-to-Text streaming recognition.
 *
 * The intended runtime uses Google's `enableSeparateRecognitionPerChannel`
 * option on a stereo input — left channel = remote party, right channel =
 * local user — so we send one stream instead of two and let Google's
 * server tag each result with its `channelTag`. The wrapper would then
 * map channelTag 1 → [TranscriptionManager.LABEL_REMOTE] and channelTag
 * 2 → [TranscriptionManager.LABEL_LOCAL].
 *
 * **This is currently a placeholder.** The actual streaming gRPC client
 * isn't wired yet — completing the implementation needs:
 *
 *  1. Add the gRPC streaming dependency (e.g.
 *     `com.google.cloud:google-cloud-speech:4.34.0`) and gRPC-OkHttp
 *     transport so it works on Android without TLS surprises.
 *  2. Authenticate using the API key from `apiKey` — for non-service-
 *     account credentials, the simplest path is to attach the key via
 *     `x-goog-api-key` metadata on the gRPC channel.
 *  3. Open a `StreamingRecognize` bidi stream with
 *     `StreamingRecognitionConfig`:
 *       - `RecognitionConfig.encoding = LINEAR16`
 *       - `RecognitionConfig.sampleRateHertz = <sampleRate>`
 *       - `RecognitionConfig.audioChannelCount = 2`
 *       - `RecognitionConfig.enableSeparateRecognitionPerChannel = true`
 *       - `RecognitionConfig.languageCode = languageCode`
 *       - `interimResults = true`
 *  4. In [feed], push the stereo PCM chunks onto the request stream.
 *  5. Map response messages into [onResult] callbacks, using
 *     `result.channelTag` to derive the speaker label.
 *  6. In [stop], close the request stream and wait for finalisation.
 *
 * Until that work lands, instantiating this engine fires an [onError]
 * message so the caller (TranscriptionManager) can surface a clear
 * "Google STT is not yet implemented" toast rather than failing silently.
 */
class GoogleSttEngine(
    private val apiKey: String,
    private val languageCode: String
) : SttEngine {

    override var onResult: ((String, Boolean, String?) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private var hasReportedScaffoldNotice = false

    override fun prepare(sampleRate: Int) {
        Log.i(TAG, "prepare($sampleRate Hz, lang=$languageCode)")
        emitScaffoldNoticeOnce()
    }

    override fun feed(pcm: ByteArray, length: Int) {
        // Real implementation will push the stereo PCM onto the request
        // stream here. For now we drop the audio on the floor and rely on
        // the scaffold notice fired during prepare().
    }

    override fun stop() {
        Log.i(TAG, "stop()")
    }

    private fun emitScaffoldNoticeOnce() {
        if (hasReportedScaffoldNotice) return
        hasReportedScaffoldNotice = true
        if (apiKey.isBlank()) {
            onError?.invoke("Google STT: Kein API-Schlüssel hinterlegt")
        } else {
            onError?.invoke(
                "Google STT noch nicht aktiv — bitte vorerst Azure verwenden " +
                    "(GoogleSttEngine.kt enthält die offene Implementierung)."
            )
        }
    }

    companion object {
        private const val TAG = "GoogleSttEngine"
    }
}
