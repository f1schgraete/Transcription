package com.siptranscribe.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.siptranscribe.app.databinding.ActivityContactSuggestionsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Caregiver-only screen. Lists pending [ContactSuggestion]s and lets the
 * caregiver either add them to system Contacts (via the system contacts
 * app, so no WRITE_CONTACTS permission is required) or dismiss them.
 *
 * The elderly user reaches this screen only by tapping the small button
 * in the advanced settings, so an accidental tap is effectively
 * impossible.
 */
class ContactSuggestionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactSuggestionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactSuggestionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // If the caregiver just came back from the contacts app, the number
        // they were viewing may now be in Contacts. We don't get an explicit
        // result code, so re-walk the suggestion list and promote anything
        // that's been "covered" since.
        ContactSuggestionStore.reconcileWithContacts(this)
        refresh()
    }

    private fun refresh() {
        binding.llContainer.removeAllViews()
        val items = ContactSuggestionStore.loadPending(this)
        if (items.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvEmpty.visibility = View.GONE
        items.forEachIndexed { i, s ->
            binding.llContainer.addView(buildCard(s))
            if (i < items.size - 1) binding.llContainer.addView(spacer())
        }
    }

    private fun buildCard(s: ContactSuggestion): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }

        card.addView(TextView(this).apply {
            text = s.suggestedName
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        })

        card.addView(TextView(this).apply {
            text = s.phoneNumber
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
        })

        val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val callsLabel = if (s.callCount == 1) "1 Anruf" else "${s.callCount} Anrufe"
        val durationLabel = "${s.totalDurationSeconds / 60} Min"
        card.addView(TextView(this).apply {
            text = "$callsLabel · $durationLabel · zuletzt ${fmt.format(Date(s.lastSeenAt))}"
            textSize = 13f
            setTextColor(getColor(R.color.status_neutral))
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        buttonRow.addView(Button(this).apply {
            text = "Ablehnen"
            textSize = 13f
            setOnClickListener { confirmDismiss(s) }
        })
        buttonRow.addView(Button(this).apply {
            text = "Hinzufügen"
            textSize = 13f
            setOnClickListener { startAddContact(s) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp(8), 0, 0, 0) }
        })
        card.addView(buttonRow)
        return card
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).also { it.setMargins(dp(14), dp(4), dp(14), dp(4)) }
        setBackgroundColor(0x1A000000)
    }

    /**
     * Pre-fills the system "new contact" form via ACTION_INSERT_OR_EDIT.
     * The contacts app does the actual writing, so we don't need
     * WRITE_CONTACTS permission and the caregiver can verify/edit fields
     * before saving. On return, [reconcileWithContacts] in onResume()
     * promotes the suggestion to ACCEPTED if the contact now exists.
     */
    private fun startAddContact(s: ContactSuggestion) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, s.suggestedName)
            putExtra(ContactsContract.Intents.Insert.PHONE, s.phoneNumber)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Kontakte-App fehlt")
                .setMessage("Auf diesem Gerät ist keine Kontakte-App verfügbar.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmDismiss(s: ContactSuggestion) {
        AlertDialog.Builder(this)
            .setTitle("Vorschlag ablehnen?")
            .setMessage("„${s.suggestedName}“ wird nicht mehr vorgeschlagen, " +
                "auch wenn die Nummer wieder anruft.")
            .setPositiveButton("Ablehnen") { _, _ ->
                ContactSuggestionStore.markStatus(
                    this, s.phoneNumber, ContactSuggestion.Status.DISMISSED
                )
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
