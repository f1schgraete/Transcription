package com.siptranscribe.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.siptranscribe.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS = "sip_prefs"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_DOMAIN = "domain"
        const val KEY_DISPLAY = "display_name"
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

        binding.btnDelete.setOnClickListener {
            val t = binding.etPhone.text
            if (t != null && t.isNotEmpty()) t.delete(t.length - 1, t.length)
        }
        binding.btnDelete.setOnLongClickListener {
            binding.etPhone.text?.clear()
            true
        }

        binding.btnRegister.setOnClickListener { saveAndRegister() }

        binding.btnCall.setOnClickListener {
            val number = binding.etPhone.text.toString().trim()
            if (number.isEmpty()) {
                toast("Bitte eine Nummer eingeben")
                return@setOnClickListener
            }
            if (!LinphoneManager.isRegistered) {
                toast("Bitte zuerst registrieren")
                return@setOnClickListener
            }
            makeCall(number)
        }

        // Keep registration status label updated
        LinphoneManager.onRegistrationStateChanged = { ok, msg ->
            runOnUiThread {
                binding.tvStatus.text = msg
                binding.tvStatus.setTextColor(
                    if (ok) getColor(R.color.status_ok) else getColor(R.color.status_error)
                )
            }
        }
    }

    private fun loadSettings() {
        binding.etUsername.setText(prefs.getString(KEY_USER, ""))
        binding.etPassword.setText(prefs.getString(KEY_PASS, ""))
        binding.etDomain.setText(prefs.getString(KEY_DOMAIN, "tel.t-online.de"))
        binding.etDisplayName.setText(prefs.getString(KEY_DISPLAY, ""))
    }

    private fun saveAndRegister() {
        val user = binding.etUsername.text.toString().trim()
        val pass = binding.etPassword.text.toString().trim()
        val domain = binding.etDomain.text.toString().trim()
        val display = binding.etDisplayName.text.toString().trim().ifEmpty { user }

        if (user.isEmpty() || pass.isEmpty() || domain.isEmpty()) {
            toast("Bitte alle Felder ausfuellen")
            return
        }

        prefs.edit().apply {
            putString(KEY_USER, user)
            putString(KEY_PASS, pass)
            putString(KEY_DOMAIN, domain)
            putString(KEY_DISPLAY, display)
            apply()
        }

        SipService.start(this)
        // Small delay to allow the service and Linphone core to initialise before registering
        binding.root.postDelayed({
            LinphoneManager.registerAccount(user, pass, domain, display)
        }, 800)

        binding.tvStatus.text = "Registrierung laeuft..."
        binding.tvStatus.setTextColor(getColor(R.color.status_neutral))
    }

    private fun makeCall(number: String) {
        val call = LinphoneManager.makeCall(number)
        if (call != null) {
            startActivity(Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_IS_INCOMING, false)
                putExtra(CallActivity.EXTRA_REMOTE_ADDRESS, number)
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
