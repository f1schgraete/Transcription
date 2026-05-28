package com.siptranscribe.app

import android.util.Log
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider
import com.google.api.gax.rpc.ApiStreamObserver
import com.google.api.gax.rpc.BidiStreamingCallable
import com.google.cloud.speech.v2.ExplicitDecodingConfig
import com.google.cloud.speech.v2.RecognitionConfig
import com.google.cloud.speech.v2.RecognitionFeatures
import com.google.cloud.speech.v2.SpeechClient
import com.google.cloud.speech.v2.SpeechSettings
import com.google.cloud.speech.v2.StreamingRecognitionConfig
import com.google.cloud.speech.v2.StreamingRecognitionFeatures
import com.google.cloud.speech.v2.StreamingRecognizeRequest
import com.google.cloud.speech.v2.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming Google Cloud Speech-to-Text v2 implementation.
 *
 * v2 over v1: the original v1 streaming endpoint silently ignores
 * `enable_separate_recognition_per_channel`, so stereo streams were
 * folded and emitted late. v2's `RecognitionFeatures.multi_channel_mode`
 * = `SEPARATE_RECOGNITION_PER_CHANNEL` is honoured by the streaming
 * call, so we can send **one** stereo PCM stream (L = remote, R =
 * local — produced by [StereoMerger]) and Google returns interim +
 * final results tagged with `channelTag` 1 / 2 for each side. One
 * gRPC connection, one configuration message, results per channel.
 *
 * Pipeline:
 *  1. [prepare] builds a v2 [SpeechClient] over grpc-okhttp (the
 *     Android-friendly gRPC transport). Auth is via the user's API
 *     key, attached as `x-goog-api-key` metadata on every call; no
 *     service-account JSON is needed.
 *  2. A worker thread opens a `StreamingRecognize` bidi stream, sends
 *     the first request containing the recognizer name and the
 *     [StreamingRecognitionConfig] (LINEAR16, 2 channels, `telephony`
 *     model, multi-channel = SEPARATE_RECOGNITION_PER_CHANNEL,
 *     `interim_results` on), then pulls audio chunks off [pcmQueue]
 *     and pushes them as `audio` payloads.
 *  3. The response observer forwards each result through [onResult]
 *     with the `channelTag` (as a string) as the speakerId argument.
 *     [TranscriptionManager] maps "1" → "Anrufer" and "2" → "Ich".
 *  4. Google's streams cap at roughly five minutes; the worker
 *     transparently re-opens a fresh stream when the previous one
 *     closes while the caller hasn't asked us to stop.
 *
 * **Required setup**: the recognizer field of every v2 streaming
 * request must be a path of the form
 * `projects/<PROJECT_ID>/locations/global/recognizers/_`. The trailing
 * underscore is the "ad-hoc" recognizer that just uses the inline
 * config we send, so no recognizer resource needs to be created on
 * the Google side — but the **project id** is unavoidable and has to
 * come from the user's Google Cloud project (Settings →
 * "Google Cloud Projekt-ID" field).
 */
class GoogleSttEngine(
    private val apiKey: String,
    private val projectId: String,
    private val languageCode: String
) : SttEngine {

    override var onResult: ((String, Boolean, String?) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val pcmQueue = LinkedBlockingQueue<ByteArray>()
    /** End-of-stream sentinel pushed by [stop]. */
    private val stopSignal = ByteArray(0)
    private val stopRequested = AtomicBoolean(false)

    private var sampleRate: Int = 8000
    private var senderThread: Thread? = null
    private var client: SpeechClient? = null

    override fun prepare(sampleRate: Int) {
        if (apiKey.isBlank()) {
            onError?.invoke("Google STT: API-Schlüssel fehlt")
            return
        }
        if (projectId.isBlank()) {
            onError?.invoke("Google STT: Projekt-ID fehlt")
            return
        }
        if (senderThread != null) {
            this.sampleRate = sampleRate
            return
        }
        this.sampleRate = sampleRate
        stopRequested.set(false)
        senderThread = Thread({ runStreamingLoop() }, "GoogleSttEngine-sender").also { it.start() }
    }

    override fun feed(pcm: ByteArray, length: Int) {
        if (length <= 0) return
        if (stopRequested.get()) return
        val chunk = if (length == pcm.size) pcm.copyOf() else pcm.copyOf(length)
        pcmQueue.offer(chunk)
    }

    override fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        pcmQueue.offer(stopSignal)
        try { senderThread?.join(2_000) } catch (_: InterruptedException) {}
        senderThread = null
        try { client?.close() } catch (_: Exception) {}
        client = null
        pcmQueue.clear()
    }

    private fun runStreamingLoop() {
        try {
            client = buildClient()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't build SpeechClient", e)
            onError?.invoke("Google STT init: ${e.message}")
            return
        }
        val callable: BidiStreamingCallable<StreamingRecognizeRequest, StreamingRecognizeResponse> =
            client!!.streamingRecognizeCallable()
        val recognizer = "projects/$projectId/locations/global/recognizers/_"

        while (!stopRequested.get()) {
            val streamClosed = AtomicBoolean(false)
            val responseObserver = createResponseObserver(streamClosed)
            val request = try {
                callable.bidiStreamingCall(responseObserver)
            } catch (e: Exception) {
                Log.e(TAG, "bidiStreamingCall failed", e)
                onError?.invoke("Google STT: ${e.message}")
                return
            }

            try {
                request.onNext(buildConfigRequest(recognizer))
            } catch (e: Exception) {
                Log.e(TAG, "Sending config failed", e)
                onError?.invoke("Google STT config: ${e.message}")
                return
            }

            while (!stopRequested.get() && !streamClosed.get()) {
                val chunk = try {
                    pcmQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue
                if (chunk === stopSignal) break
                try {
                    // v2 streaming uses `audio` (raw bytes), not `audio_content`.
                    request.onNext(
                        StreamingRecognizeRequest.newBuilder()
                            .setRecognizer(recognizer)
                            .setAudio(ByteString.copyFrom(chunk))
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "onNext audio failed (will reopen stream): ${e.message}")
                    break
                }
            }

            try { request.onCompleted() } catch (_: Exception) {}
            if (!stopRequested.get()) {
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun buildClient(): SpeechClient {
        val keyInterceptor = ApiKeyInterceptor(apiKey)
        val channelProvider = InstantiatingGrpcChannelProvider.newBuilder()
            .setEndpoint("speech.googleapis.com:443")
            .setInterceptorProvider { listOf(keyInterceptor) }
            .build()
        val settings = SpeechSettings.newBuilder()
            .setCredentialsProvider(NoCredentialsProvider.create())
            .setTransportChannelProvider(channelProvider)
            .build()
        return SpeechClient.create(settings)
    }

    private fun buildConfigRequest(recognizer: String): StreamingRecognizeRequest {
        // `telephony` is the phone-optimised narrowband model in v2.
        // Works at both 8 kHz (G.711) and 16 kHz (G.722/wideband), which
        // covers every codec Linphone can negotiate for us.
        val decoding = ExplicitDecodingConfig.newBuilder()
            .setEncoding(ExplicitDecodingConfig.AudioEncoding.LINEAR16)
            .setSampleRateHertz(sampleRate)
            .setAudioChannelCount(2)
            .build()
        val features = RecognitionFeatures.newBuilder()
            // The whole reason we're on v2: this flag actually streams.
            // v1 streaming silently ignores its equivalent.
            .setMultiChannelMode(
                RecognitionFeatures.MultiChannelMode.SEPARATE_RECOGNITION_PER_CHANNEL
            )
            .setEnableAutomaticPunctuation(true)
            .build()
        val config = RecognitionConfig.newBuilder()
            .setExplicitDecodingConfig(decoding)
            .addLanguageCodes(languageCode)
            .setModel("telephony")
            .setFeatures(features)
            .build()
        val streamingFeatures = StreamingRecognitionFeatures.newBuilder()
            .setInterimResults(true)
            .build()
        val streamingConfig = StreamingRecognitionConfig.newBuilder()
            .setConfig(config)
            .setStreamingFeatures(streamingFeatures)
            .build()
        return StreamingRecognizeRequest.newBuilder()
            .setRecognizer(recognizer)
            .setStreamingConfig(streamingConfig)
            .build()
    }

    private fun createResponseObserver(
        streamClosed: AtomicBoolean
    ): ApiStreamObserver<StreamingRecognizeResponse> {
        return object : ApiStreamObserver<StreamingRecognizeResponse> {
            override fun onNext(value: StreamingRecognizeResponse) {
                for (result in value.resultsList) {
                    val alt = result.alternativesList.firstOrNull() ?: continue
                    val text = alt.transcript ?: continue
                    if (text.isBlank()) continue
                    // channelTag is 1-based. StereoMerger writes L = remote
                    // (channelTag = 1) and R = local (channelTag = 2).
                    val tag = result.channelTag.toString()
                    Log.d(TAG, "result: ch=$tag final=${result.isFinal} text=\"$text\"")
                    onResult?.invoke(text, result.isFinal, tag)
                }
            }
            override fun onError(t: Throwable) {
                Log.w(TAG, "Stream error: ${t.message}")
                streamClosed.set(true)
                onError?.invoke("Google STT: ${t.message}")
            }
            override fun onCompleted() {
                Log.i(TAG, "Stream completed by server")
                streamClosed.set(true)
            }
        }
    }

    /**
     * Adds `x-goog-api-key: <key>` to every gRPC call. The Speech v2
     * service accepts API-key auth this way, no service-account JSON
     * needed.
     */
    private class ApiKeyInterceptor(private val apiKey: String) : ClientInterceptor {
        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel
        ): ClientCall<ReqT, RespT> {
            return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)
            ) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    headers.put(API_KEY_HEADER, apiKey)
                    super.start(responseListener, headers)
                }
            }
        }
        companion object {
            private val API_KEY_HEADER: Metadata.Key<String> =
                Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)
        }
    }

    companion object {
        private const val TAG = "GoogleSttEngine"
    }
}
