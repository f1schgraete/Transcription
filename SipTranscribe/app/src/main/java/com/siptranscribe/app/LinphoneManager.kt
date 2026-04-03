package com.siptranscribe.app

import android.content.Context
import android.util.Log
import org.linphone.core.*

object LinphoneManager {

    private const val TAG = "LinphoneManager"
    private var core: Core? = null
    private var coreListener: CoreListenerStub? = null

    // Callbacks for UI layers
    var onIncomingCall: ((Call) -> Unit)? = null
    var onCallStateChanged: ((Call, Call.State) -> Unit)? = null
    var onRegistrationStateChanged: ((Boolean, String) -> Unit)? = null

    var isRegistered = false
    private var currentDomain = ""

    fun init(context: Context) {
        if (core != null) return

        val factory = Factory.instance()
        factory.setDebugMode(BuildConfig.DEBUG, TAG)

        core = factory.createCore(null, null, context.applicationContext)

        coreListener = object : CoreListenerStub() {

            override fun onAccountRegistrationStateChanged(
                core: Core,
                account: Account,
                state: RegistrationState?,
                message: String
            ) {
                Log.d(TAG, "Registration: $state - $message")
                isRegistered = state == RegistrationState.Ok
                val statusMsg = when (state) {
                    RegistrationState.Ok -> "Registriert"
                    RegistrationState.Failed -> "Registrierung fehlgeschlagen: $message"
                    RegistrationState.Progress -> "Registrierung laeuft..."
                    RegistrationState.Cleared -> "Abgemeldet"
                    else -> message
                }
                onRegistrationStateChanged?.invoke(isRegistered, statusMsg)
            }

            override fun onCallStateChanged(
                core: Core,
                call: Call,
                state: Call.State?,
                message: String
            ) {
                Log.d(TAG, "Call state: $state - $message")
                val s = state ?: return
                onCallStateChanged?.invoke(call, s)
                if (s == Call.State.IncomingReceived) {
                    onIncomingCall?.invoke(call)
                }
            }
        }

        core!!.addListener(coreListener!!)
        core!!.start()
    }

    /**
     * Register a SIP account.
     * @param displayName Optional display name shown to called parties; defaults to username.
     */
    fun registerAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String = username
    ) {
        val c = core ?: return
        currentDomain = domain

        c.clearAccounts()
        c.clearAllAuthInfo()

        val factory = Factory.instance()

        // Auth credentials
        val authInfo = factory.createAuthInfo(username, null, password, null, null, domain)
        c.addAuthInfo(authInfo)

        // Identity address with optional display name
        val identity = factory.createAddress("sip:$username@$domain") ?: return
        identity.displayName = displayName.ifBlank { username }

        // Server address with TLS (Telekom supports TLS on port 5061)
        val serverAddr = factory.createAddress("sip:$domain") ?: return
        serverAddr.transport = TransportType.Tls

        val accountParams = c.createAccountParams()
        accountParams.identityAddress = identity
        accountParams.serverAddress = serverAddr
        accountParams.isRegisterEnabled = true
        accountParams.expires = 3600

        val account = c.createAccount(accountParams)
        c.addAccount(account)
        c.defaultAccount = account
    }

    /**
     * Dial a number. Accepts plain digits, +49..., or sip: URIs.
     * Automatically routes to the registered domain.
     */
    fun makeCall(number: String): Call? {
        val c = core ?: return null
        val clean = number.trim().replace(" ", "").replace("-", "")

        val sipUri = when {
            clean.startsWith("sip:") || clean.startsWith("sips:") -> clean
            clean.startsWith("+") -> "sip:$clean@$currentDomain"
            clean.startsWith("00") -> "sip:+${clean.substring(2)}@$currentDomain"
            else -> "sip:$clean@$currentDomain"
        }

        val remoteAddress = Factory.instance().createAddress(sipUri) ?: run {
            Log.e(TAG, "Could not parse address: $sipUri")
            return null
        }

        val params = c.createCallParams(null) ?: return null
        params.mediaEncryption = MediaEncryption.SRTP
        params.enableVideo(false)

        return c.inviteAddressWithParams(remoteAddress, params)
    }

    fun acceptCall(call: Call) {
        val c = core ?: return
        val params = c.createCallParams(call) ?: return
        params.enableVideo(false)
        call.acceptWithParams(params)
        routeToSpeaker()
    }

    fun hangUp() {
        core?.currentCall?.terminate()
    }

    fun declineCall(call: Call) {
        call.decline(Reason.Declined)
    }

    /**
     * Enable or disable the local microphone.
     * Set to false so that the system microphone is free for SpeechRecognizer
     * to capture the remote party's voice coming through the loudspeaker.
     */
    fun setMicEnabled(enabled: Boolean) {
        core?.enableMic(enabled)
    }

    fun routeToSpeaker() {
        val c = core ?: return
        c.audioDevices
            .firstOrNull { it.type == AudioDevice.Type.Speaker }
            ?.let { c.outputAudioDevice = it }
    }

    fun routeToEarpiece() {
        val c = core ?: return
        c.audioDevices
            .firstOrNull { it.type == AudioDevice.Type.Earpiece }
            ?.let { c.outputAudioDevice = it }
    }

    fun getCurrentCall(): Call? = core?.currentCall

    fun getCore(): Core? = core

    fun destroy() {
        coreListener?.let { core?.removeListener(it) }
        core?.stop()
        core = null
    }
}
