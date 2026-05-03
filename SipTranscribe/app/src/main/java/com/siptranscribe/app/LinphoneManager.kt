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
        // setDebugMode is deprecated and no longer pipes to logcat on its own —
        // enable logcat output explicitly and crank to Debug so belle-sip / TLS
        // errors surface (default is Message which only logs state changes).
        factory.setLoggerDomain(TAG)
        if (BuildConfig.DEBUG) {
            factory.enableLogcatLogs(true)
            factory.loggingService.setLogLevel(LogLevel.Debug)
        }

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
    fun makeCall(number: String, recordFilePath: String = ""): Call? {
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
        if (recordFilePath.isNotBlank()) params.recordFile = recordFilePath

        return c.inviteAddressWithParams(remoteAddress, params)
    }

    fun acceptCall(call: Call, recordFilePath: String = "") {
        val c = core ?: return
        val params = c.createCallParams(call) ?: return
        params.isVideoEnabled = false
        if (recordFilePath.isNotBlank()) params.recordFile = recordFilePath
        call.acceptWithParams(params)
    }

    fun hangUp() {
        core?.currentCall?.terminate()
    }

    fun declineCall(call: Call) {
        call.decline(Reason.Declined)
    }

    /** Enable or disable the local microphone. */
    fun setMicEnabled(enabled: Boolean) {
        Log.d(TAG, "setMicEnabled($enabled)")
        core?.isMicEnabled = enabled
    }

    fun routeToSpeaker() {
        val c = core ?: run {
            Log.w(TAG, "routeToSpeaker: core is null")
            return
        }
        val speaker = c.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker }
        if (speaker != null) {
            Log.i(TAG, "routeToSpeaker: switching output to ${speaker.deviceName}")
            c.outputAudioDevice = speaker
        } else {
            Log.w(TAG, "routeToSpeaker: no Speaker device found among: " +
                c.audioDevices.joinToString { "${it.deviceName}(${it.type})" })
        }
    }

    fun routeToEarpiece() {
        val c = core ?: return
        c.audioDevices
            .firstOrNull { it.type == AudioDevice.Type.Earpiece }
            ?.let { c.outputAudioDevice = it }
    }

    /**
     * Starts recording the active call. The record file path must already be set
     * in the call params (via [makeCall] or [acceptCall]). Call this from StreamsRunning.
     */
    fun startCallRecording() {
        val call = core?.currentCall ?: run { Log.w(TAG, "startCallRecording: no active call"); return }
        call.startRecording()
        Log.i(TAG, "startCallRecording: recording started")
    }

    fun stopCallRecording() {
        core?.currentCall?.stopRecording()
        Log.i(TAG, "stopCallRecording")
    }

    /**
     * Sample rate of the negotiated audio codec on the active call (e.g. 8000 for G.711,
     * 16000 for G.722). This is the rate Linphone writes into the recorded WAV file —
     * but the WAV header isn't finalised until [stopCallRecording], so callers that need
     * the rate during the call must read it from here.
     *
     * Falls back to 8000 Hz when no call is active or the codec is not yet negotiated.
     */
    fun getCurrentCallSampleRate(): Int =
        core?.currentCall?.currentParams?.usedAudioPayloadType?.clockRate ?: 8000

    fun getCurrentCall(): Call? = core?.currentCall

    fun getCore(): Core? = core

    fun destroy() {
        coreListener?.let { core?.removeListener(it) }
        core?.stop()
        core = null
    }
}
