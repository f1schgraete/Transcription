package com.siptranscribe.app

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Orchestrates call audio reading ([CallAudioRecorder]) and cloud STT ([AzureSttEngine]).
 *
 * Usage:
 *  1. Call [start] with the path to the WAV file that Linphone is recording.
 *  2. [onTranscription] fires on the main thread with (text, isFinal).
 *  3. Call [stop] when the call ends.
 */
class TranscriptionManager(private val context: Context) {

    companion object {
        private const val TAG = "TranscriptionManager"
    }

    private val sttEngine: SttEngine = run {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        AzureSttEngine(
            endpoint = prefs.getString(MainActivity.KEY_AZURE_ENDPOINT, "") ?: "",
            apiKey   = prefs.getString(MainActivity.KEY_AZURE_KEY, "") ?: ""
        )
    }
    private val recorder = CallAudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread with (text, isFinal, speakerId). */
    var onTranscription: ((String, Boolean, String?) -> Unit)? = null

    /** Called on the main thread when a non-recoverable error occurs. */
    var onError: ((String) -> Unit)? = null

    fun start(recordingFilePath: String, sampleRate: Int) {
        Log.i(TAG, "start($recordingFilePath, $sampleRate Hz)")

        sttEngine.onResult = { text, isFinal, speakerId ->
            mainHandler.post { onTranscription?.invoke(text, isFinal, speakerId) }
        }
        sttEngine.onError = { msg ->
            Log.e(TAG, "STT error: $msg")
            mainHandler.post { onError?.invoke(msg) }
        }

        recorder.onSampleRate = { rate ->
            Log.i(TAG, "Recorder: sample rate $rate Hz → preparing STT engine")
            sttEngine.prepare(rate)
        }
        recorder.onPcmData = { data, length ->
            sttEngine.feed(data, length)
        }
        recorder.onError = { msg ->
            Log.e(TAG, "Recorder error: $msg")
            mainHandler.post { onError?.invoke(msg) }
        }

        recorder.start(recordingFilePath, sampleRate)
    }

    fun stop() {
        Log.i(TAG, "stop()")
        recorder.stop()
        sttEngine.stop()
    }
}
