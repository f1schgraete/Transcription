package com.siptranscribe.app

import android.app.NotificationManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
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

    /** Finalised speaker turns, in order. Each turn keeps its speaker tag for color coding. */
    private val turns = mutableListOf<Turn>()
    /** In-flight partial result; replaces the previous partial on each update. */
    private var partial: Turn? = null
    /**
     * Maps speaker IDs to a stable color slot in [SPEAKER_BG_COLORS]. Pre-populated for the
     * split-recording fixed labels so the local user is always cream and the caller always blue,
     * regardless of who speaks first.
     */
    private val speakerColorMap = linkedMapOf(
        TranscriptionManager.LABEL_LOCAL  to 0,
        TranscriptionManager.LABEL_REMOTE to 1
    )
    /** Status line shown at the bottom of the transcript area (phone layout only). */
    private var summaryStatus: String? = null
    /** Final summary block shown at the bottom (phone layout only). */
    private var summaryBlock: String? = null

    private var callEnded = false
    private var speakerOn = false
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

    private data class Turn(val speakerId: String, val text: String)

    companion object {
        const val EXTRA_IS_INCOMING = "is_incoming"
        const val EXTRA_REMOTE_ADDRESS = "remote_address"
        const val EXTRA_REMOTE_NUMBER = "remote_number"
        const val EXTRA_RECORD_FILE = "record_file"
        private const val TAG = "CallActivity"

        /**
         * Background colors for speaker turns. Chosen for readers with low vision /
         * macular degeneration: pale, high-luminance pastels with very different hues
         * (warm cream vs cool blue vs neutral grey) that keep dark text easy to read.
         * The list is consulted in order — first new speaker gets index 0, etc.
         */
        private val SPEAKER_BG_COLORS = intArrayOf(
            0xFFFFF8E1.toInt(),   // pale cream — Speaker 1
            0xFFE3F2FD.toInt(),   // pale blue — Speaker 2
            0xFFE8F5E9.toInt(),   // pale green — fallback
            0xFFF3E5F5.toInt()    // pale lavender — fallback
        )
        private const val UNKNOWN_SPEAKER_BG = 0xFFEEEEEE.toInt()
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

        binding.btnSpeaker.setOnClickListener { toggleSpeaker() }
        applySpeakerButtonStyle()

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

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        if (speakerOn) LinphoneManager.routeToSpeaker() else LinphoneManager.routeToEarpiece()
        applySpeakerButtonStyle()
    }

    private fun applySpeakerButtonStyle() {
        if (speakerOn) {
            binding.btnSpeaker.text = "Lautsprecher AN"
            binding.btnSpeaker.backgroundTintList =
                getColorStateList(R.color.accent)
        } else {
            binding.btnSpeaker.text = "Lautsprecher"
            binding.btnSpeaker.backgroundTintList =
                getColorStateList(R.color.mic_off)
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
        transcriber.onTranscription = { text, isFinal, speakerId ->
            runOnUiThread {
                val sid = speakerId ?: "Unknown"
                if (isFinal) {
                    if (text.isNotBlank()) turns.add(Turn(sid, text))
                    partial = null
                } else {
                    partial = if (text.isNotBlank()) Turn(sid, text) else null
                }
                renderTranscript()
            }
        }
        transcriber.onError = { msg ->
            runOnUiThread { binding.tvStatus.text = msg }
        }
    }

    /**
     * Stable color for a speaker. Each newly-seen ID claims the next free slot in
     * [SPEAKER_BG_COLORS]. "Unknown" — Azure's pre-warm-up label — gets a neutral grey
     * so it doesn't burn through a real-speaker color.
     */
    private fun bgColorFor(speakerId: String): Int {
        if (speakerId == "Unknown" || speakerId.isBlank()) return UNKNOWN_SPEAKER_BG
        val idx = speakerColorMap.getOrPut(speakerId) {
            val next = speakerColorMap.size
            if (next < SPEAKER_BG_COLORS.size) next else SPEAKER_BG_COLORS.size - 1
        }
        return SPEAKER_BG_COLORS[idx]
    }

    /**
     * One TextView, latest content at the bottom. Each speaker turn becomes its own
     * coloured paragraph; the active partial appends in the same speaker's colour with
     * a faded foreground so the user can see what's still being processed.
     */
    private fun renderTranscript() {
        val builder = SpannableStringBuilder()
        for ((i, turn) in turns.withIndex()) {
            appendTurn(builder, turn, partial = false)
            if (i < turns.size - 1) builder.append('\n')
        }
        partial?.let { p ->
            if (turns.isNotEmpty()) builder.append('\n')
            appendTurn(builder, p, partial = true)
        }
        summaryStatus?.let {
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(it)
        }
        summaryBlock?.let {
            if (builder.isNotEmpty()) builder.append("\n\n──────────────\n")
            builder.append(it)
        }
        binding.tvHistory.text = builder
        binding.scrollHistory.post {
            binding.scrollHistory.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Flatten finalised speaker turns into a transcript string suitable for the LLM.
     * Each line begins with the actual speaker label ("Ich" / "Anrufer" in split mode) so
     * the analyser knows who said what.
     */
    private fun buildLabeledTranscript(): String {
        if (turns.isEmpty()) return ""
        val sb = StringBuilder()
        for (turn in turns) {
            val label = if (turn.speakerId.isBlank() || turn.speakerId == "Unknown")
                "Sprecher ?" else turn.speakerId
            sb.append('[').append(label).append("] ").append(turn.text).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun appendTurn(builder: SpannableStringBuilder, turn: Turn, partial: Boolean) {
        // Pad each side so the background extends slightly past the text.
        val start = builder.length
        builder.append(' ').append(turn.text).append(' ')
        builder.setSpan(
            BackgroundColorSpan(bgColorFor(turn.speakerId)),
            start, builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (partial) {
            builder.setSpan(
                ForegroundColorSpan(Color.parseColor("#666666")),
                start, builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * Derive uplink/downlink WAV paths from the configured recordFile (split-recording mode).
     * Mirrors `derive_split_paths` in our liblinphone patch (audio-stream.cpp).
     */
    private fun splitPaths(base: String): Pair<String, String> {
        val slash = maxOf(base.lastIndexOf('/'), base.lastIndexOf('\\'))
        val dot = base.lastIndexOf('.')
        return if (dot != -1 && dot > slash) {
            base.substring(0, dot) + ".ul" + base.substring(dot) to
            base.substring(0, dot) + ".dl" + base.substring(dot)
        } else {
            "$base.ul" to "$base.dl"
        }
    }

    /**
     * Called once when StreamsRunning fires. The record file path was embedded in the call
     * params before the call was accepted/initiated; with split recording enabled the patched
     * Linphone writes two WAVs at <base>.ul.<ext> and <base>.dl.<ext>.
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
        val (ul, dl) = splitPaths(recordFilePath)
        Log.i(TAG, "beginRecordingAndTranscription: ul=$ul dl=$dl @ $sampleRate Hz")
        // Split-record config is set globally in LinphoneManager.init() so it's already in
        // effect when liblinphone calls MS2AudioStream::setRecordPath during stream setup
        // (well before this point). Do NOT re-toggle here — flipping it mid-flow has no
        // effect on a stream whose recorder was already configured in mixed mode.
        LinphoneManager.startCallRecording()
        transcriber.start(ul, dl, sampleRate)
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
        val text = buildLabeledTranscript()
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
        val prompt = prefs.getString(MainActivity.KEY_SUMMARY_PROMPT, null)
            ?.takeIf { it.isNotBlank() }
            ?: ConversationAnalyzer.DEFAULT_SYSTEM_PROMPT
        val owners = prefs.getString(MainActivity.KEY_OWNER_NAMES, MainActivity.DEFAULT_OWNER_NAMES)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        appendAnalysisStatus("Analyse wird erstellt …")
        Thread({
            try {
                val result = ConversationAnalyzer(endpoint, key, deployment, prompt, owners)
                    .analyze(text)
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
            summaryStatus = msg
            summaryBlock = null
            renderTranscript()
        }
    }

    private fun showAnalysis(r: ConversationAnalyzer.Result) {
        val text = formatSummary(r)
        if (hasSummaryPane) {
            binding.cardSummary?.visibility = View.VISIBLE
            binding.tvSummary?.text = text
        } else {
            summaryStatus = null
            summaryBlock = text
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
