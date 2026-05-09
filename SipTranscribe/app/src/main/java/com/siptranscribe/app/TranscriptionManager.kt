package com.siptranscribe.app

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Orchestrates per-direction call audio reading and cloud STT for a single call.
 *
 * Split-recording flow (requires the locally built linphone-sdk AAR with our
 * mediastreamer2 patch and `LinphoneManager.enableSplitRecording(true)`):
 *  1. Linphone writes two WAVs — one for the local mic (uplink), one for the
 *     remote (downlink) — at the paths derived from the original recordFile.
 *  2. Each path is tailed by its own [CallAudioRecorder].
 *  3. PCM is fed to its own [AzureSttEngine]. Results are emitted with a fixed
 *     speaker label ("Ich" for local, "Anrufer" for remote) — channel == speaker,
 *     so no diarization is needed.
 */
class TranscriptionManager(private val context: Context) {

    companion object {
        private const val TAG = "TranscriptionManager"
        const val LABEL_LOCAL = "Ich"
        const val LABEL_REMOTE = "Anrufer"
    }

    private val ulEngine: SttEngine = newEngine()
    private val dlEngine: SttEngine = newEngine()
    private val ulRecorder = CallAudioRecorder()
    private val dlRecorder = CallAudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread with (text, isFinal, speakerLabel). */
    var onTranscription: ((String, Boolean, String?) -> Unit)? = null

    /** Called on the main thread when a non-recoverable error occurs. */
    var onError: ((String) -> Unit)? = null

    private fun newEngine(): SttEngine {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        return AzureSttEngine(
            endpoint = prefs.getString(MainActivity.KEY_AZURE_ENDPOINT, "") ?: "",
            apiKey   = prefs.getString(MainActivity.KEY_AZURE_KEY, "") ?: ""
        )
    }

    fun start(uplinkPath: String, downlinkPath: String, sampleRate: Int) {
        Log.i(TAG, "start(ul=$uplinkPath, dl=$downlinkPath, $sampleRate Hz)")
        wire(ulEngine, ulRecorder, uplinkPath,   sampleRate, LABEL_LOCAL)
        wire(dlEngine, dlRecorder, downlinkPath, sampleRate, LABEL_REMOTE)
    }

    private fun wire(
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

    fun stop() {
        Log.i(TAG, "stop()")
        ulRecorder.stop()
        dlRecorder.stop()
        ulEngine.stop()
        dlEngine.stop()
    }
}
