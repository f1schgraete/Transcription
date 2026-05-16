package com.siptranscribe.app

import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Interleaves two mono 16-bit-PCM streams (left + right) into a single
 * stereo PCM stream and forwards complete frames to [onStereoPcm].
 *
 * Used by [TranscriptionManager] in Google-STT mode: each call leg is
 * Linphone's existing mono per-direction recording, and Google's
 * `enableSeparateRecognitionPerChannel` recognises the two channels of
 * the stereo input independently. The frame ordering it expects is
 * `L R L R L R …` (little-endian 16-bit samples), exactly what we emit.
 *
 * Synchronisation strategy:
 *  - Each side pushes its mono samples into a per-channel buffer.
 *  - On every push we drain `min(leftSamples, rightSamples)` whole frames
 *    and emit them interleaved.
 *  - If one channel runs ahead the surplus stays in its buffer until
 *    the other catches up. Linphone writes both files at the same sample
 *    rate from the same call's clock, so they only drift by the OS
 *    write-buffer wobble — single-frame granularity is enough.
 *
 * Not thread-safe by itself; pushes can come from arbitrary threads, so
 * each [feedLeft] / [feedRight] call grabs the lock.
 */
class StereoMerger {

    /** Receives complete stereo PCM frames. Length is always even (multiple of 4 bytes). */
    var onStereoPcm: ((ByteArray, Int) -> Unit)? = null

    private val lock = ReentrantLock()

    /** Pending mono PCM bytes per channel, FIFO. */
    private val leftQueue = ArrayDeque<Byte>()
    private val rightQueue = ArrayDeque<Byte>()

    fun feedLeft(pcm: ByteArray, length: Int) = feed(leftQueue, rightQueue, pcm, length, leftFirst = true)
    fun feedRight(pcm: ByteArray, length: Int) = feed(rightQueue, leftQueue, pcm, length, leftFirst = false)

    /**
     * Push [length] bytes from [pcm] into [own], then drain whatever
     * complete (left+right) sample pairs are now available across both
     * queues into a single interleaved buffer.
     *
     * leftFirst toggles which queue contributes the lower-addressed sample
     * of each stereo frame — the call always emits L then R regardless of
     * which side just pushed.
     */
    private fun feed(
        own: ArrayDeque<Byte>,
        other: ArrayDeque<Byte>,
        pcm: ByteArray,
        length: Int,
        @Suppress("UNUSED_PARAMETER") leftFirst: Boolean
    ) {
        if (length <= 0) return
        lock.withLock {
            for (i in 0 until length) own.addLast(pcm[i])

            // 16-bit samples = 2 bytes; whole frames need 2 bytes from each
            // channel, so drain in pairs of pairs.
            val pairs = minOf(leftQueue.size / 2, rightQueue.size / 2)
            if (pairs <= 0) return@withLock
            val out = ByteArray(pairs * 4)
            for (i in 0 until pairs) {
                val l0 = leftQueue.removeFirst(); val l1 = leftQueue.removeFirst()
                val r0 = rightQueue.removeFirst(); val r1 = rightQueue.removeFirst()
                val base = i * 4
                out[base] = l0; out[base + 1] = l1
                out[base + 2] = r0; out[base + 3] = r1
            }
            onStereoPcm?.invoke(out, out.size)
        }
    }

    fun reset() {
        lock.withLock {
            leftQueue.clear()
            rightQueue.clear()
        }
    }

    @Suppress("unused")
    fun debugDepth(): Pair<Int, Int> = lock.withLock { leftQueue.size to rightQueue.size }

    companion object {
        @Suppress("unused")
        private const val TAG = "StereoMerger"

        init {
            // Marker so Logcat hints existence of the new path on first load.
            Log.d(TAG, "StereoMerger ready for Google per-channel STT")
        }
    }
}
