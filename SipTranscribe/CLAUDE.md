# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and run

This is an Android project using Gradle. All builds are done through Android Studio or the Gradle wrapper.

```bash
# Generate the Gradle wrapper (required if not present)
gradle wrapper --gradle-version 8.6

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run lint
./gradlew lint
```

There are no unit tests in this project. The `app` run configuration in Android Studio is the primary way to deploy.

**Prerequisites:** Android Studio Hedgehog+, Android SDK 34, a physical device running API 26+ (emulators lack SpeechRecognizer support).

## Architecture

The app is a foreground-service-based SIP phone with live transcription. The core design is:

**`SipService` (foreground service)** — entry point that keeps Linphone alive. Initialises `LinphoneManager` on `onCreate` and handles incoming call intents by launching `CallActivity`. Must be started before any SIP operations.

**`LinphoneManager` (singleton object)** — wraps the Linphone SDK `Core`. Owns registration, call control (`makeCall`, `acceptCall`, `hangUp`), and audio routing (`routeToSpeaker`/`routeToEarpiece`). Exposes three lambda callbacks for UI layers: `onIncomingCall`, `onCallStateChanged`, `onRegistrationStateChanged`. Both `SipService` and `CallActivity` set these callbacks — `CallActivity` overwrites the `onCallStateChanged` callback set by `SipService`, so only the currently active UI receives call events.

**`TranscriptionManager`** — wraps Android `SpeechRecognizer` in a continuous restart loop (150 ms gap between sessions). Transcribes German (`de-DE`). Runs entirely on the main thread via `Handler`.

**`MainActivity`** — SIP credential form + dialpad. Saves credentials to `SharedPreferences` (key names in companion object). Starts `SipService` then delays 800 ms before calling `LinphoneManager.registerAccount` to allow the core to initialise.

**`CallActivity`** — in-call UI. Manages the "speaker-to-mic" transcription pipeline: mutes Linphone's mic (`setMicEnabled(false)`), routes audio to loudspeaker, then starts `TranscriptionManager`. The **Sprechen** toggle button (`btnToggleMic`) inverts this: enables mic + pauses STT.

## The speaker-to-mic transcription trick

The central design choice: Linphone plays the remote party's voice through the device loudspeaker, and `SpeechRecognizer` uses the physical microphone to capture it acoustically. This avoids needing a custom Linphone audio device or PCM interception. The trade-off is sensitivity to room noise. `LinphoneManager.setMicEnabled(false)` frees the hardware mic from Linphone so it's exclusively available to `SpeechRecognizer`.

## Key dependencies

- `org.linphone:linphone-sdk-android:5.3.110` — SIP stack (fetched from `https://linphone.org/maven_repository/`)
- Android `SpeechRecognizer` — no external STT API; requires Google app or on-device model
- ViewBinding enabled; no Jetpack Compose, no ViewModel/LiveData

## SIP configuration defaults

- Server: `tel.t-online.de` (Deutsche Telekom), TLS on port 5061
- Media encryption: SRTP
- Registration expiry: 3600 s
- Credentials stored in `SharedPreferences` named `"sip_prefs"` (unencrypted)
