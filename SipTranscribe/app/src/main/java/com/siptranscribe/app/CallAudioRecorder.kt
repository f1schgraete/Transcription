package com.siptranscribe.app

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads a growing WAV file written by Linphone call recording.
 *
 * Linphone reserves the first 44 bytes for the WAV header but only fills it in
 * when [Call.stopRecording] runs — during the call those bytes are zero, so the
 * sample rate cannot be read from the file. Callers must pass it in explicitly.
 *
 * Lifecycle:
 *  1. [start] kicks off a background thread that waits for the file to appear.
 *  2. Once the file has at least 44 bytes, [onSampleRate] fires exactly once.
 *  3. [onPcmData] fires repeatedly with PCM chunks (16-bit signed little-endian).
 *  4. [stop] signals the thread to exit cleanly.
 */
class CallAudioRecorder {

    companion object {
        private const val TAG = "CallAudioRecorder"

        // Linphone reserves 44 bytes for the WAV header. PCM data begins at byte 44.
        private const val WAV_HEADER_BYTES = 44
        private const val PCM_DATA_OFFSET = 44L

        private const val POLL_INTERVAL_MS = 50L
        private const val CHUNK_BYTES = 8192
    }

    /** Called exactly once with the sample rate provided to [start]. */
    var onSampleRate: ((Int) -> Unit)? = null

    /** Called repeatedly with raw PCM bytes. Length is the number of valid bytes in the array. */
    var onPcmData: ((ByteArray, Int) -> Unit)? = null

    /** Called if a non-recoverable I/O error occurs. */
    var onError: ((String) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(filePath: String, sampleRate: Int) {
        if (running) {
            Log.w(TAG, "start() called while already running")
            return
        }
        running = true
        thread = Thread({ readLoop(filePath, sampleRate) }, "CallAudioRecorder").also { it.start() }
        Log.i(TAG, "started – waiting for $filePath at $sampleRate Hz")
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        // Join so the read loop has actually exited before we return. Without
        // this, TranscriptionManager.stop() would null/close the STT push
        // stream while this thread is still mid-iteration and could call
        // onPcmData → engine.feed() on a closed stream, and a late recognizer
        // result could leak into a recycled call's transcript. The loop only
        // ever blocks on short (≤100 ms) sleeps and a local-file read, and we
        // interrupt it, so the join returns promptly.
        t?.interrupt()
        try { t?.join(500) } catch (_: InterruptedException) {}
        Log.i(TAG, "stopped")
    }

    private fun readLoop(filePath: String, sampleRate: Int) {
        val file = File(filePath)

        // 1. Wait for Linphone to create the file
        while (running && !file.exists()) {
            Log.d(TAG, "Waiting for file to appear: $filePath")
            safeSleep(100)
        }
        if (!running) return

        try {
            RandomAccessFile(file, "r").use { raf ->

                // 2. Wait until Linphone has reserved the 44-byte header slot.
                //    The header itself is zero-filled until stopRecording, but PCM data
                //    starts at byte 44 the moment Linphone begins writing samples.
                while (running && raf.length() < WAV_HEADER_BYTES) {
                    safeSleep(POLL_INTERVAL_MS)
                }
                if (!running) return

                Log.i(TAG, "Reserved header present, sample rate $sampleRate Hz")
                onSampleRate?.invoke(sampleRate)

                // 3. Stream PCM data from byte 44 onwards
                raf.seek(PCM_DATA_OFFSET)
                val buffer = ByteArray(CHUNK_BYTES)

                while (running) {
                    val fileLen = raf.length()
                    val pos = raf.filePointer
                    if (fileLen > pos) {
                        val toRead = minOf(CHUNK_BYTES.toLong(), fileLen - pos).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read > 0) {
                            onPcmData?.invoke(buffer, read)
                        }
                    } else {
                        // Linphone hasn't written new data yet; yield the thread
                        safeSleep(POLL_INTERVAL_MS)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "readLoop interrupted (normal shutdown)")
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "readLoop error", e)
                onError?.invoke("Aufnahme-Lesefehler: ${e.message}")
            }
        }

        Log.i(TAG, "readLoop ended")
    }

    private fun safeSleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
