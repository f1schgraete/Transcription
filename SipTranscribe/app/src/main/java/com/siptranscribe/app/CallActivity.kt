package com.siptranscribe.app

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    /**
     * Whether the LLM summary feature is on (read from settings at start).
     * When off we transcribe only the caller's leg (no second Azure
     * connection) and never produce a Zusammenfassung; the summary card is
     * hidden so the transcript fills the screen.
     */
    private val summaryEnabled: Boolean by lazy {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_SUMMARY_ENABLED, MainActivity.DEFAULT_SUMMARY_ENABLED)
    }
    private var recordingStarted = false
    private var answered = false
    private var callStartTime = 0L
    private var callerName = "Unbekannt"
    private var callerNumber = ""

    /**
     * The Linphone Call object this activity is currently tracking.
     * Set in onCreate / btnAccept / recycleForNewCall and used by the
     * state listener to ignore stale events from a different call — e.g.
     * a previous call's `Released` arriving milliseconds after we've
     * already recycled into a new incoming, which would otherwise call
     * `endCall()` on the still-fresh new-call activity.
     */
    private var activeCall: org.linphone.core.Call? = null

    /**
     * A second call that arrived while [activeCall] was already in
     * conversation. We don't interrupt the active call (see [onNewIntent]);
     * instead the waiting caller is shown in a banner and, once the user
     * hangs up, presented on the normal incoming screen if still ringing.
     * Null whenever no second call is pending.
     */
    private var waitingCall: org.linphone.core.Call? = null
    private var waitingCallerName = ""
    private var waitingCallerNumber = ""
    /** Pending "…hat aufgelegt" banner-fade callback, so we can cancel it. */
    private var waitingBannerHide: Runnable? = null

    /**
     * Monotonic counter bumped every time [recycleForNewCall] flips the
     * activity over to a new call. Async work that survives across
     * recycles — most importantly the ConversationAnalyzer thread, which
     * posts its result back to the main handler when it completes —
     * captures the current generation at submit time and bails out on
     * the post if the generation has moved on, so the previous call's
     * summary can never overwrite the new call's card.
     */
    private val callGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Periodic summary state. While the call is active a timer fires every
     * [SUMMARY_INTERVAL_MS]; each tick re-runs ConversationAnalyzer over the
     * transcript so far and quietly replaces tv_summary's text. The in-flight
     * guard prevents two simultaneous Azure round-trips; pendingFinalAnalysis
     * captures the "call just ended" signal so a refresh that's currently
     * mid-flight gets followed by exactly one more pass.
     */
    private var analyzeInFlight = false
    private var lastAnalyzedTurnCount = 0
    private var pendingFinalAnalysis = false
    private var summaryRunnable: Runnable? = null

    /** Stable id assigned the first time we save the record; reused as the
     *  filename for the encrypted archive so the two never drift apart. */
    private var callRecordId = 0L
    /** Most recent formatted summary, captured every time analysis succeeds.
     *  Archived alongside the transcript so the caregiver can read it later. */
    private var latestSummaryText: String? = null


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

        private const val MIN_CHARS_FOR_SUMMARY = 80

        /** Call states from which no further progress is possible. */
        private val TERMINAL_STATES = setOf(
            Call.State.End, Call.State.Released, Call.State.Error
        )

        /**
         * Wall-clock time the most recent call became active (answered /
         * streams running). ListenActivity reads this to decide what to do
         * when a call interrupted a "Zuhören" session: if a call was actually
         * taken while Zuhören was in the background, there's no point dropping
         * the user back on the stale "Beendet" screen afterwards — Zuhören
         * dismisses itself so they land back on the main screen. A missed or
         * declined call never sets this, so in that case Zuhören is preserved.
         */
        @Volatile
        var lastAnsweredAtMs: Long = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Incoming-call ergonomics: wake the screen if it's asleep, show the
        // call UI over the keyguard, and try to dismiss the keyguard so the
        // user lands on a usable Annehmen/Ablehnen screen instead of the
        // lock screen. The tablet is configured with no passcode so the
        // dismiss call succeeds; on a locked device it would simply do
        // nothing (we never bypass auth).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(android.app.KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersiveMode()

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

        // Race guard: an incoming call can be cancelled (caller hung up, or
        // another registered device answered) between when SipService kicks
        // CallActivity off and when our onCallStateChanged listener is wired
        // up. In that case the End / Released event never reaches us and the
        // activity sits forever on the Incoming UI. Catch the case explicitly
        // by inspecting the core's current state once setup is complete.
        //
        // We only act on explicitly terminal states. A null currentCall does
        // NOT mean "call already ended" — Linphone returns null transiently
        // while it's wiring up a freshly-arrived incoming call, and an
        // earlier version of this guard treated null as terminal. That
        // produced a "missed call" history row plus a second CallActivity
        // launch via the notification's full-screen intent — the user
        // ended up with one red entry from the bogus early-end and one
        // green entry from the real call they actually answered.
        //
        // The same race also runs the other way: an *outgoing* call is placed
        // by MainActivity before CallActivity launches, so the call can already
        // be Connected/StreamsRunning by the time our listener attaches. Those
        // state events fired before we were listening and will never be
        // re-delivered, leaving the activity stuck on the "Verbinde..." ringing
        // UI with no transcript (beginRecordingAndTranscription only runs from
        // StreamsRunning). Sync to the current state explicitly so we adopt
        // whatever the call is already doing.
        syncToCurrentCallState()
    }

    /**
     * Reconcile the UI with the call's *current* state, for events that may
     * have fired before our [setupCallListener] was wired up (outgoing calls
     * placed by MainActivity before launch; incoming calls already cancelled).
     * Safe to call repeatedly: showActiveUI / startTimer /
     * beginRecordingAndTranscription / endCall are all idempotent, so any
     * later live onCallStateChanged callbacks are harmless no-ops.
     */
    private fun syncToCurrentCallState() {
        // Reconcile against the call this activity is presenting. We must not
        // switch on core.currentCall here: while two calls coexist it may be a
        // different (e.g. just-displaced) call, and adopting its terminal state
        // would wrongly endCall() this fresh screen. Fall back to currentCall
        // only when we haven't adopted a call yet (first launch).
        if (activeCall == null) activeCall = LinphoneManager.getCurrentCall()
        when (activeCall?.state) {
            Call.State.End, Call.State.Released, Call.State.Error -> endCall()
            Call.State.Connected -> {
                showActiveUI()
                startTimer()
            }
            Call.State.StreamsRunning -> {
                showActiveUI()
                startTimer()
                beginRecordingAndTranscription()
            }
            else -> {
                // Still ringing (or null while Linphone wires up an incoming
                // call) — the live onCallStateChanged listener will drive it.
            }
        }
    }

    private fun setupButtons() {
        binding.btnHangUp.setOnClickListener { hangUpWithSafetyNet() }

        binding.btnHangUpRinging.setOnClickListener { hangUpWithSafetyNet() }

        binding.btnLoeschen.setOnClickListener {
            finish()
        }

        // The tablet layout drops the speaker button entirely (one omni
        // speaker, no earpiece to switch between), so the binding field
        // is nullable here.
        binding.btnSpeaker?.setOnClickListener { toggleSpeaker() }
        applySpeakerButtonStyle()

        binding.btnAccept.setOnClickListener {
            // Accept the call this activity is presenting. activeCall is the
            // one we adopted (reliable even when two calls coexist); fall back
            // to currentCall only if we somehow haven't adopted one yet.
            (activeCall ?: LinphoneManager.getCurrentCall())?.let { call ->
                // For incoming calls: generate the path here so it's in the call params
                // before acceptWithParams is called.
                recordFilePath = "${filesDir.absolutePath}/call_${System.currentTimeMillis()}.wav"
                LinphoneManager.acceptCall(call, recordFilePath)
                activeCall = call
            }
            answered = true
            callStartTime = System.currentTimeMillis()
            showActiveUI()
            startTimer()
        }

        binding.btnDecline.setOnClickListener {
            // Tell Linphone to send the 603, then route through endCall() so
            // there's exactly one history row written. The old code called
            // saveCallRecord here AND finish(), and the subsequent state
            // transition (End / Released) ran endCall() too — which wrote
            // a second identical missed-call row.
            LinphoneManager.getCurrentCall()?.let { LinphoneManager.declineCall(it) }
            cancelIncomingNotification()
            endCall()
        }
    }

    /**
     * Hang up, with a safety net so the user is never stranded on the call
     * screen. Normally LinphoneManager.hangUp() terminates the call and the
     * resulting End/Released event runs endCall(), which tears the screen down.
     * But if the SIP connection went stale in the background, terminate() can
     * sit unacknowledged and that event never arrives — leaving the big red
     * button apparently "dead" with no way back for a low-vision user. Schedule
     * a fallback that force-ends the call locally if nothing happened in time.
     */
    private fun hangUpWithSafetyNet() {
        LinphoneManager.hangUp()
        handler.postDelayed({
            if (!callEnded) {
                Log.w(TAG, "hangUp safety net: no End event arrived, forcing endCall()")
                endCall()
            }
        }, 2500)
    }

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        if (speakerOn) LinphoneManager.routeToSpeaker() else LinphoneManager.routeToEarpiece()
        applySpeakerButtonStyle()
    }

    private fun applySpeakerButtonStyle() {
        val btn = binding.btnSpeaker ?: return
        if (speakerOn) {
            btn.text = "Lautsprecher AN"
            btn.backgroundTintList = getColorStateList(R.color.accent)
        } else {
            btn.text = "Lautsprecher"
            btn.backgroundTintList = getColorStateList(R.color.mic_off)
        }
    }

    private fun setupCallListener() {
        LinphoneManager.onCallStateChanged = lambda@ { call, state ->
            // Adopt the call on the first event if we don't have one yet
            // (covers outgoing calls that started slightly before we got
            // into onCreate). Once we have an `activeCall`, anything from
            // a different Call object is a stale event from a previous
            // call and must be ignored, otherwise its terminal states
            // (End / Released) would re-trigger endCall() on a call we
            // are currently in the middle of presenting.
            if (activeCall == null) activeCall = call
            if (call !== activeCall) {
                // Not the call we're presenting. If it's the waiting second
                // call and it just ended, update the banner so it stops
                // claiming someone is still there.
                if (call === waitingCall && state in TERMINAL_STATES) {
                    runOnUiThread { onWaitingCallGone() }
                }
                return@lambda
            }
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
            DiagLog.log(this, "STT-Fehler: $msg")
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
        // Re-read the toggle from prefs every render so a change in
        // MainActivity takes effect on the very next transcript update,
        // not just on the next call. Read is sub-millisecond and
        // renderTranscript only fires when a new turn or partial arrives,
        // so this is cheap. The unfiltered [turns] list still drives the
        // archive and the analyser — only what's shown on screen changes.
        val showLocal = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_TRANSCRIPT_SHOW_LOCAL, false)
        val visibleTurns = if (showLocal) turns
            else turns.filter { it.speakerId != TranscriptionManager.LABEL_LOCAL }
        for ((i, turn) in visibleTurns.withIndex()) {
            appendTurn(builder, turn, partial = false)
            if (i < visibleTurns.size - 1) builder.append('\n')
        }
        partial?.let { p ->
            if (!showLocal && p.speakerId == TranscriptionManager.LABEL_LOCAL) {
                return@let
            }
            if (visibleTurns.isNotEmpty()) builder.append('\n')
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
        // Summary off → caller-only transcription (skips the second Azure
        // recognizer) and no periodic analysis.
        transcriber.start(ul, dl, sampleRate, callerOnly = !summaryEnabled)
        if (summaryEnabled) schedulePeriodicSummary()
    }

    private fun schedulePeriodicSummary() {
        if (summaryRunnable != null) return
        if (!hasSummaryPane) return
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val seconds = prefs
            .getInt(MainActivity.KEY_SUMMARY_INTERVAL, MainActivity.DEFAULT_SUMMARY_INTERVAL_SECONDS)
            .coerceAtLeast(MainActivity.MIN_SUMMARY_INTERVAL_SECONDS)
        val intervalMs = seconds * 1000L
        val r = object : Runnable {
            override fun run() {
                tryRunAnalysis(force = false)
                handler.postDelayed(this, intervalMs)
            }
        }
        summaryRunnable = r
        handler.postDelayed(r, intervalMs)
    }

    private fun cancelPeriodicSummary() {
        summaryRunnable?.let { handler.removeCallbacks(it) }
        summaryRunnable = null
    }

    /**
     * Posts the current transcript to Azure for a fresh summary. Throttled so:
     *   - at most one analyse is in flight at a time,
     *   - we don't re-run when no new turns have arrived since last pass,
     *   - `force=true` (end-of-call path) bypasses the "nothing new" guard but
     *     still queues behind any in-flight request via [pendingFinalAnalysis].
     */
    private fun tryRunAnalysis(force: Boolean) {
        if (!summaryEnabled) return
        if (analyzeInFlight) {
            if (force) pendingFinalAnalysis = true
            return
        }
        val text = buildLabeledTranscript()
        if (text.length < MIN_CHARS_FOR_SUMMARY) return
        if (!force && turns.size == lastAnalyzedTurnCount) return

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val endpoint = prefs.getString(MainActivity.KEY_AZURE_ENDPOINT, "")?.trim().orEmpty()
        val key = prefs.getString(MainActivity.KEY_AZURE_KEY, "")?.trim().orEmpty()
        val deployment = prefs.getString(MainActivity.KEY_AZURE_DEPLOYMENT, "")?.trim().orEmpty()
        if (endpoint.isBlank() || key.isBlank() || deployment.isBlank()) {
            Log.i(TAG, "Skipping analysis: chat deployment not configured")
            // On the final pass, tell the user *why* there's no summary
            // instead of leaving the card on its placeholder forever.
            if (force) {
                DiagLog.log(this, "Zusammenfassung übersprungen: Azure-Endpunkt/Schlüssel/Chat-Deployment fehlt")
                if (latestSummaryText == null) {
                    appendAnalysisStatus("Keine Zusammenfassung: Azure-Chat-Deployment ist nicht konfiguriert (Einstellungen → KI / Zusammenfassung).")
                }
            }
            return
        }
        val prompt = prefs.getString(MainActivity.KEY_SUMMARY_PROMPT, null)
            ?.takeIf { it.isNotBlank() }
            ?: ConversationAnalyzer.DEFAULT_SYSTEM_PROMPT

        analyzeInFlight = true
        lastAnalyzedTurnCount = turns.size

        // Snapshot the call generation at submit time. If the activity is
        // recycled to a new call before this thread posts back, the
        // generation check on the main thread drops the stale result so
        // it can never overwrite the new call's summary card.
        val gen = callGeneration.get()

        Thread({
            try {
                val result = ConversationAnalyzer(endpoint, key, deployment, prompt)
                    .analyze(text)
                handler.post {
                    if (gen != callGeneration.get()) return@post
                    analyzeInFlight = false
                    showAnalysis(result)
                    if (pendingFinalAnalysis) {
                        pendingFinalAnalysis = false
                        tryRunAnalysis(force = true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                // Always record the failure durably so the caregiver can read
                // it in the Diagnose dialog even when this device blocks
                // logcat self-reads. e.message carries the HTTP code + body
                // (e.g. "HTTP 500: …") from ConversationAnalyzer.post().
                DiagLog.log(this@CallActivity, "Zusammenfassung fehlgeschlagen: ${e.message}")
                handler.post {
                    if (gen != callGeneration.get()) return@post
                    analyzeInFlight = false
                    val runFinalNext = pendingFinalAnalysis
                    pendingFinalAnalysis = false
                    // Surface the error when this was the final/end-of-call pass
                    // (force) or the call has already ended — but only if we have
                    // no good summary on screen to preserve. A transient mid-call
                    // 500 with an earlier summary still showing is left untouched
                    // (it was logged above and the next tick will retry).
                    if ((force || callEnded) && latestSummaryText == null) {
                        appendAnalysisStatus("Zusammenfassung fehlgeschlagen: ${e.message}")
                    }
                    if (runFinalNext) tryRunAnalysis(force = true)
                }
            }
        }, "ConversationAnalyzer").start()
    }

    private fun endCall() {
        if (callEnded) return
        callEnded = true
        LinphoneManager.stopCallRecording()
        transcriber.stop()
        stopTimer()
        cancelPeriodicSummary()
        saveCallRecord(answeredCall = answered)
        cancelIncomingNotification()

        // Hand-off: a second caller is waiting and still ringing → present
        // them on the familiar incoming screen instead of the call-ended
        // review. The user finishes one conversation and the next person
        // simply "arrives" as a normal incoming call — one decision at a
        // time, no call juggling. recycleForNewCall resets all per-call state.
        val waiting = waitingCall
        if (waiting != null && isCallRinging(waiting)) {
            val switchIntent = Intent(this, CallActivity::class.java).apply {
                putExtra(EXTRA_IS_INCOMING, true)
                putExtra(EXTRA_REMOTE_ADDRESS, waitingCallerName)
                putExtra(EXTRA_REMOTE_NUMBER, waitingCallerNumber)
            }
            setIntent(switchIntent)
            recycleForNewCall(switchIntent)
            return
        }
        // No one waiting (or they gave up): drop any lingering banner.
        hideWaitingCall()

        // Unanswered call: return to the home screen. MainActivity's onResume
        // will detect the new unanswered entry in CallHistory and show the
        // missed-call banner inline — without launching a separate activity that
        // would sit on the back stack and re-appear confusingly after the next
        // call. The golden rule: after *any* call the user always lands on the
        // familiar home screen.
        if (!answered) {
            finish()
            return
        }

        binding.tvStatus.text = "Anruf beendet"
        binding.layoutCalling.visibility = View.GONE
        binding.layoutIncoming.visibility = View.GONE
        binding.btnHangUp.visibility = View.GONE
        binding.btnLoeschen.visibility = View.VISIBLE
        binding.layoutActive.visibility = View.VISIBLE
        // Save now with whatever state we have so a missed/failed analyser
        // doesn't lose the transcript. showAnalysis() overwrites the file
        // again with the fresher summary if/when the final pass completes.
        saveArchive()
        // One last analysis pass over the now-frozen transcript. If a periodic
        // pass is still running, tryRunAnalysis() queues this via pendingFinalAnalysis.
        tryRunAnalysis(force = true)
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
        latestSummaryText = text
        if (hasSummaryPane) {
            binding.cardSummary?.visibility = View.VISIBLE
            binding.tvSummary?.text = text
        } else {
            summaryStatus = null
            summaryBlock = text
            renderTranscript()
        }
        // Persist the freshest summary; idempotent overwrite at the file level.
        // Only matters once the call has ended — periodic mid-call passes don't
        // need to hit disk yet.
        if (callEnded) {
            DiagLog.log(this, "Zusammenfassung erstellt (Anrufer: ${r.callerName ?: "—"})")
            saveArchive()
            considerContactSuggestion(r)
        }
    }

    /**
     * If the final analysis identified a plausible caller name and we don't
     * already know this number, queue a contact suggestion for the
     * caregiver. With the new split-recording prompt the LLM gets explicit
     * `[Ich]` / `[Anrufer]` labels in the transcript, so we no longer need
     * an owner-name allow-list to filter false positives where the model
     * misattributed our user's name to the caller.
     */
    private fun considerContactSuggestion(r: ConversationAnalyzer.Result) {
        val name = r.callerName?.trim().orEmpty()
        if (name.isBlank() || callerNumber.isBlank()) return
        val durationSec = if (callStartTime > 0L) {
            ((System.currentTimeMillis() - callStartTime) / 1000).toInt()
        } else callSeconds
        ContactSuggestionStore.maybeRecord(this, callerNumber, name, durationSec)
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
        if (callRecordId == 0L) callRecordId = System.currentTimeMillis()
        val record = CallRecord(
            id = callRecordId,
            direction = if (isIncoming) CallRecord.Direction.INCOMING else CallRecord.Direction.OUTGOING,
            callerName = callerName,
            callerNumber = callerNumber.ifBlank { callerName },
            startTime = if (callStartTime > 0L) callStartTime else System.currentTimeMillis(),
            durationSeconds = duration,
            answered = answeredCall
        )
        CallHistory.add(this, record)
    }

    /**
     * Snapshot the transcript (and any summary captured so far) to an
     * encrypted archive file keyed by [callRecordId]. Called at end-of-call
     * and re-called when the final analysis arrives; the file is overwritten
     * each time so it always reflects the freshest state. Skipped entirely
     * when there's no content worth keeping.
     */
    private fun saveArchive() {
        if (callRecordId == 0L) return
        if (turns.isEmpty() && latestSummaryText.isNullOrBlank()) return
        val archive = CallArchive(
            callId = callRecordId,
            transcriptTurns = turns.map {
                CallArchive.TranscriptTurn(it.speakerId, it.text)
            },
            summaryText = latestSummaryText
        )
        CallArchiveStore.save(this, archive)
    }

    private fun cancelIncomingNotification() {
        getSystemService(NotificationManager::class.java)
            .cancel(SipService.INCOMING_CALL_NOTIF_ID)
    }

    private fun showIncomingUI() {
        binding.layoutIncoming.visibility = View.VISIBLE
        binding.layoutActive.visibility = View.GONE
        binding.tvStatus.text = "Eingehender Anruf"
        showBigCallerName(true)
    }

    private fun showCallingUI() {
        binding.layoutCalling.visibility = View.VISIBLE
        binding.layoutIncoming.visibility = View.GONE
        binding.layoutActive.visibility = View.GONE
        binding.tvStatus.text = "Verbinde..."
        showBigCallerName(true)
    }

    private fun showActiveUI() {
        // Mark that a call was actually taken (any path into the active UI:
        // accept, Connected, StreamsRunning). ListenActivity uses this to
        // decide whether to dismiss itself after interrupting a Zuhören session.
        lastAnsweredAtMs = System.currentTimeMillis()
        // The incoming-call notification is ongoing=true so the user cannot
        // swipe it away. Cancel it immediately the moment the call goes active
        // (not just at the end of the call) so it stops blocking the screen.
        cancelIncomingNotification()
        if (binding.layoutActive.visibility == View.VISIBLE) return
        binding.layoutCalling.visibility = View.GONE
        binding.layoutIncoming.visibility = View.GONE
        binding.layoutActive.visibility = View.VISIBLE
        binding.tvStatus.text = "Aktiver Anruf"
        showBigCallerName(false)
    }

    /**
     * Toggles the big-name banner that sits over the transcript area while
     * the call is still ringing. The transcript card is also flipped — it
     * would otherwise show a thin empty card behind the banner, which looks
     * broken. Once the call goes active the banner hides and the transcript
     * card returns as the primary content area.
     *
     * Binding fields are nullable because some layout variants (e.g. legacy
     * portrait) don't include `tv_caller_big`; in that case we just no-op.
     */
    private fun showBigCallerName(visible: Boolean) {
        val big = binding.tvCallerBig ?: return
        if (visible) {
            big.text = callerName
            big.visibility = View.VISIBLE
            binding.cardTranscript?.visibility = View.GONE
            binding.cardSummary?.visibility = View.GONE
        } else {
            big.visibility = View.GONE
            binding.cardTranscript?.visibility = View.VISIBLE
            // With the summary disabled the card stays hidden so the
            // transcript card (weight 2) expands to fill the full width.
            binding.cardSummary?.visibility =
                if (summaryEnabled) View.VISIBLE else View.GONE
        }
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
        cancelPeriodicSummary()
        LinphoneManager.onCallStateChanged = null
        cancelIncomingNotification()
    }

    /**
     * Fresh call intent arrived while this CallActivity instance already
     * exists (SipService uses SINGLE_TOP + the activity is launchMode
     * singleTop in the manifest, so the intent lands here instead of
     * making a duplicate instance). Behaviour:
     *  - If there's a genuinely *active* conversation (answered and not yet
     *    ended), ignore the new intent. The heads-up notification stays up
     *    and takes the user to the new call after they hang up; we
     *    deliberately don't interrupt a live conversation (and recycling
     *    would tear down its in-progress transcript).
     *  - Otherwise — the previous call is merely ringing/unanswered, or has
     *    already ended — recycle this activity into the new call's UI so the
     *    current call always wins the screen. Previously this only recycled
     *    when the previous call had fully ended, so a *not-picked-up* call's
     *    ringing screen lingered and a newly-arriving call never appeared
     *    in-app. The new caller is what matters; the stale ringing screen
     *    must yield.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (answered && !callEnded) {
            // Active conversation: don't interrupt it. Surface the second
            // caller as a non-actionable banner plus a vibration cue (the
            // deaf user gets no call-waiting tone). She takes them after
            // hanging up. NOTE: we deliberately do NOT setIntent() here — the
            // stored intent must keep describing the *active* call so its
            // history row / direction stay correct when it ends.
            val name = intent.getStringExtra(EXTRA_REMOTE_ADDRESS) ?: "Unbekannt"
            val number = intent.getStringExtra(EXTRA_REMOTE_NUMBER) ?: name
            showWaitingCall(name, number, LinphoneManager.latestIncomingCall)
            return
        }
        setIntent(intent)
        recycleForNewCall(intent)
    }

    /**
     * Display the persistent waiting-call banner for a second caller and buzz
     * the device. No answer button by design — the only in-call action stays
     * the big red Auflegen.
     */
    private fun showWaitingCall(name: String, number: String, call: org.linphone.core.Call?) {
        waitingCall = call
        waitingCallerName = name
        waitingCallerNumber = number
        waitingBannerHide?.let { handler.removeCallbacks(it) }
        waitingBannerHide = null
        val banner = binding.layoutWaitingCall ?: return
        binding.tvWaitingCaller?.text = "$name ruft auch an"
        binding.tvWaitingHint?.text = "Zum Annehmen zuerst auflegen."
        banner.visibility = View.VISIBLE
        vibrateWaitingCue()
    }

    /**
     * The waiting caller gave up before the user finished. Tell the truth so
     * the banner doesn't keep claiming someone is there, then fade it.
     */
    private fun onWaitingCallGone() {
        waitingCall = null
        binding.tvWaitingCaller?.text =
            "${waitingCallerName.ifBlank { "Anrufer" }} hat aufgelegt"
        binding.tvWaitingHint?.text = ""
        val hide = Runnable { binding.layoutWaitingCall?.visibility = View.GONE }
        waitingBannerHide = hide
        handler.postDelayed(hide, 4000)
    }

    private fun hideWaitingCall() {
        waitingBannerHide?.let { handler.removeCallbacks(it) }
        waitingBannerHide = null
        waitingCall = null
        binding.layoutWaitingCall?.visibility = View.GONE
    }

    private fun isCallRinging(call: org.linphone.core.Call): Boolean = when (call.state) {
        Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> true
        else -> false
    }

    /** Short double-buzz so the deaf user notices the waiting-call banner. */
    private fun vibrateWaitingCue() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            } ?: return
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    /**
     * Reset every piece of per-call UI/state so the activity behaves as
     * if it had been freshly created for this new call. Covers both
     * incoming (from SipService) and outgoing (from MainActivity.makeCall)
     * intents; `EXTRA_IS_INCOMING` picks which ringing-UI to show.
     */
    private fun recycleForNewCall(intent: Intent) {
        // The call this activity was presenting until now. We only reach
        // recycle for a previous call that is NOT an active conversation
        // (onNewIntent guards that), so it's a ringing/unanswered or already
        // ended call — safe to terminate once the new call is adopted.
        val displaced = activeCall

        // Bump generation FIRST so any in-flight analyser threads that are
        // about to post back on the main handler see a stale gen and bail.
        callGeneration.incrementAndGet()

        // Per-call flags + buffers
        callEnded = false
        answered = false
        recordingStarted = false
        callStartTime = 0L
        callSeconds = 0
        turns.clear()
        partial = null
        speakerColorMap.clear()
        speakerColorMap[TranscriptionManager.LABEL_LOCAL]  = 0
        speakerColorMap[TranscriptionManager.LABEL_REMOTE] = 1
        summaryStatus = null
        summaryBlock = null
        latestSummaryText = null
        callRecordId = 0L
        analyzeInFlight = false
        lastAnalyzedTurnCount = 0
        pendingFinalAnalysis = false
        speakerOn = false

        // Clear any pending main-thread runnables from the old call
        // (e.g. the Error→finish() postDelayed, or the analyser hand-off)
        // so they can't fire on the recycled UI.
        stopTimer()
        cancelPeriodicSummary()
        handler.removeCallbacksAndMessages(null)

        // The TranscriptionManager owns recorders and a network engine
        // whose threads were torn down on the previous endCall(). Build a
        // fresh one for the new call and re-wire its callbacks.
        try { transcriber.stop() } catch (_: Exception) {}
        transcriber = TranscriptionManager(this)
        setupTranscriber()

        // Adopt the newest call so the state-listener filter routes its
        // events to this recycled instance. For incoming calls we use the
        // explicitly-tracked latest INVITE (core.currentCall is ambiguous
        // while two calls coexist); outgoing calls are always currentCall.
        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        activeCall = if (isIncoming)
            (LinphoneManager.latestIncomingCall ?: LinphoneManager.getCurrentCall())
        else
            LinphoneManager.getCurrentCall()

        // Stop the displaced call so the phone isn't still ringing for a call
        // we've navigated away from. activeCall now points at the new call, so
        // the displaced call's later End/Released is filtered out by the
        // listener and can't tear down this fresh screen.
        if (displaced != null && displaced !== activeCall) {
            try {
                when (displaced.state) {
                    Call.State.End, Call.State.Released, Call.State.Error -> {}
                    else -> displaced.terminate()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to terminate displaced call", e)
            }
        }

        // Refresh caller info from the new intent.
        callerName = intent.getStringExtra(EXTRA_REMOTE_ADDRESS) ?: "Unbekannt"
        callerNumber = intent.getStringExtra(EXTRA_REMOTE_NUMBER) ?: callerName
        recordFilePath = intent.getStringExtra(EXTRA_RECORD_FILE) ?: ""

        // Visible widgets back to their fresh-call state.
        binding.tvCaller.text = callerName
        binding.tvHistory.text = ""
        binding.tvDuration.text = "00:00"
        binding.tvSummary?.text = "Zusammenfassung erscheint, sobald genug gesprochen wurde."
        binding.btnHangUp.visibility = View.VISIBLE
        binding.btnLoeschen.visibility = View.GONE
        applySpeakerButtonStyle()

        // Clear any waiting-call banner carried over from the previous call.
        binding.layoutWaitingCall?.visibility = View.GONE
        waitingCall = null
        waitingCallerName = ""
        waitingCallerNumber = ""
        waitingBannerHide = null

        if (isIncoming) showIncomingUI() else showCallingUI()

        // Same pre-listener race as onCreate: the recycled call may already be
        // active (e.g. an outgoing call dialled before this intent arrived), so
        // adopt its current state instead of waiting only for future callbacks.
        syncToCurrentCallState()
    }

    private var batteryWatcher: BatteryWatcher? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        if (batteryWatcher == null) {
            batteryWatcher = BatteryWatcher(this, binding.tvBattery).also { it.start() }
        }
    }

    override fun onPause() {
        super.onPause()
        batteryWatcher?.stop()
        batteryWatcher = null
    }
}
