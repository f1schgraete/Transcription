package com.siptranscribe.app

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Orchestrates per-direction call audio reading and cloud STT for a single call.
 *
 * Two recognition strategies are supported, picked by KEY_STT_PROVIDER:
 *
 * **Azure (default)** — one [AzureSttEngine] per call leg. Each mono WAV
 * is tailed independently and pushed to its own recognizer; results get
 * the channel's fixed speaker label ("Ich" for local, "Anrufer" for
 * remote), so Azure's own diarization is ignored.
 *
 * **Google** — one [GoogleSttEngine] consuming a stereo stream. A
 * [StereoMerger] interleaves the two mono recorders' PCM into L=remote /
 * R=local stereo frames. Google's `enableSeparateRecognitionPerChannel`
 * recognises each channel separately and tags the result with a channel
 * id we map back to the speaker label.
 *
 * Split-recording (requires our patched linphone-sdk AAR and
 * `LinphoneManager.enableSplitRecording(true)`) supplies the two mono
 * WAVs regardless of the chosen provider.
 */
class TranscriptionManager(private val context: Context) {

    companion object {
        private const val TAG = "TranscriptionManager"
        const val LABEL_LOCAL = "Ich"
        const val LABEL_REMOTE = "Anrufer"
    }

    private val provider = readProvider()
    private val ulRecorder = CallAudioRecorder()
    private val dlRecorder = CallAudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Per-direction Azure engines — only created in Azure mode.
    private val ulEngine: SttEngine? = if (provider == MainActivity.STT_PROVIDER_GOOGLE) null else newAzureEngine()
    private val dlEngine: SttEngine? = if (provider == MainActivity.STT_PROVIDER_GOOGLE) null else newAzureEngine()

    // Single Google engine + stereo merger — only created in Google mode.
    private val googleEngine: SttEngine? = if (provider == MainActivity.STT_PROVIDER_GOOGLE) newGoogleEngine() else null
    private val stereoMerger: StereoMerger? = if (provider == MainActivity.STT_PROVIDER_GOOGLE) StereoMerger() else null

    /** Called on the main thread with (text, isFinal, speakerLabel). */
    var onTranscription: ((String, Boolean, String?) -> Unit)? = null

    /** Called on the main thread when a non-recoverable error occurs. */
    var onError: ((String) -> Unit)? = null

    private fun readProvider(): String {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        return prefs.getString(MainActivity.KEY_STT_PROVIDER, MainActivity.STT_PROVIDER_AZURE)
            ?: MainActivity.STT_PROVIDER_AZURE
    }

    private fun newAzureEngine(): SttEngine {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        return AzureSttEngine(
            endpoint = prefs.getString(MainActivity.KEY_AZURE_ENDPOINT, "") ?: "",
            apiKey   = prefs.getString(MainActivity.KEY_AZURE_KEY, "") ?: ""
        )
    }

    private fun newGoogleEngine(): SttEngine {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        return GoogleSttEngine(
            apiKey = prefs.getString(MainActivity.KEY_GOOGLE_STT_KEY, "") ?: "",
            languageCode = prefs.getString(
                MainActivity.KEY_GOOGLE_STT_LANGUAGE,
                MainActivity.DEFAULT_GOOGLE_STT_LANGUAGE
            ) ?: MainActivity.DEFAULT_GOOGLE_STT_LANGUAGE
        )
    }

    fun start(uplinkPath: String, downlinkPath: String, sampleRate: Int) {
        Log.i(TAG, "start(provider=$provider, ul=$uplinkPath, dl=$downlinkPath, $sampleRate Hz)")
        if (provider == MainActivity.STT_PROVIDER_GOOGLE) {
            startGoogle(uplinkPath, downlinkPath, sampleRate)
        } else {
            startAzure(uplinkPath, downlinkPath, sampleRate)
        }
    }

    private fun startAzure(uplinkPath: String, downlinkPath: String, sampleRate: Int) {
        wireAzure(ulEngine!!, ulRecorder, uplinkPath,   sampleRate, LABEL_LOCAL)
        wireAzure(dlEngine!!, dlRecorder, downlinkPath, sampleRate, LABEL_REMOTE)
    }

    private fun wireAzure(
        engine: SttEngine,
        recorder: CallAudioRecorder,
        path: String,
        sampleRate: Int,
        label: String
    ) {
        engine.onResult = { text, isFinal, _ ->
            // Channel is the speaker — override Azure's diarization label.
            mainHandler.post { onTranscription?.invoke(text, isFinal, label) }
        }
        engine.onError = { msg ->
            Log.e(TAG, "STT error [$label]: $msg")
            mainHandler.post { onError?.invoke(msg) }
        }
        recorder.onSampleRate = { rate ->
            Log.i(TAG, "[$label] recorder sample rate $rate Hz → preparing STT")
            engine.prepare(rate)
        }
        recorder.onPcmData = { data, length ->
            engine.feed(data, length)
        }
        recorder.onError = { msg ->
            Log.e(TAG, "Recorder error [$label]: $msg")
            mainHandler.post { onError?.invoke(msg) }
        }
        recorder.start(path, sampleRate)
    }

    private fun startGoogle(uplinkPath: String, downlinkPath: String, sampleRate: Int) {
        val engine = googleEngine!!
        val merger = stereoMerger!!

        // Google's `enableSeparateRecognitionPerChannel` ties channel
        // ordering to the request payload: we put downlink (remote
        // = the caller) on the left so channelTag 1 → "Anrufer", and
        // uplink (local) on the right so channelTag 2 → "Ich". The
        // engine maps tags back to labels in its onResult callback.
        engine.onResult = { text, isFinal, speakerId ->
            val label = when (speakerId) {
                "1" -> LABEL_REMOTE
                "2" -> LABEL_LOCAL
                else -> speakerId
            }
            mainHandler.post { onTranscription?.invoke(text, isFinal, label) }
        }
        engine.onError = { msg ->
            Log.e(TAG, "Google STT error: $msg")
            mainHandler.post { onError?.invoke(msg) }
        }

        merger.onStereoPcm = { data, length -> engine.feed(data, length) }

        // We have to prepare the engine once we know the sample rate;
        // both recorders read the same sample rate from the same call,
        // so the first onSampleRate firing is fine.
        var prepared = false
        val prepareIfNeeded: (Int) -> Unit = { rate ->
            if (!prepared) {
                prepared = true
                Log.i(TAG, "Google STT prepare at $rate Hz")
                engine.prepare(rate)
            }
        }

        dlRecorder.onSampleRate = prepareIfNeeded
        dlRecorder.onPcmData = { data, length -> merger.feedLeft(data, length) }
        dlRecorder.onError = { msg ->
            Log.e(TAG, "Recorder error [dl]: $msg")
            mainHandler.post { onError?.invoke(msg) }
        }

        ulRecorder.onSampleRate = prepareIfNeeded
        ulRecorder.onPcmData = { data, length -> merger.feedRight(data, length) }
        ulRecorder.onError = { msg ->
            Log.e(TAG, "Recorder error [ul]: $msg")
            mainHandler.post { onError?.invoke(msg) }
        }

        dlRecorder.start(downlinkPath, sampleRate)
        ulRecorder.start(uplinkPath, sampleRate)
    }

    fun stop() {
        Log.i(TAG, "stop()")
        ulRecorder.stop()
        dlRecorder.stop()
        ulEngine?.stop()
        dlEngine?.stop()
        googleEngine?.stop()
        stereoMerger?.reset()
    }
}
