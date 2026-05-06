package com.siptranscribe.app

/**
 * Abstraction over any speech-to-text backend that accepts raw PCM.
 *
 * Contract:
 *  1. Call [prepare] once the sample rate is known (read from the WAV header).
 *  2. Call [feed] repeatedly with 16-bit signed little-endian PCM chunks.
 *  3. Call [stop] when the call ends.
 *
 * Current implementation: AzureSttEngine.
 */
interface SttEngine {

    /** Called once the WAV sample-rate is known. Creates the internal recognizer. */
    fun prepare(sampleRate: Int)

    /** Feed a chunk of 16-bit signed little-endian PCM audio. */
    fun feed(pcm: ByteArray, length: Int)

    /** Release all resources. */
    fun stop()

    /**
     * Fired on the thread that calls [feed].
     * (text, isFinal, speakerId) — speakerId is null when the engine doesn't
     * provide diarisation, "Unknown" while Azure is still warming up, or e.g.
     * "Guest-1" / "Guest-2" once it has separated speakers.
     */
    var onResult: ((String, Boolean, String?) -> Unit)?

    /** Fired when a non-recoverable error occurs. */
    var onError: ((String) -> Unit)?
}
