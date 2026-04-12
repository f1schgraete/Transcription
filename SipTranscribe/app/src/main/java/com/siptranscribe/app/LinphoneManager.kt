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
    private var callMediaEncryption: MediaEncryption = MediaEncryption.SRTP

    fun init(context: Context) {
        if (core != null) return

        val factory = Factory.instance()
        factory.setDebugMode(BuildConfig.DEBUG, TAG)

        core = factory.createCore(null, null, context.applicationContext)
        // Deutsche Telekom's SIP certificate may not be in Linphone's bundled CA store;
        // disabling verification avoids the TLS io-error on registration.
        core!!.verifyServerCertificates(false)

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
     *
     * @param displayName  Optional display name shown to called parties; defaults to username.
     * @param port         SIP server port (5061 for TLS, 5060 for UDP/TCP).
     * @param transport    Transport protocol (UDP / TCP / TLS).
     * @param expires      Registration expiry in seconds.
     * @param authUserId   Auth user ID when it differs from the SIP username; null = same as username.
     * @param realm        Auth realm; null = match any realm.
     * @param outboundProxy Outbound proxy URI (e.g. "sip:proxy.example.com"); null = use domain directly.
     * @param mediaEncryption Media encryption mode used for outgoing calls.
     */
    fun registerAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String = username,
        port: Int = 5061,
        transport: TransportType = TransportType.Tls,
        expires: Int = 3600,
        authUserId: String? = null,
        realm: String? = null,
        outboundProxy: String? = null,
        mediaEncryption: MediaEncryption = MediaEncryption.SRTP
    ) {
        val c = core ?: return
        currentDomain = domain
        callMediaEncryption = mediaEncryption

        c.clearAccounts()
        c.clearAllAuthInfo()

        val factory = Factory.instance()

        // Auth credentials (authUserId and realm may be null to use defaults)
        val authInfo = factory.createAuthInfo(username, authUserId, password, null, realm, domain)
        c.addAuthInfo(authInfo)

        // Identity address with optional display name
        val identity = factory.createAddress("sip:$username@$domain") ?: return
        identity.displayName = displayName.ifBlank { username }

        // Server / outbound-proxy address — embed port in URI to avoid SDK version differences
        val serverHost = if (!outboundProxy.isNullOrBlank()) outboundProxy else domain
        val hostOnly = serverHost.removePrefix("sips:").removePrefix("sip:")
        // Append port only when the host string doesn't already include one
        val hostWithPort = if (port > 0 && !hostOnly.contains(':')) "$hostOnly:$port" else hostOnly
        val serverAddr = factory.createAddress("sip:$hostWithPort") ?: return
        serverAddr.transport = transport

        val accountParams = c.createAccountParams()
        accountParams.identityAddress = identity
        accountParams.serverAddress = serverAddr
        accountParams.isRegisterEnabled = true
        accountParams.expires = expires

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
        params.mediaEncryption = callMediaEncryption
        params.isVideoEnabled = false

        return c.inviteAddressWithParams(remoteAddress, params)
    }

    fun acceptCall(call: Call) {
        val c = core ?: return
        val params = c.createCallParams(call) ?: return
        params.isVideoEnabled = false
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
        core?.isMicEnabled = enabled
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
