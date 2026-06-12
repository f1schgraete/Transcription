package com.siptranscribe.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.siptranscribe.app.databinding.ActivityMissedCallsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Verpasste Anrufe" list. Reached automatically when a call ends without
 * being answered (see [CallActivity.endCall]), so the deaf user — who can't
 * hear a call come in — lands on a clear list of who tried to reach her rather
 * than being left on the call/Auflegen screen. One-tap Zurückrufen per entry
 * reuses MainActivity's auto-dial flow.
 */
class MissedCallsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMissedCallsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMissedCallsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersiveMode()
        binding.btnClose.setOnClickListener { finish() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.llMissed.removeAllViews()
        // CallHistory.load() is newest-first. "Missed" = any not-answered call:
        // incoming (verpasst) and outgoing (nicht erreicht).
        val missed = CallHistory.load(this).filter { !it.answered }
        if (missed.isEmpty()) {
            binding.llMissed.addView(TextView(this).apply {
                text = "Keine verpassten Anrufe"
                textSize = 18f
                setTextColor(getColor(R.color.status_neutral))
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
            return
        }
        missed.forEachIndexed { i, r ->
            binding.llMissed.addView(buildCard(r))
            if (i < missed.size - 1) binding.llMissed.addView(spacer())
        }
    }

    private fun buildCard(r: CallRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val name = r.callerName.takeIf { it.isNotBlank() && it != r.callerNumber }
            ?: r.callerNumber.ifBlank { "Unbekannt" }
        card.addView(TextView(this).apply {
            text = name
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        })

        if (r.callerNumber.isNotBlank() && r.callerNumber != name) {
            card.addView(TextView(this).apply {
                text = r.callerNumber
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
            })
        }

        val fmt = SimpleDateFormat("EEE dd.MM. HH:mm", Locale.GERMAN)
        val kind = if (r.direction == CallRecord.Direction.INCOMING) "Verpasst" else "Nicht erreicht"
        card.addView(TextView(this).apply {
            text = "$kind · ${fmt.format(Date(r.startTime))}"
            textSize = 14f
            setTextColor(getColor(R.color.status_neutral))
        })

        val number = r.callerNumber
        if (number.isNotBlank()) {
            card.addView(Button(this).apply {
                text = "Zurückrufen"
                textSize = 18f
                setBackgroundColor(getColor(R.color.call_green))
                setTextColor(getColor(R.color.text_on_dark))
                setOnClickListener { callBack(number, name) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
                ).also { it.setMargins(0, dp(8), 0, 0) }
            })
        }
        return card
    }

    private fun callBack(number: String, name: String) {
        // Reuse MainActivity's auto-dial path (it handles SIP registration timing).
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(ContactWidgetProvider.EXTRA_AUTO_DIAL_NUMBER, number)
            putExtra(ContactWidgetProvider.EXTRA_AUTO_DIAL_NAME, name)
        })
        finish()
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).also { it.setMargins(dp(14), dp(4), dp(14), dp(4)) }
        setBackgroundColor(0x1A000000)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
