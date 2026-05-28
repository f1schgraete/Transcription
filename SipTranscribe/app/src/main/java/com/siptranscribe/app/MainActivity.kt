package com.siptranscribe.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.siptranscribe.app.databinding.ActivityMainBinding
import org.linphone.core.MediaEncryption
import org.linphone.core.TransportType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var testTranscriber: TranscriptionManager? = null

    /**
     * When the user picks a callable target (recent call or contact) we put
     * the *name* in the visible phone field for readability, and keep the
     * actual number to dial here. Cleared as soon as the user touches the
     * dialpad or types manually, so freshly-entered digits behave normally.
     */
    private var pendingCallNumber: String? = null
    private var suppressPhoneTextWatcher = false

    /**
     * Auto-dial from a widget tap. We can't makeCall() right away when the
     * SIP core isn't registered yet — store the (number, displayName) pair
     * and fire it from the registration callback once status flips to OK.
     * Null while no auto-dial is pending.
     */
    private var pendingAutoDial: Pair<String, String>? = null

    /** Slot index (0..3) being configured by the currently-running contact
     *  picker. Set when we launch [favouritePicker], read by the callback,
     *  then reset to -1. */
    private var currentFavouriteSlot: Int = -1

    private val favouritePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val slot = currentFavouriteSlot
        currentFavouriteSlot = -1
        // The hard-coded 0..3 guard used to silently drop slots 4 and 5 even
        // when the user had Anzahl Favoriten set to 6 — the contact picker
        // returned successfully but the save was a no-op.
        if (result.resultCode == RESULT_OK && uri != null && slot in 0 until MAX_FAVOURITE_SLOTS) {
            saveFavouriteFromUri(slot, uri)
        }
    }

    companion object {
        const val PREFS = "sip_prefs"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_DOMAIN = "domain"
        const val KEY_DISPLAY = "display_name"
        const val KEY_PORT = "port"
        const val KEY_TRANSPORT = "transport"
        const val KEY_EXPIRES = "expires"
        const val KEY_AUTH_USER = "auth_user"
        const val KEY_REALM = "realm"
        const val KEY_OUTBOUND_PROXY = "outbound_proxy"
        const val KEY_MEDIA_ENC = "media_enc"
        const val KEY_AZURE_ENDPOINT = "azure_endpoint"
        const val KEY_AZURE_KEY = "azure_key"
        const val KEY_AZURE_DEPLOYMENT = "azure_deployment"
        const val KEY_SUMMARY_PROMPT = "summary_prompt"
        const val KEY_USE_SRV = "use_dns_srv"
        const val KEY_SUMMARY_INTERVAL = "summary_interval_seconds"

        /**
         * STT provider choice and credentials.
         *
         * Azure keeps the existing two-mono-streams flow (one
         * AzureSttEngine per call leg). Google supports per-channel
         * recognition on stereo input — when selected, TranscriptionManager
         * interleaves the two mono recordings into stereo and feeds a
         * single GoogleSttEngine. See [STT_PROVIDER_AZURE] / [STT_PROVIDER_GOOGLE].
         */
        const val KEY_STT_PROVIDER = "stt_provider"
        const val KEY_GOOGLE_STT_KEY = "google_stt_key"
        const val KEY_GOOGLE_STT_LANGUAGE = "google_stt_language"
        /** Google Cloud project ID. The Speech-to-Text v2 streaming API
         *  requires every request to address a "recognizer" resource:
         *  `projects/<id>/locations/global/recognizers/_`. The trailing
         *  `_` means "use the inline config" so no recognizer resource
         *  has to be pre-created — but the project id is unavoidable. */
        const val KEY_GOOGLE_STT_PROJECT = "google_stt_project"
        const val STT_PROVIDER_AZURE = "azure"
        const val STT_PROVIDER_GOOGLE = "google"
        const val DEFAULT_GOOGLE_STT_LANGUAGE = "de-DE"
        /** When false (the default), the elderly user's own voice doesn't
         *  appear in the live transcript pane. We still record and feed
         *  both directions to the analyser so the summary stays useful. */
        const val KEY_TRANSCRIPT_SHOW_LOCAL = "transcript_show_local"

        /** Number of favourite slots displayed in the middle column.
         *  Clamped to [FAVOURITE_COUNT_OPTIONS] at read+write time. The
         *  upper bound is also the cap on how many name/number pairs we
         *  read out of prefs — values for slots beyond the cap are
         *  preserved across changes (so dropping 6→4 and going back to 6
         *  doesn't lose your work) but invisible. */
        const val KEY_FAVOURITE_COUNT = "favourite_count"
        const val DEFAULT_FAVOURITE_COUNT = 4
        val FAVOURITE_COUNT_OPTIONS = intArrayOf(2, 4, 6)
        const val MAX_FAVOURITE_SLOTS = 6
        /** Seconds between in-call summary refreshes. Bottoming out at 5 s
         *  keeps us from hammering Azure on a misconfigured value. */
        const val DEFAULT_SUMMARY_INTERVAL_SECONDS = 25
        const val MIN_SUMMARY_INTERVAL_SECONDS = 5

        // Answering-machine settings. The auto-pickup runtime is intentionally
        // not wired up yet — these keys just persist what the caregiver
        // configured so the toggle can be flipped on later without losing
        // values. KEY_MAILBOX_ENABLED stays false by default.
        const val KEY_MAILBOX_ENABLED = "mailbox_enabled"
        const val KEY_MAILBOX_TIMEOUT_SECONDS = "mailbox_timeout_seconds"
        const val KEY_MAILBOX_GREETING_TEXT = "mailbox_greeting_text"
        const val DEFAULT_MAILBOX_TIMEOUT_SECONDS = 20
        const val DEFAULT_MAILBOX_GREETING_TEXT =
            "Guten Tag, hier ist der Anschluss von Waltraud Hirsch. " +
            "Ich kann gerade nicht ans Telefon kommen. " +
            "Bitte hinterlassen Sie eine Nachricht nach dem Signalton."

        private const val REQ_PERMS = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        loadSettings()
        requestRequiredPermissions()
        // Drop anything older than 90 days so the prefs file and the
        // encrypted-archive directory don't grow without bound. Cheap.
        CallHistory.purgeExpired(this)

        // Dialpad digit buttons
        val digitButtons = mapOf(
            binding.btn0 to "0", binding.btn1 to "1", binding.btn2 to "2",
            binding.btn3 to "3", binding.btn4 to "4", binding.btn5 to "5",
            binding.btn6 to "6", binding.btn7 to "7", binding.btn8 to "8",
            binding.btn9 to "9", binding.btnStar to "*", binding.btnHash to "#"
        )
        digitButtons.forEach { (btn, digit) ->
            btn.setOnClickListener { appendToPhone(digit) }
        }

        binding.btnPlus.setOnClickListener { appendToPhone("+") }

        binding.btnDelete.setOnClickListener {
            if (pendingCallNumber != null) {
                // Field shows a contact / history name. "Delete one character"
                // doesn't make sense there — interpret it as "start over".
                clearCallTarget()
            } else {
                val t = binding.etPhone.text
                if (t != null && t.isNotEmpty()) t.delete(t.length - 1, t.length)
            }
        }
        binding.btnDelete.setOnLongClickListener {
            clearCallTarget()
            true
        }
        // Long-press 0 → + (standard phone convention)
        binding.btn0.setOnLongClickListener {
            appendToPhone("+")
            true
        }

        // Manual typing into the phone field invalidates any name-from-pick
        // override — once the user starts typing, what they see is what gets
        // dialled.
        binding.etPhone.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!suppressPhoneTextWatcher) pendingCallNumber = null
            }
        })

        // Collapsible settings sub-sections. wireSection swaps the trailing
        // arrow (\u25B8 / \u25BE) and toggles the content layout. Each binding pair
        // is nullable on the phone-landscape / portrait layouts (only the
        // tablet sw600dp variant has the new section headers) \u2014 wireSection
        // no-ops there so the activity still compiles for both.
        wireSection(binding.btnSectionDisplay, binding.layoutSectionDisplay)
        wireSection(binding.btnSectionSummary, binding.layoutSectionSummary)
        wireSection(binding.btnSectionMailbox, binding.layoutSectionMailbox)
        wireSection(binding.btnAdvanced, binding.layoutAdvanced)
        wireSection(binding.btnSectionDiag, binding.layoutSectionDiag)

        // Transport dropdown
        val transportAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.transport_types)
        )
        binding.actvTransport.setAdapter(transportAdapter)
        binding.actvTransport.threshold = 0
        binding.actvTransport.setOnClickListener { binding.actvTransport.showDropDown() }
        binding.actvTransport.setOnItemClickListener { _, _, _, _ ->
            val t = binding.actvTransport.text.toString()
            val currentPort = binding.etPort.text.toString().trim()
            if (currentPort == "5060" || currentPort == "5061" || currentPort.isEmpty()) {
                binding.etPort.setText(if (t == "TLS") "5061" else "5060")
            }
        }

        // Media encryption dropdown
        val mediaEncAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.media_encryptions)
        )
        binding.actvMediaEnc.setAdapter(mediaEncAdapter)
        binding.actvMediaEnc.threshold = 0
        binding.actvMediaEnc.setOnClickListener { binding.actvMediaEnc.showDropDown() }

        // STT provider dropdown. Nullable because the field only exists on
        // the tablet layout for now (layout-sw600dp). Same threshold-0 +
        // forced showDropDown pattern as the other dropdowns.
        binding.actvSttProvider?.let { provider ->
            val sttAdapter = ArrayAdapter(
                this, android.R.layout.simple_dropdown_item_1line,
                resources.getStringArray(R.array.stt_providers)
            )
            provider.setAdapter(sttAdapter)
            provider.threshold = 0
            provider.setOnClickListener { provider.showDropDown() }
        }

        // Favourite-count dropdown. Selection is applied live so the
        // caregiver sees the new tile count in the middle column without
        // tapping Verbinden first.
        //
        // Note: read-only AutoCompleteTextViews (inputType="none") used with
        // the Material ExposedDropdownMenu style are flaky if you rely on
        // the default click→showDropDown behaviour — sometimes the popup
        // shows only the currently-selected row, sometimes nothing at all,
        // until the user clicks elsewhere and back. Forcing showDropDown()
        // from an explicit OnClickListener and pinning the filter threshold
        // to 0 gives the reliable "tap → full list" behaviour the caregiver
        // expects. Same fix applied to the transport + media-encryption
        // dropdowns below.
        val favCountAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.favourite_counts)
        )
        binding.actvFavouriteCount.setAdapter(favCountAdapter)
        binding.actvFavouriteCount.threshold = 0
        binding.actvFavouriteCount.setOnClickListener {
            binding.actvFavouriteCount.showDropDown()
        }
        binding.actvFavouriteCount.setOnItemClickListener { _, _, _, _ ->
            val n = binding.actvFavouriteCount.text.toString().toIntOrNull()
                ?: DEFAULT_FAVOURITE_COUNT
            val clamped = if (n in FAVOURITE_COUNT_OPTIONS) n else DEFAULT_FAVOURITE_COUNT
            prefs.edit().putInt(KEY_FAVOURITE_COUNT, clamped).apply()
            refreshFavourites()
        }

        binding.btnRegister.setOnClickListener { saveAndRegister() }
        binding.btnLogout.setOnClickListener { confirmAndLogout() }
        binding.btnResetPrompt.setOnClickListener {
            binding.etSummaryPrompt.setText(ConversationAnalyzer.DEFAULT_SYSTEM_PROMPT)
        }
        binding.btnContactSuggestions.setOnClickListener {
            startActivity(Intent(this, ContactSuggestionsActivity::class.java))
        }
        binding.btnDiagnostics.setOnClickListener { showDiagnosticsDialog() }
        setupSttTest()

        // Persist UX-only toggles immediately when flipped, instead of
        // waiting for "Verbinden" to save them. The Verbinden flow still
        // writes the same keys so this isn't a behavioural change for the
        // setup path — it just means the caregiver doesn't need to remember
        // to re-save settings after toggling a single checkbox.
        binding.cbTranscriptShowLocal.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_TRANSCRIPT_SHOW_LOCAL, checked).apply()
        }
        binding.cbUseSrv.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_USE_SRV, checked).apply()
        }
        binding.cbMailboxEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_MAILBOX_ENABLED, checked).apply()
        }

        binding.btnCall.setOnClickListener {
            // If the user picked a name from history or contacts the visible
            // text is the display name; the actual number lives in
            // pendingCallNumber. Manual typing clears that override so the
            // typed digits are dialled instead.
            val number = pendingCallNumber ?: binding.etPhone.text.toString().trim()
            if (number.isEmpty()) {
                toast("Bitte eine Nummer eingeben")
                return@setOnClickListener
            }
            if (!LinphoneManager.isRegistered) {
                toast("Bitte zuerst Einstellungen speichern")
                return@setOnClickListener
            }
            makeCall(number)
        }

        // Settings button: toggles card_settings visibility
        binding.btnSettings.setOnClickListener {
            val visible = binding.cardSettings.visibility == View.VISIBLE
            binding.cardSettings.visibility = if (visible) View.GONE else View.VISIBLE
        }

        // Keep registration status label updated
        LinphoneManager.onRegistrationStateChanged = { ok, msg ->
            runOnUiThread {
                binding.tvStatus.text = msg
                binding.tvStatus.setTextColor(
                    if (ok) getColor(R.color.status_ok) else getColor(R.color.status_error)
                )
                updateRegistrationUI(ok)

                // A widget tap can land here before the core is registered.
                // Fire the queued call as soon as registration succeeds, or
                // drop it (with a toast) if it definitively fails so the
                // user isn't left wondering.
                val pending = pendingAutoDial
                if (pending != null) {
                    if (ok) {
                        pendingAutoDial = null
                        makeCall(pending.first)
                        clearCallTarget()
                    } else if (msg.startsWith("Registrierung fehlgeschlagen")) {
                        pendingAutoDial = null
                        clearCallTarget()
                        toast("Anruf konnte nicht gestartet werden: keine SIP-Verbindung.")
                    }
                }
            }
        }

        // Apply initial state
        if (LinphoneManager.isRegistered) {
            binding.tvStatus.text = "Registriert"
            binding.tvStatus.setTextColor(getColor(R.color.status_ok))
        }
        updateRegistrationUI(LinphoneManager.isRegistered)

        refreshFavourites()
        autoLoginIfPossible()
        handleAutoDialIntent(intent)
    }

    private fun keyFavouriteName(slot: Int) = "favourite_${slot}_name"
    private fun keyFavouriteNumber(slot: Int) = "favourite_${slot}_number"

    /**
     * Generic show/hide wiring for the collapsible Einstellungen sub-sections.
     * The button's label is expected to end with " ▸" (collapsed) or " ▾"
     * (expanded); we swap whichever arrow is currently there each tap, so
     * the XML default can carry the initial direction.
     *
     * Both arguments are nullable to keep callers tidy: layout variants
     * that don't include a given section pass null and the helper no-ops.
     */
    private fun wireSection(button: android.widget.Button?, content: View?) {
        if (button == null || content == null) return
        button.setOnClickListener {
            val expand = content.visibility != View.VISIBLE
            content.visibility = if (expand) View.VISIBLE else View.GONE
            val base = button.text.toString()
                .trimEnd()
                .trimEnd('▸', '▾')
                .trimEnd()
            button.text = "$base ${if (expand) "▾" else "▸"}"
        }
    }

    /** Validates the stored favourite count against [FAVOURITE_COUNT_OPTIONS],
     *  falling back to the default if the prefs file has a stale or invalid
     *  value (e.g. an older build wrote one we no longer accept). */
    private fun currentFavouriteCount(): Int {
        val raw = prefs.getInt(KEY_FAVOURITE_COUNT, DEFAULT_FAVOURITE_COUNT)
        return if (raw in FAVOURITE_COUNT_OPTIONS) raw else DEFAULT_FAVOURITE_COUNT
    }

    /**
     * Drives the 6 static favourite tiles in `ll_favourites` (a 2-column
     * GridLayout). Each slot's button is either VISIBLE (with its stored
     * name and click handlers wired up) or GONE (when the active count
     * doesn't reach that slot). We deliberately keep the buttons in XML
     * with fixed 80dp heights — earlier dynamic-inflation and rowWeight-
     * driven approaches both produced render artifacts on this tablet
     * ("4 became 2 enlarged", slot 5/6 not appearing). Toggling visibility
     * on stable Button instances is the most boring, predictable option.
     *
     * Safe to call on layouts without the favourites container (phone
     * landscape / portrait fallback) — every binding field is null there
     * and we early-out.
     */
    private fun refreshFavourites() {
        if (binding.llFavourites == null) return

        val buttons: Array<android.widget.Button?> = arrayOf(
            binding.btnFav0, binding.btnFav1, binding.btnFav2,
            binding.btnFav3, binding.btnFav4, binding.btnFav5
        )
        val count = currentFavouriteCount()
        for (slot in 0 until MAX_FAVOURITE_SLOTS) {
            val btn = buttons[slot] ?: continue
            if (slot >= count) {
                btn.visibility = View.GONE
                continue
            }
            btn.visibility = View.VISIBLE

            val name = prefs.getString(keyFavouriteName(slot), null)?.takeIf { it.isNotBlank() }
            val number = prefs.getString(keyFavouriteNumber(slot), null)?.takeIf { it.isNotBlank() }
            if (name != null && number != null) {
                btn.text = name
                btn.setOnClickListener { setCallTarget(name, number) }
            } else {
                btn.text = "+ Hinzufügen"
                btn.setOnClickListener { launchFavouritePicker(slot) }
            }
            btn.setOnLongClickListener {
                launchFavouritePicker(slot)
                true
            }
        }
    }

    private fun launchFavouritePicker(slot: Int) {
        currentFavouriteSlot = slot
        try {
            favouritePicker.launch(
                Intent(Intent.ACTION_PICK).apply {
                    type = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
                }
            )
        } catch (e: Exception) {
            currentFavouriteSlot = -1
            toast("Kontakte-App nicht verfügbar")
        }
    }

    private fun saveFavouriteFromUri(slot: Int, uri: android.net.Uri) {
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1)?.trim().orEmpty()
                    if (name.isNotBlank() && number.isNotBlank()) {
                        prefs.edit()
                            .putString(keyFavouriteName(slot), name)
                            .putString(keyFavouriteNumber(slot), number)
                            .apply()
                        refreshFavourites()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "saveFavouriteFromUri failed", e)
        }
    }

    /**
     * The activity is single-top so a widget tap on a running app delivers
     * here instead of re-creating us. Pick up any auto-dial extras that came
     * with the fresh intent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoDialIntent(intent)
    }

    /**
     * Consumes the extras from a [ContactWidgetProvider] tap. If we're
     * already registered we dial immediately. Otherwise the (number, name)
     * pair is queued in [pendingAutoDial] and fired from the registration
     * callback above. The extras are removed from the intent so a later
     * onResume / onNewIntent doesn't re-dial the same contact.
     */
    private fun handleAutoDialIntent(src: Intent?) {
        val number = src?.getStringExtra(ContactWidgetProvider.EXTRA_AUTO_DIAL_NUMBER)
            ?.takeIf { it.isNotBlank() } ?: return
        val name = src.getStringExtra(ContactWidgetProvider.EXTRA_AUTO_DIAL_NAME).orEmpty()
        src.removeExtra(ContactWidgetProvider.EXTRA_AUTO_DIAL_NUMBER)
        src.removeExtra(ContactWidgetProvider.EXTRA_AUTO_DIAL_NAME)

        // Show the contact's name in the field so the screen is informative
        // even if SIP registration takes a beat to come up.
        setCallTarget(name.ifBlank { number }, number)

        if (LinphoneManager.isRegistered) {
            makeCall(number)
            clearCallTarget()
        } else {
            pendingAutoDial = number to name
            // Kick off (or top up) the registration flow so the callback above
            // has something to fire on. autoLoginIfPossible is a no-op when
            // already in progress.
            autoLoginIfPossible()
        }
    }

    /**
     * If the user has previously saved SIP credentials and the core is
     * not already registered, fire the registration silently so they
     * don't have to scroll down and tap Verbinden every time the app
     * (re)launches. Safe to call repeatedly — when nothing's saved or
     * we're already registered it's a no-op.
     *
     * Triggered after [loadSettings] populates the UI fields from prefs,
     * so [saveAndRegister] (which reads from those fields) sees the
     * persisted values unchanged.
     */
    private fun autoLoginIfPossible() {
        if (LinphoneManager.isRegistered) return
        val user = prefs.getString(KEY_USER, "").orEmpty().trim()
        val pass = prefs.getString(KEY_PASS, "").orEmpty().trim()
        val domain = prefs.getString(KEY_DOMAIN, "").orEmpty().trim()
        if (user.isBlank() || pass.isBlank() || domain.isBlank()) return
        saveAndRegister()
    }

    override fun onResume() {
        super.onResume()
        // Refresh history and contacts whenever returning to this screen
        // (e.g. after a call ends, or after the user added/removed someone
        // via the Vorgeschlagene Kontakte → system contacts app flow).
        if (LinphoneManager.isRegistered) {
            refreshCallHistory()
            refreshContacts()
        }
        startBatteryWatcher()
    }

    override fun onPause() {
        super.onPause()
        stopBatteryWatcher()
    }

    private var batteryWatcher: BatteryWatcher? = null

    private fun startBatteryWatcher() {
        if (batteryWatcher != null) return
        batteryWatcher = BatteryWatcher(
            this,
            binding.tvBattery,
            binding.tvChargeHint
        ).also { it.start() }
    }

    private fun stopBatteryWatcher() {
        batteryWatcher?.stop()
        batteryWatcher = null
    }

    private fun updateRegistrationUI(registered: Boolean) {
        if (registered) {
            binding.cardSettings.visibility = View.GONE
            binding.cardRecentCalls.visibility = View.VISIBLE
            binding.cardContacts.visibility = View.VISIBLE
            refreshCallHistory()
            refreshContacts()
        } else {
            binding.cardSettings.visibility = View.VISIBLE
            binding.cardRecentCalls.visibility = View.GONE
            binding.cardContacts.visibility = View.GONE
        }
    }

    private fun refreshContacts() {
        val contacts = ContactsLookup.loadAllContacts(this)
        binding.llContacts.removeAllViews()
        if (contacts.isEmpty()) {
            binding.tvNoContacts.visibility = View.VISIBLE
            binding.tvNoContacts.text = if (ContactsLookup.hasPermission(this))
                "Keine Kontakte gefunden"
            else
                "Berechtigung für Kontakte fehlt"
            return
        }
        binding.tvNoContacts.visibility = View.GONE
        contacts.forEachIndexed { i, contact ->
            binding.llContacts.addView(buildContactRow(contact))
            if (i < contacts.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).also { it.setMargins(dp(60), 0, dp(16), 0) }
                    setBackgroundColor(0x1A000000)
                }
                binding.llContacts.addView(divider)
            }
        }
    }

    private fun buildContactRow(contact: ContactsLookup.Entry): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { setCallTarget(contact.displayName, contact.phoneNumber) }
        }

        // Lead column kept the same width as call-history rows so the two
        // lists' name columns line up vertically even though contacts have
        // no direction icon.
        val lead = TextView(this).apply {
            text = "·"
            textSize = 22f
            setTextColor(getColor(R.color.status_neutral))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        textColumn.addView(TextView(this).apply {
            text = contact.displayName
            textSize = 20f
            setTextColor(getColor(R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textColumn.addView(TextView(this).apply {
            text = contact.phoneNumber
            textSize = 13f
            setTextColor(getColor(R.color.status_neutral))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        row.addView(lead)
        row.addView(textColumn)
        return row
    }

    private fun refreshCallHistory() {
        val records = CallHistory.load(this)
        binding.llCallHistory.removeAllViews()

        if (records.isEmpty()) {
            binding.tvNoCalls.visibility = View.VISIBLE
            return
        }

        binding.tvNoCalls.visibility = View.GONE

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("dd.MM.", Locale.getDefault())
        val todayCal = Calendar.getInstance()
        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        val displayRecords = records.take(10)
        displayRecords.forEachIndexed { index, record ->
            val row = buildHistoryRow(record, timeFmt, dateFmt, todayCal, yesterdayCal)
            binding.llCallHistory.addView(row)

            // Divider between rows
            if (index < displayRecords.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).also { it.setMargins(dp(60), 0, dp(16), 0) }
                    setBackgroundColor(0x1A000000)
                }
                binding.llCallHistory.addView(divider)
            }
        }
    }

    private fun buildHistoryRow(
        record: CallRecord,
        timeFmt: SimpleDateFormat,
        dateFmt: SimpleDateFormat,
        todayCal: Calendar,
        yesterdayCal: Calendar
    ): LinearLayout {
        // Direction icon and colour
        val (iconText, iconColor) = when {
            record.direction == CallRecord.Direction.INCOMING && record.answered ->
                "←" to getColor(R.color.call_green)
            record.direction == CallRecord.Direction.INCOMING && !record.answered ->
                "✗" to getColor(R.color.call_red)
            else ->
                "→" to getColor(R.color.accent)
        }

        // Time label
        val callCal = Calendar.getInstance().apply { timeInMillis = record.startTime }
        val timeLabel = when {
            isSameDay(callCal, todayCal)     -> timeFmt.format(record.startTime)
            isSameDay(callCal, yesterdayCal) -> "gestern"
            else                             -> dateFmt.format(record.startTime)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            isLongClickable = true
            // Ripple background
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            // Tap = call back (fills dial field with the *name* for clarity;
            // the actual number is held in pendingCallNumber so Anrufen still
            // works). Long-press = caregiver-facing detail view. Long-press
            // stays a hidden affordance so the elderly user never stumbles
            // into it by accident.
            setOnClickListener { setCallTarget(displayNameForRecord(record), record.callerNumber) }
            setOnLongClickListener {
                showCallDetailDialog(record)
                true
            }
        }

        val iconView = TextView(this).apply {
            text = iconText
            textSize = 22f
            setTextColor(iconColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            setTypeface(null, Typeface.BOLD)
        }

        val nameView = TextView(this).apply {
            text = displayNameForRecord(record)
            textSize = 20f
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val timeView = TextView(this).apply {
            text = timeLabel
            textSize = 15f
            setTextColor(getColor(R.color.status_neutral))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(iconView)
        row.addView(nameView)
        row.addView(timeView)
        return row
    }

    private fun isSameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /**
     * Marks the next call as targeting [number] while the field shows
     * [displayName]. [suppressPhoneTextWatcher] keeps the watcher from
     * tripping its "user is typing" branch when we set the text ourselves.
     */
    private fun setCallTarget(displayName: String, number: String) {
        pendingCallNumber = number
        suppressPhoneTextWatcher = true
        binding.etPhone.setText(displayName)
        suppressPhoneTextWatcher = false
    }

    private fun clearCallTarget() {
        pendingCallNumber = null
        suppressPhoneTextWatcher = true
        binding.etPhone.text?.clear()
        suppressPhoneTextWatcher = false
    }

    /**
     * Append to the phone field as if the user had typed [s]. If a name is
     * currently shown from a history/contact tap, replace it first — the
     * user is starting a fresh manual number.
     */
    private fun appendToPhone(s: String) {
        if (pendingCallNumber != null) clearCallTarget()
        binding.etPhone.append(s)
    }

    /**
     * Resolves the best label for a historical call. The saved `callerName`
     * is preferred when it isn't a placeholder (the raw number), because a
     * contact may have since been renamed or deleted and we don't want
     * historical entries to lose their original identification. Only when
     * the saved name *is* just the number do we try a fresh lookup — handy
     * when the user adds a contact for a number they previously called.
     */
    private fun displayNameForRecord(record: CallRecord): String {
        if (record.callerName.isNotBlank() && record.callerName != record.callerNumber) {
            return record.callerName
        }
        return ContactsLookup.displayNameForNumber(this, record.callerNumber)
            ?: record.callerName.ifBlank { record.callerNumber }
    }

    /**
     * Caregiver-facing detail view. Reached only by long-pressing a history
     * row, so it never appears for the elderly user by accident. Shows the
     * persisted summary and full transcript for the call and offers a
     * "Löschen" button that scrubs both the metadata and the encrypted
     * archive file.
     */
    private fun showCallDetailDialog(record: CallRecord) {
        val archive = CallArchiveStore.load(this, record.id)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val headerFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val durationLabel = if (record.durationSeconds >= 60)
            "${record.durationSeconds / 60} Min ${record.durationSeconds % 60} Sek"
        else
            "${record.durationSeconds} Sek"
        container.addView(TextView(this).apply {
            text = "${displayNameForRecord(record)}\n" +
                "${headerFmt.format(record.startTime)} · $durationLabel · ${record.callerNumber}"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, dp(10))
        })

        if (archive == null) {
            container.addView(TextView(this).apply {
                text = "Kein Transkript gespeichert."
                textSize = 14f
                setTextColor(getColor(R.color.status_neutral))
            })
        } else {
            if (!archive.summaryText.isNullOrBlank()) {
                container.addView(sectionHeader("Zusammenfassung"))
                container.addView(TextView(this).apply {
                    text = archive.summaryText
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(0, 0, 0, dp(10))
                })
            }
            if (archive.transcriptTurns.isNotEmpty()) {
                container.addView(sectionHeader("Transkript"))
                container.addView(TextView(this).apply {
                    text = archive.transcriptTurns.joinToString("\n") {
                        if (it.speakerLabel.isBlank()) it.text
                        else "${it.speakerLabel}: ${it.text}"
                    }
                    textSize = 13f
                    setTextColor(getColor(R.color.text_primary))
                })
            }
        }

        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Anrufdetails")
            .setView(scroll)
            .setPositiveButton("Schließen", null)
            .setNeutralButton("Löschen") { _, _ -> confirmDeleteCall(record) }
            .show()
    }

    private fun sectionHeader(label: String) = TextView(this).apply {
        text = label
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(getColor(R.color.text_primary))
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun confirmDeleteCall(record: CallRecord) {
        AlertDialog.Builder(this)
            .setTitle("Eintrag löschen?")
            .setMessage("Der Anruf und das gespeicherte Transkript werden entfernt.")
            .setPositiveButton("Löschen") { _, _ ->
                CallHistory.remove(this, record.id)
                refreshCallHistory()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /**
     * Surfaces logcat output as a dialog so the caregiver can diagnose
     * registration/network failures without needing adb on a connected
     * laptop. Since API 24 each app can only read its own log lines
     * without the system-only READ_LOGS permission, which is exactly
     * what we need — we see Linphone's belle-sip / ortp output and our
     * own Log.* calls, nothing else on the device.
     *
     * Tapping "Kopieren" sends the buffer to the system clipboard so
     * it can be pasted into an email/chat for the developer.
     */
    private fun showDiagnosticsDialog() {
        val log = readRecentLogcat(maxLines = 500)
        val tv = TextView(this).apply {
            text = if (log.isBlank())
                "Keine Log-Einträge verfügbar.\n\nManche Geräte erlauben Apps nicht, ihre eigenen Logs zu lesen. Bitte mit adb logcat von einem Computer aus prüfen."
            else log
            textSize = 10f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Diagnose-Log (zuletzt)")
            .setView(scroll)
            .setPositiveButton("Schließen", null)
            .setNeutralButton("Kopieren") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("SipTranscribe diagnostic log", tv.text)
                )
                toast("Log in Zwischenablage kopiert")
            }
            .show()
    }

    /**
     * Reads the tail of the current process's logcat buffer. Falls back
     * to an empty string on any failure (some OEM ROMs strip the logcat
     * binary or block process-self-reads even within the API contract).
     */
    private fun readRecentLogcat(maxLines: Int): String = try {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "-t", maxLines.toString())
        )
        proc.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun loadSettings() {
        binding.etUsername.setText(prefs.getString(KEY_USER, ""))
        binding.etPassword.setText(prefs.getString(KEY_PASS, ""))
        binding.etDomain.setText(prefs.getString(KEY_DOMAIN, "tel.t-online.de"))
        binding.etDisplayName.setText(prefs.getString(KEY_DISPLAY, ""))
        // Defaults match the combination the Linphone reference app uses
        // against tel.t-online.de today: UDP/5060 with no media encryption.
        // The earlier TLS/SRTP defaults produced "io error" on networks that
        // don't pass TLS on 5061. The advanced UI still lets the user pick
        // TLS/SRTP if their line supports it.
        binding.etPort.setText(prefs.getInt(KEY_PORT, 5060).toString())
        binding.actvTransport.setText(prefs.getString(KEY_TRANSPORT, "UDP"), false)
        binding.etExpires.setText(prefs.getInt(KEY_EXPIRES, 3600).toString())
        binding.etAuthUser.setText(prefs.getString(KEY_AUTH_USER, ""))
        binding.etRealm.setText(prefs.getString(KEY_REALM, ""))
        binding.etOutboundProxy.setText(prefs.getString(KEY_OUTBOUND_PROXY, ""))
        binding.actvMediaEnc.setText(prefs.getString(KEY_MEDIA_ENC, "Keine"), false)
        binding.cbUseSrv.isChecked = prefs.getBoolean(KEY_USE_SRV, true)
        binding.cbTranscriptShowLocal.isChecked =
            prefs.getBoolean(KEY_TRANSCRIPT_SHOW_LOCAL, false)
        val favCount = currentFavouriteCount()
        binding.actvFavouriteCount.setText(favCount.toString(), false)
        binding.etAzureEndpoint.setText(prefs.getString(KEY_AZURE_ENDPOINT, ""))
        binding.etAzureKey.setText(prefs.getString(KEY_AZURE_KEY, ""))
        binding.etAzureDeployment.setText(prefs.getString(KEY_AZURE_DEPLOYMENT, ""))
        // STT provider stays in prefs as a lowercase key ("azure" / "google")
        // but is shown to the user via the capitalised array entries.
        val providerKey = prefs.getString(KEY_STT_PROVIDER, STT_PROVIDER_AZURE)
        val providerLabel =
            if (providerKey == STT_PROVIDER_GOOGLE) "Google" else "Azure"
        binding.actvSttProvider?.setText(providerLabel, false)
        binding.etGoogleSttKey?.setText(prefs.getString(KEY_GOOGLE_STT_KEY, ""))
        binding.etGoogleSttLanguage?.setText(
            prefs.getString(KEY_GOOGLE_STT_LANGUAGE, DEFAULT_GOOGLE_STT_LANGUAGE)
        )
        binding.etGoogleSttProject?.setText(prefs.getString(KEY_GOOGLE_STT_PROJECT, ""))
        binding.etSummaryPrompt.setText(
            prefs.getString(KEY_SUMMARY_PROMPT, ConversationAnalyzer.DEFAULT_SYSTEM_PROMPT)
        )
        binding.etSummaryInterval.setText(
            prefs.getInt(KEY_SUMMARY_INTERVAL, DEFAULT_SUMMARY_INTERVAL_SECONDS).toString()
        )
        binding.cbMailboxEnabled.isChecked =
            prefs.getBoolean(KEY_MAILBOX_ENABLED, false)
        binding.etMailboxTimeout.setText(
            prefs.getInt(KEY_MAILBOX_TIMEOUT_SECONDS, DEFAULT_MAILBOX_TIMEOUT_SECONDS).toString()
        )
        binding.etMailboxGreeting.setText(
            prefs.getString(KEY_MAILBOX_GREETING_TEXT, DEFAULT_MAILBOX_GREETING_TEXT)
        )
    }

    private fun saveAndRegister() {
        val user = binding.etUsername.text.toString().trim()
        val pass = binding.etPassword.text.toString().trim()
        val domain = binding.etDomain.text.toString().trim()
        val display = binding.etDisplayName.text.toString().trim().ifEmpty { user }
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 5061
        val transportStr = binding.actvTransport.text.toString()
        val transport = when (transportStr) {
            "UDP" -> TransportType.Udp
            "TCP" -> TransportType.Tcp
            else -> TransportType.Tls
        }
        val expires = binding.etExpires.text.toString().trim().toIntOrNull() ?: 3600
        val authUser = binding.etAuthUser.text.toString().trim().ifEmpty { null }
        val realm = binding.etRealm.text.toString().trim().ifEmpty { null }
        val outboundProxy = binding.etOutboundProxy.text.toString().trim().ifEmpty { null }
        val mediaEncStr = binding.actvMediaEnc.text.toString()
        val mediaEncryption = when (mediaEncStr) {
            "SRTP" -> MediaEncryption.SRTP
            "ZRTP" -> MediaEncryption.ZRTP
            "DTLS" -> MediaEncryption.DTLS
            else -> MediaEncryption.None
        }
        val useSrv = binding.cbUseSrv.isChecked

        if (user.isEmpty() || pass.isEmpty() || domain.isEmpty()) {
            toast("Bitte alle Felder ausfuellen")
            return
        }

        prefs.edit().apply {
            putString(KEY_USER, user)
            putString(KEY_PASS, pass)
            putString(KEY_DOMAIN, domain)
            putString(KEY_DISPLAY, display)
            putInt(KEY_PORT, port)
            putString(KEY_TRANSPORT, transportStr)
            putInt(KEY_EXPIRES, expires)
            putString(KEY_AUTH_USER, authUser ?: "")
            putString(KEY_REALM, realm ?: "")
            putString(KEY_OUTBOUND_PROXY, outboundProxy ?: "")
            putString(KEY_MEDIA_ENC, mediaEncStr)
            putBoolean(KEY_USE_SRV, useSrv)
            putBoolean(KEY_TRANSCRIPT_SHOW_LOCAL, binding.cbTranscriptShowLocal.isChecked)
            putString(KEY_AZURE_ENDPOINT, binding.etAzureEndpoint.text.toString().trim())
            putString(KEY_AZURE_KEY, binding.etAzureKey.text.toString().trim())
            putString(KEY_AZURE_DEPLOYMENT, binding.etAzureDeployment.text.toString().trim())
            val providerLabel = binding.actvSttProvider?.text?.toString().orEmpty()
            putString(
                KEY_STT_PROVIDER,
                if (providerLabel.equals("Google", ignoreCase = true)) STT_PROVIDER_GOOGLE
                else STT_PROVIDER_AZURE
            )
            binding.etGoogleSttKey?.let {
                putString(KEY_GOOGLE_STT_KEY, it.text.toString().trim())
            }
            binding.etGoogleSttLanguage?.let {
                val lang = it.text.toString().trim().ifEmpty { DEFAULT_GOOGLE_STT_LANGUAGE }
                putString(KEY_GOOGLE_STT_LANGUAGE, lang)
            }
            binding.etGoogleSttProject?.let {
                putString(KEY_GOOGLE_STT_PROJECT, it.text.toString().trim())
            }
            putString(KEY_SUMMARY_PROMPT, binding.etSummaryPrompt.text.toString())
            // Allow blank or invalid input to fall back to the default rather
            // than persisting a bad value the user can't see in the UI.
            val intervalRaw = binding.etSummaryInterval.text?.toString()?.trim()
            val interval = intervalRaw?.toIntOrNull()?.coerceAtLeast(MIN_SUMMARY_INTERVAL_SECONDS)
                ?: DEFAULT_SUMMARY_INTERVAL_SECONDS
            putInt(KEY_SUMMARY_INTERVAL, interval)

            putBoolean(KEY_MAILBOX_ENABLED, binding.cbMailboxEnabled.isChecked)
            val mbTimeoutRaw = binding.etMailboxTimeout.text?.toString()?.trim()
            val mbTimeout = mbTimeoutRaw?.toIntOrNull()?.coerceAtLeast(5)
                ?: DEFAULT_MAILBOX_TIMEOUT_SECONDS
            putInt(KEY_MAILBOX_TIMEOUT_SECONDS, mbTimeout)
            putString(KEY_MAILBOX_GREETING_TEXT, binding.etMailboxGreeting.text.toString())

            apply()
        }

        SipService.start(this)
        binding.root.postDelayed({
            LinphoneManager.registerAccount(
                user, pass, domain, display,
                port, transport, expires,
                authUser, realm, outboundProxy,
                mediaEncryption,
                useSrv
            )
        }, 800)

        binding.tvStatus.text = "Verbindung wird hergestellt..."
        binding.tvStatus.setTextColor(getColor(R.color.status_neutral))
    }

    private fun makeCall(number: String) {
        val recordFilePath = "${filesDir.absolutePath}/call_${System.currentTimeMillis()}.wav"
        val call = LinphoneManager.makeCall(number, recordFilePath)
        if (call != null) {
            val displayName = ContactsLookup.displayNameForNumber(this, number) ?: number
            startActivity(Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_IS_INCOMING, false)
                putExtra(CallActivity.EXTRA_REMOTE_ADDRESS, displayName)
                putExtra(CallActivity.EXTRA_REMOTE_NUMBER, number)
                putExtra(CallActivity.EXTRA_RECORD_FILE, recordFilePath)
            })
        } else {
            toast("Anruf konnte nicht gestartet werden")
        }
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                it.add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
    }

    /**
     * STT test: reads {filesDir}/test.wav through the same pipeline used during a real call.
     * Push a WAV file to the device first:
     *   adb push your_file.wav /data/data/com.siptranscribe.app/files/test.wav
     */
    private fun setupSttTest() {
        binding.btnTestStt.setOnClickListener {
            val running = testTranscriber != null
            if (running) {
                testTranscriber?.stop()
                testTranscriber = null
                binding.btnTestStt.text = "STT Test (test.wav)"
                return@setOnClickListener
            }

            val testFile = java.io.File(filesDir, "test.wav")
            if (!testFile.exists()) {
                binding.tvTestResult.visibility = View.VISIBLE
                binding.tvTestResult.text =
                    "Datei nicht gefunden. Bitte zuerst pushen:\n" +
                    "adb push <datei>.wav ${testFile.absolutePath}"
                return@setOnClickListener
            }

            binding.tvTestResult.visibility = View.VISIBLE
            binding.tvTestResult.text = "Starte…"
            binding.btnTestStt.text = "Test stoppen"

            val t = TranscriptionManager(this).also { testTranscriber = it }
            t.onTranscription = { text, isFinal, speakerId ->
                runOnUiThread {
                    val tag = if (speakerId != null && speakerId != "Unknown") "[$speakerId] " else ""
                    binding.tvTestResult.text =
                        if (isFinal) "✓ $tag$text" else "… $tag$text"
                }
            }
            t.onError = { msg ->
                runOnUiThread {
                    binding.tvTestResult.text = "Fehler: $msg"
                    binding.btnTestStt.text = "STT Test (test.wav)"
                    testTranscriber = null
                }
            }
            val sampleRate = readWavSampleRate(testFile)
            if (sampleRate <= 0) {
                binding.tvTestResult.text = "Ungültiger WAV-Header in ${testFile.name}"
                binding.btnTestStt.text = "STT Test (test.wav)"
                testTranscriber = null
                return@setOnClickListener
            }
            // STT-only test: feed the same WAV as both channels so the user sees
            // the recognizer emit results twice (labeled Ich + Anrufer). The point of
            // this button is to validate the Azure endpoint, not the split pipeline.
            t.start(testFile.absolutePath, testFile.absolutePath, sampleRate)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testTranscriber?.stop()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * Sends an UNREGISTER, stops the foreground service, and exits the app.
     * The OS won't auto-restart the service after stopSelf(), so this is the
     * only way to "really log out".
     */
    private fun confirmAndLogout() {
        AlertDialog.Builder(this)
            .setTitle("Abmelden und Beenden")
            .setMessage("SIP-Anmeldung wird beendet und die App geschlossen. Eingehende Anrufe können dann nicht empfangen werden, bis Sie wieder auf Verbinden tippen.")
            .setPositiveButton("Beenden") { _, _ ->
                SipService.logoutAndStop(this)
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun readWavSampleRate(file: java.io.File): Int = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 28) return -1
            raf.seek(24)
            val b = ByteArray(4).also { raf.readFully(it) }
            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        }
    } catch (_: Exception) {
        -1
    }
}
