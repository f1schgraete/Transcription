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
        const val KEY_OWNER_NAMES = "owner_names"
        const val KEY_SUMMARY_PROMPT = "summary_prompt"
        const val DEFAULT_OWNER_NAMES =
            "Waltraud, Walde, Babu, Dr. Hirsch, Waltraud Hirsch"
        private const val REQ_PERMS = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        loadSettings()
        requestRequiredPermissions()

        // Dialpad digit buttons
        val digitButtons = mapOf(
            binding.btn0 to "0", binding.btn1 to "1", binding.btn2 to "2",
            binding.btn3 to "3", binding.btn4 to "4", binding.btn5 to "5",
            binding.btn6 to "6", binding.btn7 to "7", binding.btn8 to "8",
            binding.btn9 to "9", binding.btnStar to "*", binding.btnHash to "#"
        )
        digitButtons.forEach { (btn, digit) ->
            btn.setOnClickListener { binding.etPhone.append(digit) }
        }

        binding.btnPlus.setOnClickListener { binding.etPhone.append("+") }

        binding.btnDelete.setOnClickListener {
            val t = binding.etPhone.text
            if (t != null && t.isNotEmpty()) t.delete(t.length - 1, t.length)
        }
        binding.btnDelete.setOnLongClickListener {
            binding.etPhone.text?.clear()
            true
        }
        // Long-press 0 → + (standard phone convention)
        binding.btn0.setOnLongClickListener {
            binding.etPhone.append("+")
            true
        }

        // Advanced settings toggle
        binding.btnAdvanced.setOnClickListener {
            val visible = binding.layoutAdvanced.visibility == View.VISIBLE
            binding.layoutAdvanced.visibility = if (visible) View.GONE else View.VISIBLE
            binding.btnAdvanced.text =
                if (visible) "Erweiterte Einstellungen \u25B8" else "Erweiterte Einstellungen \u25BE"
        }

        // Transport dropdown
        val transportAdapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line,
            resources.getStringArray(R.array.transport_types)
        )
        binding.actvTransport.setAdapter(transportAdapter)
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

        binding.btnRegister.setOnClickListener { saveAndRegister() }
        binding.btnLogout.setOnClickListener { confirmAndLogout() }
        binding.btnResetPrompt.setOnClickListener {
            binding.etSummaryPrompt.setText(ConversationAnalyzer.DEFAULT_SYSTEM_PROMPT)
        }
        setupSttTest()

        binding.btnCall.setOnClickListener {
            val number = binding.etPhone.text.toString().trim()
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
            }
        }

        // Apply initial state
        if (LinphoneManager.isRegistered) {
            binding.tvStatus.text = "Registriert"
            binding.tvStatus.setTextColor(getColor(R.color.status_ok))
        }
        updateRegistrationUI(LinphoneManager.isRegistered)
    }

    override fun onResume() {
        super.onResume()
        // Refresh call history whenever returning to this screen (e.g. after a call ends)
        if (LinphoneManager.isRegistered) {
            refreshCallHistory()
        }
    }

    private fun updateRegistrationUI(registered: Boolean) {
        if (registered) {
            binding.cardSettings.visibility = View.GONE
            binding.cardRecentCalls.visibility = View.VISIBLE
            refreshCallHistory()
        } else {
            binding.cardSettings.visibility = View.VISIBLE
            binding.cardRecentCalls.visibility = View.GONE
        }
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
            // Ripple background
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            // Tap to fill dial field with caller number
            setOnClickListener { binding.etPhone.setText(record.callerNumber) }
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
            text = record.callerName
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun loadSettings() {
        binding.etUsername.setText(prefs.getString(KEY_USER, ""))
        binding.etPassword.setText(prefs.getString(KEY_PASS, ""))
        binding.etDomain.setText(prefs.getString(KEY_DOMAIN, "tel.t-online.de"))
        binding.etDisplayName.setText(prefs.getString(KEY_DISPLAY, ""))
        binding.etPort.setText(prefs.getInt(KEY_PORT, 5061).toString())
        binding.actvTransport.setText(prefs.getString(KEY_TRANSPORT, "TLS"), false)
        binding.etExpires.setText(prefs.getInt(KEY_EXPIRES, 3600).toString())
        binding.etAuthUser.setText(prefs.getString(KEY_AUTH_USER, ""))
        binding.etRealm.setText(prefs.getString(KEY_REALM, ""))
        binding.etOutboundProxy.setText(prefs.getString(KEY_OUTBOUND_PROXY, ""))
        binding.actvMediaEnc.setText(prefs.getString(KEY_MEDIA_ENC, "SRTP"), false)
        binding.etAzureEndpoint.setText(prefs.getString(KEY_AZURE_ENDPOINT, ""))
        binding.etAzureKey.setText(prefs.getString(KEY_AZURE_KEY, ""))
        binding.etAzureDeployment.setText(prefs.getString(KEY_AZURE_DEPLOYMENT, ""))
        binding.etOwnerNames.setText(prefs.getString(KEY_OWNER_NAMES, DEFAULT_OWNER_NAMES))
        binding.etSummaryPrompt.setText(
            prefs.getString(KEY_SUMMARY_PROMPT, ConversationAnalyzer.DEFAULT_SYSTEM_PROMPT)
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
            putString(KEY_AZURE_ENDPOINT, binding.etAzureEndpoint.text.toString().trim())
            putString(KEY_AZURE_KEY, binding.etAzureKey.text.toString().trim())
            putString(KEY_AZURE_DEPLOYMENT, binding.etAzureDeployment.text.toString().trim())
            putString(KEY_OWNER_NAMES, binding.etOwnerNames.text.toString().trim())
            putString(KEY_SUMMARY_PROMPT, binding.etSummaryPrompt.text.toString())
            apply()
        }

        SipService.start(this)
        binding.root.postDelayed({
            LinphoneManager.registerAccount(
                user, pass, domain, display,
                port, transport, expires,
                authUser, realm, outboundProxy,
                mediaEncryption
            )
        }, 800)

        binding.tvStatus.text = "Verbindung wird hergestellt..."
        binding.tvStatus.setTextColor(getColor(R.color.status_neutral))
    }

    private fun makeCall(number: String) {
        val recordFilePath = "${filesDir.absolutePath}/call_${System.currentTimeMillis()}.wav"
        val call = LinphoneManager.makeCall(number, recordFilePath)
        if (call != null) {
            startActivity(Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_IS_INCOMING, false)
                putExtra(CallActivity.EXTRA_REMOTE_ADDRESS, number)
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
            Manifest.permission.READ_PHONE_STATE
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
            t.start(testFile.absolutePath, sampleRate)
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
