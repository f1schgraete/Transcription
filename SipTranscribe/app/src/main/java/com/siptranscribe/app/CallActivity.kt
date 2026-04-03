package com.siptranscribe.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.siptranscribe.app.databinding.ActivityCallBinding
import org.linphone.core.Call

class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var transcriber: TranscriptionManager
    private val handler = Handler(Looper.getMainLooper())

    private var callSeconds = 0
    private var timerRunnable: Runnable? = null
    private val transcript = StringBuilder()

    // Tracks whether the local mic is intentionally enabled (Sprechen mode).
    // Default is false: mic is muted so SpeechRecognizer can use it for STT.
    private var micEnabled = false

    companion object {
        const val EXTRA_IS_INCOMING = "is_incoming"
        const val EXTRA_REMOTE_ADDRESS = "remote_address"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        val remote = intent.getStringExtra(EXTRA_REMOTE_ADDRESS) ?: "Unbekannt"

        transcriber = TranscriptionManager(this)

        binding.tvCaller.text = remote
        if (isIncoming) showIncomingUI() else showCallingUI()

        setupButtons()
        setupCallListener()
        setupTranscriber()
    }

    private fun setupButtons() {
        binding.btnHangUp.setOnClickListener {
            LinphoneManager.hangUp()
            finish()
        }

        binding.btnAccept.setOnClickListener {
            LinphoneManager.getCurrentCall()?.let { call ->
                LinphoneManager.acceptCall(call)
            }
            showActiveUI()
            startTimer()
            beginTranscription()
        }

        binding.btnDecline.setOnClickListener {
            LinphoneManager.getCurrentCall()?.let { LinphoneManager.declineCall(it) }
            finish()
        }

        // "Sprechen" toggle: enables mic so the deaf user can speak, pausing STT.
        // A second tap reverts to transcription mode.
        binding.btnToggleMic.setOnClickListener {
            micEnabled = !micEnabled
            LinphoneManager.setMicEnabled(micEnabled)
            if (micEnabled) {
                // Mic ON -> pause STT so STT doesn't capture the user's own voice
                transcriber.stop()
                binding.btnToggleMic.text = "Mikrofon AN - Tippen zum Transkribieren"
                binding.btnToggleMic.setBackgroundColor(getColor(R.color.mic_on))
            } else {
                // Mic OFF -> resume STT
                beginTranscription()
                binding.btnToggleMic.text = "Mikrofon AUS - Tippen zum Sprechen"
                binding.btnToggleMic.setBackgroundColor(getColor(R.color.mic_off))
            }
        }
    }

    private fun setupCallListener() {
        LinphoneManager.onCallStateChanged = { _, state ->
            runOnUiThread {
                when (state) {
                    Call.State.OutgoingRinging -> binding.tvStatus.text = "Klingelt..."
                    Call.State.Connected,
                    Call.State.StreamsRunning -> {
                        showActiveUI()
                        startTimer()
                        beginTranscription()
                    }
                    Call.State.Error -> {
                        binding.tvStatus.text = "Fehler beim Anruf"
                        handler.postDelayed({ finish() }, 2000)
                    }
                    Call.State.End,
                    Call.State.Released -> endCall()
                    else -> {}
                }
            }
        }
    }

    private fun setupTranscriber() {
        transcriber.onTranscription = { text, isFinal ->
            runOnUiThread {
                // Always show the latest partial or final result in the live area
                binding.tvLive.text = text
                if (isFinal && text.isNotBlank()) {
                    transcript.append(text).append(" ")
                    binding.tvHistory.text = transcript.toString()
                    // Auto-scroll to the bottom
                    binding.scrollHistory.post {
                        binding.scrollHistory.fullScroll(View.FOCUS_DOWN)
                    }
                    binding.tvLive.text = ""
                }
            }
        }
        transcriber.onError = { msg ->
            runOnUiThread { binding.tvStatus.text = msg }
        }
    }

    /**
     * Prepares the audio pipeline for transcription:
     * 1. Mutes the Linphone mic so the microphone hardware is free.
     * 2. Routes call audio to the loudspeaker so the mic can pick it up.
     * 3. Starts the SpeechRecognizer loop.
     */
    private fun beginTranscription() {
        micEnabled = false
        LinphoneManager.setMicEnabled(false)
        LinphoneManager.routeToSpeaker()
        binding.btnToggleMic.text = "Mikrofon AUS - Tippen zum Sprechen"
        binding.btnToggleMic.setBackgroundColor(getColor(R.color.mic_off))
        transcriber.start()
    }

    private fun endCall() {
        transcriber.stop()
        stopTimer()
        finish()
    }

    private fun showIncomingUI() {
        binding.layoutIncoming.visibility = View.VISIBLE
        binding.layoutActive.visibility = View.GONE
        binding.tvStatus.text = "Eingehender Anruf"
    }

    private fun showCallingUI() {
        binding.layoutIncoming.visibility = View.GONE
        binding.layoutActive.visibility = View.VISIBLE
        binding.tvStatus.text = "Verbinde..."
    }

    private fun showActiveUI() {
        if (binding.layoutActive.visibility == View.VISIBLE) return  // already shown
        binding.layoutIncoming.visibility = View.GONE
        binding.layoutActive.visibility = View.VISIBLE
        binding.tvStatus.text = "Aktiver Anruf"
    }

    private fun startTimer() {
        if (timerRunnable != null) return
        timerRunnable = object : Runnable {
            override fun run() {
                callSeconds++
                binding.tvDuration.text =
                    "%02d:%02d".format(callSeconds / 60, callSeconds % 60)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        transcriber.stop()
        stopTimer()
        LinphoneManager.onCallStateChanged = null
    }
}
