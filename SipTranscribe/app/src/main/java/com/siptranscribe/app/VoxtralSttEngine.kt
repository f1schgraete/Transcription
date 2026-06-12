package com.siptranscribe.app

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mistral Voxtral *Realtime* implementation of [SttEngine].
 *
 * Streams 16-bit signed little-endian mono PCM to Mistral's realtime
 * transcription WebSocket and emits results through [onResult]. Like
 * [AzureSttEngine] it handles a single call leg, so [TranscriptionManager]
 * runs one instance per direction and tags each with a fixed speaker label
 * (Voxtral's own diarization isn't available alongside realtime anyway).
 *
 * **Wire protocol** (reverse-engineered from the `mistralai` Python SDK's
 * `extra/realtime` module, since the public docs only cover the SDK):
 *
 *  - Connect: `wss://api.mistral.ai/v1/audio/transcriptions/realtime?model=<model>`
 *  - Auth:    `Authorization: Bearer <apiKey>` on the handshake.
 *  - On open we send a `session.update` carrying the audio format
 *    (`pcm_s16le` + sample rate) and the target streaming delay. The format
 *    must be set *before* any audio — Mistral rejects format updates once
 *    audio has started.
 *  - Audio:   `{"type":"input_audio.append","audio":"<base64 PCM>"}`. Each
 *    message's decoded payload must stay under 256 KiB ([MAX_APPEND_BYTES]).
 *  - End:     `{"type":"input_audio.end"}` on stop.
 *  - Results: the server streams `transcription.text.delta` events whose
 *    `text` field is an *incremental* fragment (not a full running
 *    hypothesis). We accumulate fragments and split them into finalized
 *    turns at sentence boundaries — see [onDelta] — because [CallActivity]
 *    expects the (partial = full pending line, final = committed turn)
 *    shape that Azure/Google produce. `transcription.done` flushes whatever
 *    remains.
 *
 * Language is auto-detected by Voxtral (there is no language field on the
 * session); a `transcription.language` event announces the detected one,
 * which we only log.
 *
 * NOTE: this is a *test-grade* engine for the Swabian dialect bake-off. The
 * API key is read from SharedPreferences and lives on the device — that's
 * deliberately temporary. Once Voxtral proves itself the key moves behind
 * the backend proxy and the tablet talks to that instead of api.mistral.ai.
 */
class VoxtralSttEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val targetStreamingDelayMs: Int = DEFAULT_DELAY_MS,
    private val baseHost: String = DEFAULT_HOST
) : SttEngine {

    companion object {
        private const val TAG = "VoxtralSttEngine"
        const val DEFAULT_MODEL = "voxtral-mini-transcribe-realtime-2602"
        const val DEFAULT_DELAY_MS = 480
        private const val DEFAULT_HOST = "api.mistral.ai"

        /** Max decoded bytes per `input_audio.append` (256 KiB server cap). */
        private const val MAX_APPEND_BYTES = 240 * 1024

        private const val ENCODING_PCM_S16LE = "pcm_s16le"

        /** Characters that end a sentence — used to commit a finalized turn. */
        private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '…')
    }

    override var onResult: ((String, Boolean, String?) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    @Volatile private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    /** True once the server has accepted the session and audio may flow. */
    private val sessionReady = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    /** PCM queued before the session is ready; drained on session.created. */
    private val pending = ConcurrentLinkedQueue<ByteArray>()

    /** Accumulates delta fragments until a sentence boundary commits them. */
    private val lineBuffer = StringBuilder()

    override fun prepare(sampleRate: Int) {
        if (apiKey.isBlank()) {
            onError?.invoke("Voxtral: API-Schlüssel fehlt")
            return
        }
        if (webSocket != null) return

        Log.i(TAG, "prepare($sampleRate Hz) model=$model delay=${targetStreamingDelayMs}ms")

        val http = OkHttpClient.Builder()
            // No read timeout: the socket is idle between the user's words.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
            .also { client = it }

        val url = "wss://$baseHost/v1/audio/transcriptions/realtime?model=$model"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        webSocket = http.newWebSocket(request, Listener(sampleRate))
    }

    override fun feed(pcm: ByteArray, length: Int) {
        if (length <= 0 || stopRequested.get()) return
        val chunk = if (length == pcm.size) pcm.copyOf() else pcm.copyOf(length)
        if (sessionReady.get()) {
            sendAudio(chunk)
        } else {
            // Hold until the session is accepted; format must precede audio.
            pending.offer(chunk)
        }
    }

    override fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        Log.i(TAG, "stop()")
        val ws = webSocket
        webSocket = null
        try {
            ws?.send(JSONObject().put("type", "input_audio.end").toString())
        } catch (e: Exception) {
            Log.w(TAG, "input_audio.end failed", e)
        }
        // Commit whatever we were still building so it isn't lost.
        flushLine()
        try { ws?.close(1000, "client stop") } catch (_: Exception) {}
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        client = null
        pending.clear()
    }

    private fun sendAudio(pcm: ByteArray) {
        val ws = webSocket ?: return
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + MAX_APPEND_BYTES, pcm.size)
            val slice = if (offset == 0 && end == pcm.size) pcm else pcm.copyOfRange(offset, end)
            val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
            try {
                ws.send(JSONObject().put("type", "input_audio.append").put("audio", b64).toString())
            } catch (e: Exception) {
                Log.w(TAG, "append failed", e)
                return
            }
            offset = end
        }
    }

    /**
     * Accumulate an incremental delta and emit results. Completed sentences
     * are committed as finals (isFinal = true); the still-growing tail is
     * emitted as the current partial (isFinal = false).
     */
    private fun onDelta(fragment: String) {
        if (fragment.isEmpty()) return
        lineBuffer.append(fragment)

        // Commit every complete sentence sitting in the buffer.
        var cut = lastSentenceCut()
        while (cut > 0) {
            val sentence = lineBuffer.substring(0, cut).trim()
            lineBuffer.delete(0, cut)
            if (sentence.isNotEmpty()) onResult?.invoke(sentence, true, null)
            cut = lastSentenceCut()
        }

        val tail = lineBuffer.toString().trim()
        onResult?.invoke(tail, false, null)
    }

    /**
     * Index just past the last sentence-ending punctuation in [lineBuffer]
     * (0 if none), so `substring(0, cut)` is the committable prefix.
     */
    private fun lastSentenceCut(): Int {
        for (i in lineBuffer.length - 1 downTo 0) {
            if (lineBuffer[i] in SENTENCE_ENDINGS) return i + 1
        }
        return 0
    }

    /** Commit any buffered text as a final turn (used on done / stop). */
    private fun flushLine() {
        val remaining = lineBuffer.toString().trim()
        lineBuffer.setLength(0)
        if (remaining.isNotEmpty()) onResult?.invoke(remaining, true, null)
    }

    private inner class Listener(private val sampleRate: Int) : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket open; sending session.update")
            val audioFormat = JSONObject()
                .put("encoding", ENCODING_PCM_S16LE)
                .put("sample_rate", sampleRate)
            val session = JSONObject()
                .put("audio_format", audioFormat)
                .put("target_streaming_delay_ms", targetStreamingDelayMs)
            val msg = JSONObject()
                .put("type", "session.update")
                .put("session", session)
            ws.send(msg.toString())
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                Log.w(TAG, "Non-JSON message: $text")
                return
            }
            when (val type = json.optString("type")) {
                "session.created", "session.updated" -> {
                    Log.i(TAG, "session ready ($type)")
                    if (sessionReady.compareAndSet(false, true)) {
                        // Flush any audio captured during the handshake.
                        while (true) {
                            val chunk = pending.poll() ?: break
                            sendAudio(chunk)
                        }
                    }
                }
                "transcription.text.delta" -> onDelta(json.optString("text"))
                "transcription.segment" -> {
                    // Finalized segment with timing. We already build finals
                    // from the delta stream, so this is informational only.
                    Log.d(TAG, "segment: ${json.optString("text")}")
                }
                "transcription.language" ->
                    Log.i(TAG, "detected language: ${json.optString("language")}")
                "transcription.done" -> {
                    Log.i(TAG, "transcription.done")
                    flushLine()
                }
                "error" -> {
                    val detail = json.optJSONObject("error")?.optString("message")
                        ?: json.optString("message", text)
                    Log.e(TAG, "server error: $detail")
                    onError?.invoke("Voxtral: $detail")
                }
                else -> Log.d(TAG, "unhandled message type: $type")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (stopRequested.get()) return
            val code = response?.code
            Log.e(TAG, "WebSocket failure (http=$code)", t)
            onError?.invoke("Voxtral: ${t.message ?: "Verbindungsfehler"}${if (code != null) " (HTTP $code)" else ""}")
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
        }
    }
}
