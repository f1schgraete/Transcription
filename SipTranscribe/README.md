# SipTranscribe

An accessible Android SIP telephone app with live German transcription for deaf and hard-of-hearing users.

---

## Project overview

SipTranscribe connects to any standard SIP/VoIP provider (pre-configured for Deutsche Telekom) and displays real-time subtitles of the remote party's speech during a call. No cloud API key is required — transcription runs entirely through the built-in Android `SpeechRecognizer` API, which on modern devices can use an on-device German model.

| Feature | Detail |
|---|---|
| SIP stack | Linphone SDK 5.3.x (`org.linphone:linphone-sdk-android`) |
| Transcription | Android `SpeechRecognizer`, language `de-DE` |
| Transport | SIP over TLS (port 5061) |
| Audio codec | Linphone default (Opus preferred) |
| Media encryption | SRTP |
| Min Android | API 26 (Android 8.0 Oreo) |
| Target SDK | 34 (Android 14) |
| Language | Kotlin |

---

## Deutsche Telekom VoIP setup

1. Log in to your Telekom account at https://www.telekom.de and navigate to your VoIP / "Festnetz" settings.
2. Note your SIP username (usually your telephone number in the form `+4930...` or the local form `030...`) and your SIP password.
3. In the app, enter:
   - **Benutzername**: your SIP username (e.g. `030123456789`)
   - **Passwort**: your SIP/VoIP password (not your Telekom login password — look for "Verbindungsdaten" or "Zugangsdaten")
   - **SIP-Server**: `tel.t-online.de` (pre-filled)
   - **Anzeigename**: optional display name shown to the called party
4. Tap **Registrieren**. The status bar will show "Registriert" when the connection is successful.

### Network requirements

- The app uses **TLS transport** (`sip:tel.t-online.de` on port 5061). Ensure your firewall/router does not block outbound TCP 5061.
- For RTP media, standard UDP ports (typically 16384–32767) must be reachable. Most home routers handle this automatically via NAT traversal (STUN).
- Telekom may require that you are on a Telekom DSL line or have explicitly enabled third-party device SIP access in your account portal.

---

## How transcription works

The app uses a deliberate audio routing trick to transcribe the remote party without requiring a secondary microphone or audio loopback API:

```
Remote voice
    |
    v
Linphone SDK  -->  loudspeaker (AudioDevice.Type.Speaker)
                        |
                        v (acoustic path through air)
                   device microphone
                        |
                        v
              Android SpeechRecognizer (de-DE)
                        |
                        v
              tvLive / tvHistory (on screen)
```

1. When a call is connected, `LinphoneManager.setMicEnabled(false)` mutes the Linphone microphone input. This frees the hardware microphone for exclusive use by `SpeechRecognizer`.
2. `LinphoneManager.routeToSpeaker()` sets the Linphone audio output to the loudspeaker so the remote voice is played aloud.
3. `TranscriptionManager.start()` opens a continuous `SpeechRecognizer` session in German. Partial results appear in the blue live area at the bottom of the transcript card; finalised segments accumulate in the scrollable history above.
4. The **"Tippen zum Sprechen"** button temporarily re-enables the Linphone microphone (so the deaf user can speak) and pauses `SpeechRecognizer` to avoid capturing the user's own voice. A second tap resumes transcription.

### Limitations of this approach

- **Acoustic coupling** — the microphone captures room noise alongside the loudspeaker audio. In quiet environments this works well; in noisy environments accuracy drops.
- **Echo** — the user's own voice (when the mic is ON) will not be transcribed (SpeechRecognizer is paused), but any residual loudspeaker leakage during transcription mode may confuse the recogniser.
- **Google dependency** — `SpeechRecognizer` on most devices sends audio to Google's servers unless an on-device model is downloaded (Android 13+ via `PREFER_OFFLINE`). The app requests offline mode, but falls back to online if no model is installed.
- **Continuous recognition limit** — Android's `SpeechRecognizer` stops after a period of silence or after roughly 60 seconds. `TranscriptionManager` automatically restarts it with a 150 ms gap, but there will be a brief interruption between segments.

---

## Build instructions

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34 installed
- Gradle 8.2+ (managed by the Gradle wrapper)

### Steps

1. **Generate the Gradle wrapper** (this project ships without the `gradle/wrapper/` binaries to keep the repo clean):

   ```bash
   # From the project root
   gradle wrapper --gradle-version 8.6
   ```

   Or open the project in Android Studio — it will prompt you to generate the wrapper automatically.

2. **Sync dependencies**: Android Studio will download the Linphone SDK (~80 MB) from `https://linphone.org/maven_repository/` and the AndroidX libraries from Maven Central.

3. **Connect a device** running Android 8.0+ (API 26) with USB debugging enabled, or start an emulator.

4. **Run** the `app` configuration. Grant microphone and notification permissions when prompted.

### First-run checklist

- [ ] Grant **Mikrofon** permission (required for both SIP and SpeechRecognizer)
- [ ] Grant **Benachrichtigungen** permission (Android 13+) for the foreground service notification
- [ ] Enter SIP credentials and tap **Registrieren**
- [ ] Confirm status shows "Registriert" before placing a call
- [ ] Download the German on-device speech model: Settings > General > Voice Input > Offline speech recognition > Deutsch

---

## Project structure

```
SipTranscribe/
  app/src/main/
    java/com/siptranscribe/app/
      LinphoneManager.kt      — SIP core singleton (init, register, call control)
      TranscriptionManager.kt — Continuous SpeechRecognizer wrapper
      SipService.kt           — Foreground service that keeps Linphone alive
      MainActivity.kt         — SIP credentials UI + dialpad
      CallActivity.kt         — In-call UI with transcription display
    res/
      layout/
        activity_main.xml     — Login + dialpad screen
        activity_call.xml     — Call screen with subtitle card
      values/
        colors.xml            — High-contrast colour palette
        strings.xml
        themes.xml            — Material3 theme + DialButton style
      drawable/
        ic_phone.xml          — Notification icon (vector)
        ic_launcher_foreground.xml
      mipmap-anydpi-v26/
        ic_launcher.xml
        ic_launcher_round.xml
    AndroidManifest.xml
  app/build.gradle
  build.gradle
  settings.gradle
  gradle.properties
```

---

## Known limitations and future improvements

### Audio interception (higher accuracy)

The loudspeaker-to-microphone acoustic path introduces room noise. A cleaner approach is to use a **Linphone custom audio device** (`AudioDevice` with a custom `AudioDeviceCallback`) that routes the decoded PCM frames directly into a shared buffer, which `SpeechRecognizer` or a cloud STT client reads. This avoids the acoustic path entirely but requires a custom Linphone build or the internal Linphone `MixerApi`.

### Transcription accuracy

For higher accuracy consider replacing `SpeechRecognizer` with:
- **Google Cloud Speech-to-Text** (streaming gRPC, requires API key, billed per minute)
- **Whisper.cpp** compiled to an Android AAR (on-device, no internet, higher latency)
- **Azure Cognitive Services Speech SDK** (free tier available)

### UI / accessibility

- Add `contentDescription` attributes to all icon buttons for TalkBack support.
- Consider a larger-font mode controlled by a settings toggle.
- The transcript history could be exportable as a text file after the call ends.

### Incoming call handling

- For reliable incoming calls the SIP registration keep-alive interval (`expires = 3600`) should be reduced to 60–120 s, or Telekom push-via-SIP should be configured.
- A full `ConnectionService` / `TelecomManager` integration would allow the system call UI and Bluetooth headset buttons to work correctly.

### Security

- Credentials are stored in `SharedPreferences` without encryption. For production, use `EncryptedSharedPreferences` from the Jetpack Security library.
