package com.siptranscribe.app

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.siptranscribe.app.databinding.ActivityListenBinding

/**
 * "Zuhören" — transcribes ambient microphone audio for in-person
 * conversations the user can't follow by ear. There's no SIP call: we read
 * straight off the mic ([MicRecorder]) and feed a single [AzureSttEngine],
 * relying on its diarisation to colour-separate speakers.
 *
 * Per the agreed design this is deliberately minimal:
 *  - Azure only (Google needs a different push path; not worth it here).
 *  - Ephemeral — nothing is saved to history or archived to disk.
 *  - Summary is optional and only offered once listening has stopped.
 */
class ListenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListenBinding
    private val handler = Handler(Looper.getMainLooper())

    private var mic: MicRecorder? = null
    private var engine: AzureSttEngine? = null

    private val turns = mutableListOf<Turn>()
    private var partial: Turn? = null
    /** Assigns each diarised speaker a stable colour slot in order of appearance. */
    private val speakerColorMap = linkedMapOf<String, Int>()

    private var listening = false
    private var summaryShown = false
    private var summaryInFlight = false

    private var batteryWatcher: BatteryWatcher? = null

    private data class Turn(val speakerId: String, val text: String)

    companion object {
        private const val TAG = "ListenActivity"
        private const val REQ_MIC = 4711
        private const val MIN_CHARS_FOR_SUMMARY = 80

        /**
         * Per-speaker background colours. Azure's ConversationTranscriber
         * diarises the ambient mic and tags each utterance with a speakerId
         * ("Guest-1", "Guest-2", …); each new speaker claims the next slot
         * here in order of appearance.
         *
         * Unlike the 2-speaker call screen (where pale cream vs. pale blue is
         * enough), a room can hold several guests, so these are picked for
         * maximum separability for low-vision / colour-blind viewers: four
         * well-spaced hues (orange · blue · green · purple — an Okabe-Ito-style
         * colour-blind-safe set) kept light enough that the dark transcript
         * text stays high-contrast and easy to read on top.
         */
        private val SPEAKER_BG_COLORS = intArrayOf(
            0xFFFFB74D.toInt(),   // amber/orange — Sprecher 1
            0xFF64B5F6.toInt(),   // strong sky blue — Sprecher 2
            0xFF81C784.toInt(),   // green — Sprecher 3
            0xFFCE93D8.toInt()    // purple — Sprecher 4
        )
        private const val UNKNOWN_SPEAKER_BG = 0xFFEEEEEE.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityListenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvHistory.text = ""
        binding.btnStop.setOnClickListener { stopListening() }
        binding.btnSummary.setOnClickListener { runSummary() }
        binding.btnClose.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                showStoppedUi()
                binding.tvStatus.text = "Mikrofon-Berechtigung fehlt"
            }
        }
    }

    private fun startListening() {
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val endpoint = prefs.getString(MainActivity.KEY_AZURE_ENDPOINT, "")?.trim().orEmpty()
        val key = prefs.getString(MainActivity.KEY_AZURE_KEY, "")?.trim().orEmpty()
        if (endpoint.isBlank() || key.isBlank()) {
            showStoppedUi()
            binding.tvStatus.text = "Azure nicht konfiguriert"
            DiagLog.log(this, "Zuhören: Azure-Endpunkt/Schlüssel fehlt")
            return
        }

        val eng = AzureSttEngine(endpoint, key)
        eng.onResult = { text, isFinal, speakerId ->
            handler.post {
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
        eng.onError = { msg ->
            DiagLog.log(this, "Zuhören STT-Fehler: $msg")
            handler.post { binding.tvStatus.text = msg }
        }
        try {
            eng.prepare(16000)
        } catch (e: Exception) {
            Log.e(TAG, "engine.prepare failed", e)
            DiagLog.log(this, "Zuhören: Start fehlgeschlagen: ${e.message}")
            showStoppedUi()
            binding.tvStatus.text = "Start fehlgeschlagen"
            return
        }
        engine = eng

        val recorder = MicRecorder(16000)
        recorder.onPcmData = { buf, n -> engine?.feed(buf, n) }
        recorder.onError = { msg ->
            DiagLog.log(this, "Zuhören Mikrofon: $msg")
            handler.post {
                binding.tvStatus.text = msg
                stopListening()
            }
        }
        recorder.start()
        mic = recorder

        listening = true
        binding.tvStatus.text = "Höre zu …"
        binding.btnStop.visibility = View.VISIBLE
        binding.btnSummary.visibility = View.GONE
        binding.btnClose.visibility = View.GONE
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        try { mic?.stop() } catch (_: Exception) {}
        try { engine?.stop() } catch (_: Exception) {}
        mic = null
        engine = null
        partial = null
        renderTranscript()
        showStoppedUi()
    }

    private fun showStoppedUi() {
        binding.tvStatus.text = "Beendet"
        binding.btnStop.visibility = View.GONE
        binding.btnClose.visibility = View.VISIBLE
        // Only offer a summary when there's enough transcript to be worth it.
        binding.btnSummary.visibility =
            if (!summaryShown && buildLabeledTranscript().length >= MIN_CHARS_FOR_SUMMARY)
                View.VISIBLE else View.GONE
    }

    private fun runSummary() {
        if (summaryInFlight) return
        val text = buildLabeledTranscript()
        if (text.length < MIN_CHARS_FOR_SUMMARY) {
            binding.tvStatus.text = "Zu wenig gesprochen für eine Zusammenfassung"
            return
        }
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val endpoint = prefs.getString(MainActivity.KEY_AZURE_ENDPOINT, "")?.trim().orEmpty()
        val key = prefs.getString(MainActivity.KEY_AZURE_KEY, "")?.trim().orEmpty()
        val deployment = prefs.getString(MainActivity.KEY_AZURE_DEPLOYMENT, "")?.trim().orEmpty()
        if (endpoint.isBlank() || key.isBlank() || deployment.isBlank()) {
            binding.tvStatus.text = "Keine Zusammenfassung: Azure-Chat-Deployment fehlt"
            DiagLog.log(this, "Zuhören-Zusammenfassung übersprungen: Chat-Deployment fehlt")
            return
        }
        val prompt = prefs.getString(MainActivity.KEY_SUMMARY_PROMPT, null)
            ?.takeIf { it.isNotBlank() }
            ?: ConversationAnalyzer.DEFAULT_SYSTEM_PROMPT

        summaryInFlight = true
        binding.btnSummary.isEnabled = false
        binding.tvStatus.text = "Erstelle Zusammenfassung …"

        Thread({
            try {
                val result = ConversationAnalyzer(endpoint, key, deployment, prompt).analyze(text)
                handler.post {
                    summaryInFlight = false
                    summaryShown = true
                    binding.btnSummary.isEnabled = true
                    binding.btnSummary.visibility = View.GONE
                    binding.tvStatus.text = "Beendet"
                    showSummary(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listen summary failed", e)
                DiagLog.log(this@ListenActivity, "Zuhören-Zusammenfassung fehlgeschlagen: ${e.message}")
                handler.post {
                    summaryInFlight = false
                    binding.btnSummary.isEnabled = true
                    binding.tvStatus.text = "Zusammenfassung fehlgeschlagen: ${e.message}"
                }
            }
        }, "ListenSummary").start()
    }

    /**
     * Renders the summary inline below the transcript (ephemeral — nothing is
     * saved). Reuses CallActivity's section layout for familiarity.
     */
    private fun showSummary(r: ConversationAnalyzer.Result) {
        val sb = StringBuilder()
        r.topic?.let { sb.append("Thema: ").append(it).append('\n') }
        if (r.importantPoints.isNotEmpty()) {
            sb.append("\nWichtige Punkte:\n")
            r.importantPoints.forEachIndexed { i, p -> sb.append(i + 1).append(") ").append(p).append('\n') }
        }
        if (r.dates.isNotEmpty()) {
            sb.append("\nTermine:\n")
            r.dates.forEach { sb.append("• ").append(it).append('\n') }
        }
        if (r.todos.isNotEmpty()) {
            sb.append("\nAufgaben:\n")
            r.todos.forEach { sb.append("• ").append(it).append('\n') }
        }
        val builder = SpannableStringBuilder(buildTranscriptSpannable())
        if (builder.isNotEmpty()) builder.append("\n\n──────────────\n")
        builder.append("Zusammenfassung\n\n").append(sb.toString().trimEnd())
        binding.tvHistory.text = builder
        binding.scrollHistory.post { binding.scrollHistory.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bgColorFor(speakerId: String): Int {
        if (speakerId == "Unknown" || speakerId.isBlank()) return UNKNOWN_SPEAKER_BG
        val idx = speakerColorMap.getOrPut(speakerId) {
            val next = speakerColorMap.size
            if (next < SPEAKER_BG_COLORS.size) next else SPEAKER_BG_COLORS.size - 1
        }
        return SPEAKER_BG_COLORS[idx]
    }

    private fun buildTranscriptSpannable(): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        for ((i, turn) in turns.withIndex()) {
            appendTurn(builder, turn, partial = false)
            if (i < turns.size - 1) builder.append('\n')
        }
        partial?.let { p ->
            if (turns.isNotEmpty()) builder.append('\n')
            appendTurn(builder, p, partial = true)
        }
        return builder
    }

    private fun renderTranscript() {
        binding.tvHistory.text = buildTranscriptSpannable()
        binding.scrollHistory.post { binding.scrollHistory.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendTurn(builder: SpannableStringBuilder, turn: Turn, partial: Boolean) {
        val start = builder.length
        builder.append(' ').append(turn.text).append(' ')
        builder.setSpan(
            BackgroundColorSpan(bgColorFor(turn.speakerId)),
            start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (partial) {
            builder.setSpan(
                ForegroundColorSpan(Color.parseColor("#666666")),
                start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

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

    override fun onDestroy() {
        super.onDestroy()
        try { mic?.stop() } catch (_: Exception) {}
        try { engine?.stop() } catch (_: Exception) {}
        mic = null
        engine = null
        handler.removeCallbacksAndMessages(null)
    }
}
