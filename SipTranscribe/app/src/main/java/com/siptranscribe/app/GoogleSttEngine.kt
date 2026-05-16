package com.siptranscribe.app

import android.util.Log
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider
import com.google.api.gax.rpc.ApiStreamObserver
import com.google.api.gax.rpc.BidiStreamingCallable
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechSettings
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
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
 * Streaming Google Cloud Speech-to-Text implementation.
 *
 * Pipeline:
 *  1. [prepare] builds a [SpeechClient] using gRPC over OkHttp (the
 *     Android-friendly transport) and authenticates by attaching the
 *     user's API key on every call via `x-goog-api-key` metadata. No
 *     service-account JSON file is required.
 *  2. A worker thread opens a `StreamingRecognize` bidi stream, sends
 *     the [StreamingRecognitionConfig] (LINEAR16, stereo, per-channel
 *     recognition, `telephony` model — Google's narrowband-phone-call
 *     model), and then pulls audio chunks off the [pcmQueue] and pushes
 *     them as `StreamingRecognizeRequest` messages.
 *  3. The response observer forwards [StreamingRecognizeResponse]s back
 *     through [onResult]. The result's `channelTag` (1-based) is passed
 *     to the caller as the `speakerId` argument so
 *     [TranscriptionManager] can map it to "Anrufer" / "Ich".
 *  4. Google's streams time out after roughly five minutes. The worker
 *     transparently re-opens a fresh stream whenever the previous one
 *     closes while the caller hasn't asked us to stop, so longer calls
 *     keep transcribing with at most a short pause between streams.
 *
 * Audio expectations: the merger in [StereoMerger] hands us interleaved
 * stereo PCM (L = remote, R = local). We forward those raw bytes; we
 * never re-arrange channels.
 */
class GoogleSttEngine(
    private val apiKey: String,
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
        if (senderThread != null) {
            // Already running; just remember the new rate if the recorder
            // re-reports it (it shouldn't change mid-call).
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
        // The merger reuses its output buffer, so copy here.
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

    /**
     * Run streams back-to-back until [stop] is called. Each iteration opens
     * a fresh bidi stream, drains the queue into it until either the queue
     * sends a stop signal or the server closes (5-minute limit), then
     * loops to open the next one. This means a long call keeps producing
     * results with at most a brief gap at the rollover.
     */
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
                request.onNext(buildConfigRequest())
            } catch (e: Exception) {
                Log.e(TAG, "Sending config failed", e)
                onError?.invoke("Google STT config: ${e.message}")
                return
            }

            // Drain audio chunks until the server closes or we get the stop
            // sentinel. poll with a timeout so we notice server-side
            // closures and the stop flag in a timely manner.
            while (!stopRequested.get() && !streamClosed.get()) {
                val chunk = try {
                    pcmQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue
                if (chunk === stopSignal) {
                    break
                }
                try {
                    request.onNext(
                        StreamingRecognizeRequest.newBuilder()
                            .setAudioContent(ByteString.copyFrom(chunk))
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "onNext audio failed (will reopen stream): ${e.message}")
                    break
                }
            }

            try { request.onCompleted() } catch (_: Exception) {}
            // Brief breather before reopening so we don't tight-loop on
            // repeated immediate errors (e.g. auth failure).
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

    private fun buildConfigRequest(): StreamingRecognizeRequest {
        // `telephony` is the phone-optimised narrowband model. It works
        // equally well at 8 kHz (G.711) and 16 kHz (G.722/wideband phone),
        // which covers every codec Linphone can negotiate for us.
        val config = RecognitionConfig.newBuilder()
            .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
            .setSampleRateHertz(sampleRate)
            .setLanguageCode(languageCode)
            .setAudioChannelCount(2)
            .setEnableSeparateRecognitionPerChannel(true)
            .setModel("telephony")
            .setUseEnhanced(true)
            .build()
        val streaming = StreamingRecognitionConfig.newBuilder()
            .setConfig(config)
            .setInterimResults(true)
            .build()
        return StreamingRecognizeRequest.newBuilder()
            .setStreamingConfig(streaming)
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
                    // channelTag is 1-based; we pass it as a string so
                    // TranscriptionManager can map it via the same path
                    // used for Azure's speaker IDs.
                    val tag = result.channelTag.toString()
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
     * Adds `x-goog-api-key: <key>` to every gRPC call. The Speech v1
     * service accepts API-key auth for both unary and streaming requests
     * this way, no service-account JSON needed.
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
