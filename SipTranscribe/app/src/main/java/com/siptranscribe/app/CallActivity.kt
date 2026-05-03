package com.siptranscribe.app

import android.app.NotificationManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
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
    private var partial: String = ""
    private var callEnded = false
    private var recordingStarted = false
    private var answered = false
    private var callStartTime = 0L
    private var callerName = "Unbekannt"
    private var callerNumber = ""

    /**
     * Path of the WAV file Linphone is recording to.
     * Set from the Intent extra for outgoing calls (path was embedded in params before dialling).
     * Set at accept-time for incoming calls.
     */
    private var recordFilePath: String = ""

    companion object {
        const val EXTRA_IS_INCOMING = "is_incoming"
        const val EXTRA_REMOTE_ADDRESS = "remote_address"
        const val EXTRA_REMOTE_NUMBER = "remote_number"
        const val EXTRA_RECORD_FILE = "record_file"
        private const val TAG = "CallActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        callerName = intent.getStringExtra(EXTRA_REMOTE_ADDRESS) ?: "Unbekannt"
        callerNumber = intent.getStringExtra(EXTRA_REMOTE_NUMBER) ?: callerName

        // For outgoing calls the record path was set in the call params by MainActivity.
        recordFilePath = intent.getStringExtra(EXTRA_RECORD_FILE) ?: ""

        transcriber = TranscriptionManager(this)

        binding.tvCaller.text = callerName
        if (isIncoming) showIncomingUI() else showCallingUI()

        setupButtons()
        setupCallListener()
        setupTranscriber()
    }

    private fun setupButtons() {
        binding.btnHangUp.setOnClickListener {
            LinphoneManager.hangUp()
        }

        binding.btnHangUpRinging.setOnClickListener {
            LinphoneManager.hangUp()
        }

        binding.btnLoeschen.setOnClickListener {
            finish()
        }

        binding.btnAccept.setOnClickListener {
            LinphoneManager.getCurrentCall()?.let { call ->
                // For incoming calls: generate the path here so it's in the call params
                // before acceptWithParams is called.
                recordFilePath = "${filesDir.absolutePath}/call_${System.currentTimeMillis()}.wav"
                LinphoneManager.acceptCall(call, recordFilePath)
            }
            answered = true
            callStartTime = System.currentTimeMillis()
            showActiveUI()
            startTimer()
        }

        binding.btnDecline.setOnClickListener {
            saveCallRecord(answeredCall = false)
            LinphoneManager.getCurrentCall()?.let { LinphoneManager.declineCall(it) }
            cancelIncomingNotification()
            finish()
        }
    }

    private fun setupCallListener() {
        LinphoneManager.onCallStateChanged = { _, state ->
            runOnUiThread {
                when (state) {
                    Call.State.OutgoingRinging -> binding.tvStatus.text = "Klingelt..."
                    Call.State.Connected -> {
                        showActiveUI()
                        startTimer()
                        // Do NOT start recording here — audio streams are not ready yet.
                        // StreamsRunning fires immediately after and is the right place.
                    }
                    Call.State.StreamsRunning -> {
                        showActiveUI()
                        startTimer()
                        beginRecordingAndTranscription()
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
                if (isFinal) {
                    if (text.isNotBlank()) transcript.append(text).append(' ')
                    partial = ""
                } else {
                    partial = text
                }
                renderTranscript()
            }
        }
        transcriber.onError = { msg ->
            runOnUiThread { binding.tvStatus.text = msg }
        }
    }

    /**
     * Renders the finalised text in the primary colour followed by the in-progress
     * partial in a faded grey, all in the same TextView. New utterances always
     * appear at the same line position — no jumping between zones.
     */
    private fun renderTranscript() {
        val builder = SpannableStringBuilder(transcript)
        if (partial.isNotEmpty()) {
            val start = builder.length
            builder.append(partial)
            builder.setSpan(
                ForegroundColorSpan(Color.parseColor("#888888")),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.tvHistory.text = builder
        binding.scrollHistory.post {
            binding.scrollHistory.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Called once when StreamsRunning fires. The record file path was embedded in the call
     * params before the call was accepted/initiated, so Linphone already knows where to write.
     * We just call startRecording() and start reading the file.
     */
    private fun beginRecordingAndTranscription() {
        if (recordingStarted) {
            Log.d(TAG, "beginRecordingAndTranscription: already started, skipping")
            return
        }
        if (recordFilePath.isBlank()) {
            Log.e(TAG, "beginRecordingAndTranscription: recordFilePath is empty")
            binding.tvStatus.text = "Aufnahmepfad fehlt"
            return
        }
        recordingStarted = true
        answered = true
        if (callStartTime == 0L) callStartTime = System.currentTimeMillis()
        val sampleRate = LinphoneManager.getCurrentCallSampleRate()
        Log.i(TAG, "beginRecordingAndTranscription: $recordFilePath @ $sampleRate Hz")
        LinphoneManager.startCallRecording()
        transcriber.start(recordFilePath, sampleRate)
    }

    private fun endCall() {
        if (callEnded) return
        callEnded = true
        LinphoneManager.stopCallRecording()
        transcriber.stop()
        stopTimer()
        saveCallRecord(answeredCall = answered)
        cancelIncomingNotification()
        binding.tvStatus.text = "Anruf beendet"
        binding.layoutCalling.visibility = View.GONE
        binding.layoutIncoming.visibility = View.GONE
        binding.btnHangUp.visibility = View.GONE
        binding.btnLoeschen.visibility = View.VISIBLE
        binding.layoutActive.visibility = View.VISIBLE
        runAnalysisIfPossible()
    }

    /**
     * Fires Azure chat completions over the finalised transcript. Skipped silently
     * when the deployment isn't configured or the transcript is too short to be
     * meaningful. Result is appended at the bottom of the transcript view.
     */
    private fun runAnalysisIfPossible() {
        val text = transcript.toString().trim()
        if (text.length < 30) {
            Log.d(TAG, "Skipping analysis: transcript too short")
            return
        }
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val endpoint = prefs.getString(MainActivity.KEY_AZURE_ENDPOINT, "")?.trim().orEmpty()
        val key = prefs.getString(MainActivity.KEY_AZURE_KEY, "")?.trim().orEmpty()
        val deployment = prefs.getString(MainActivity.KEY_AZURE_DEPLOYMENT, "")?.trim().orEmpty()
        if (endpoint.isBlank() || key.isBlank() || deployment.isBlank()) {
            Log.i(TAG, "Skipping analysis: chat deployment not configured")
            return
        }

        appendAnalysisStatus("Analyse wird erstellt …")
        Thread({
            try {
                val result = ConversationAnalyzer(endpoint, key, deployment).analyze(text)
                handler.post { showAnalysis(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                handler.post {
                    appendAnalysisStatus("Analyse fehlgeschlagen: ${e.message}")
                }
            }
        }, "ConversationAnalyzer").start()
    }

    /**
     * Tablet layout has a dedicated summary card; phone layout reuses the transcript
     * view (the summary just appends at the bottom).
     */
    private val hasSummaryPane: Boolean get() = binding.tvSummary != null

    private fun appendAnalysisStatus(msg: String) {
        if (hasSummaryPane) {
            binding.cardSummary?.visibility = View.VISIBLE
            binding.tvSummary?.text = msg
        } else {
            if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
            transcript.append('\n').append(msg).append('\n')
            partial = ""
            renderTranscript()
        }
    }

    private fun showAnalysis(r: ConversationAnalyzer.Result) {
        val text = formatSummary(r)
        if (hasSummaryPane) {
            binding.cardSummary?.visibility = View.VISIBLE
            binding.tvSummary?.text = text
        } else {
            // Replace the "Analyse wird erstellt …" placeholder appended earlier.
            val placeholder = "\n\nAnalyse wird erstellt …\n"
            val idx = transcript.lastIndexOf(placeholder)
            if (idx >= 0) transcript.setLength(idx)
            transcript.append('\n').append("──────────────").append('\n')
            transcript.append(text)
            partial = ""
            renderTranscript()
        }
    }

    private fun formatSummary(r: ConversationAnalyzer.Result): String = buildString {
        append("Anrufer: ").append(r.callerName ?: "—").append('\n')
        append("Thema: ").append(r.topic ?: "—").append('\n')
        if (r.importantPoints.isNotEmpty()) {
            append('\n').append("Wichtige Punkte:").append('\n')
            r.importantPoints.forEachIndexed { i, p ->
                append(i + 1).append(") ").append(p).append('\n')
            }
        }
        if (r.dates.isNotEmpty()) {
            append('\n').append("Termine:").append('\n')
            r.dates.forEach { append("• ").append(it).append('\n') }
        }
        if (r.todos.isNotEmpty()) {
            append('\n').append("Aufgaben:").append('\n')
            r.todos.forEach { append("• ").append(it).append('\n') }
        }
    }

    private fun saveCallRecord(answeredCall: Boolean) {
        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        val duration = if (callStartTime > 0L) {
            ((System.currentTimeMillis() - callStartTime) / 1000).toInt()
        } else {
            0
        }
        val record = CallRecord(
            id = System.currentTimeMillis(),
            direction = if (isIncoming) CallRecord.Direction.INCOMING else CallRecord.Direction.OUTGOING,
            callerName = callerName,
            callerNumber = callerNumber.ifBlank { callerName },
            startTime = if (callStartTime > 0L) callStartTime else System.currentTimeMillis(),
            durationSeconds = duration,
            answered = answeredCall
        )
        CallHistory.add(this, record)
    }

    private fun cancelIncomingNotification() {
        getSystemService(NotificationManager::class.java)
            .cancel(SipService.INCOMING_CALL_NOTIF_ID)
    }

    private fun showIncomingUI() {
        binding.layoutIncoming.visibility = View.VISIBLE
        binding.layoutActive.visibility = View.GONE
        binding.tvStatus.text = "Eingehender Anruf"
    }

    private fun showCallingUI() {
        binding.layoutCalling.visibility = View.VISIBLE
        binding.layoutIncoming.visibility = View.GONE
        binding.layoutActive.visibility = View.GONE
        binding.tvStatus.text = "Verbinde..."
    }

    private fun showActiveUI() {
        if (binding.layoutActive.visibility == View.VISIBLE) return
        binding.layoutCalling.visibility = View.GONE
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
        cancelIncomingNotification()
    }
}
