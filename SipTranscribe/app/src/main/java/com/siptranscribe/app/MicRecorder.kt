package com.siptranscribe.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures raw microphone audio for the "Zuhören" (in-person listening)
 * feature and emits 16-bit signed little-endian mono PCM chunks via
 * [onPcmData] — the exact format [SttEngine.feed] expects.
 *
 * This is deliberately separate from the call pipeline: there's no SIP
 * call, no Linphone, and no WAV-tailing. We read straight off the mic on
 * a background thread and hand each buffer to whatever STT engine the
 * caller wires up.
 *
 * The audio source is [MediaRecorder.AudioSource.VOICE_RECOGNITION], which
 * applies the platform's noise suppression / AGC tuned for speech — a good
 * fit for understanding a guest across a room.
 */
class MicRecorder(val sampleRate: Int = 16000) {

    /** (buffer, validBytes) on the recording thread. The buffer is reused
     *  between callbacks, so consumers must copy if they retain it; the
     *  Azure push stream copies synchronously, so feeding directly is safe. */
    var onPcmData: ((ByteArray, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "MicRecorder").also { it.start() }
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            running = false
            onError?.invoke("Mikrofon nicht verfügbar")
            return
        }
        // ~200 ms of audio per read keeps latency low without thrashing.
        val bufSize = maxOf(minBuf, sampleRate * 2 / 5)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: SecurityException) {
            running = false
            onError?.invoke("Mikrofon-Berechtigung fehlt")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running = false
            onError?.invoke("Mikrofon konnte nicht gestartet werden")
            return
        }
        val buffer = ByteArray(bufSize)
        try {
            record.startRecording()
            Log.i(TAG, "recording started @ $sampleRate Hz, buf=$bufSize")
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    onPcmData?.invoke(buffer, n)
                } else if (n < 0) {
                    onError?.invoke("Mikrofon-Lesefehler ($n)")
                    break
                }
            }
        } catch (e: Exception) {
            onError?.invoke("Mikrofon: ${e.message}")
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            Log.i(TAG, "recording stopped")
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
    }

    companion object {
        private const val TAG = "MicRecorder"
    }
}
