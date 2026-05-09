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

**Prerequisites:** Android Studio Hedgehog+, Android SDK 34, a physical device running API 26+. Live transcription requires reachable Azure Speech and Azure OpenAI endpoints (configured in the main screen and saved to `SharedPreferences`).

## Architecture

The app is a foreground-service-based SIP phone with live cloud transcription and post-call LLM analysis.

**`SipService` (foreground service)** — entry point that keeps Linphone alive. Initialises `LinphoneManager` on `onCreate` and handles incoming call intents by launching `CallActivity`. Must be started before any SIP operations.

**`LinphoneManager` (singleton object)** — wraps the Linphone SDK `Core`. Owns registration, call control (`makeCall`, `acceptCall`, `hangUp`), audio routing (`routeToSpeaker`/`routeToEarpiece`), and call recording (`startCallRecording`/`stopCallRecording`). Exposes three lambda callbacks for UI layers: `onIncomingCall`, `onCallStateChanged`, `onRegistrationStateChanged`. Both `SipService` and `CallActivity` set these callbacks — `CallActivity` overwrites the `onCallStateChanged` callback set by `SipService`, so only the currently active UI receives call events.

**`CallAudioRecorder`** — tails a WAV file Linphone writes during a call. Linphone reserves the first 44 bytes for the WAV header but only fills it in when `Call.stopRecording` runs, so the sample rate must be passed in explicitly (read from the negotiated codec via `LinphoneManager.getCurrentCallSampleRate`). Emits 16-bit signed little-endian mono PCM chunks via `onPcmData`. In split-recording mode, two instances run in parallel — one per direction.

**`SttEngine` / `AzureSttEngine`** — `SttEngine` is a thin interface (`prepare(sampleRate)`, `feed(pcm, length)`, `stop()`) over any push-PCM speech-to-text backend. `AzureSttEngine` implements it against Azure's `ConversationTranscriber` (Speech SDK), pushing PCM into a `PushAudioInputStream`. With split recording the transcriber's diarization is moot (channel == speaker), so `TranscriptionManager` ignores the SDK's speakerId and tags each engine's results with a fixed label. Endpoint parsing handles the legacy `<region>.stt.speech.microsoft.com`, the unified `<resource>.cognitiveservices.azure.com` / `.services.ai.azure.com` Foundry hosts, and arbitrary custom WSS URLs. Language is hardcoded to `de-DE`.

**`TranscriptionManager`** — runs two `CallAudioRecorder` + `AzureSttEngine` pairs in parallel, one per channel. The uplink pair tags results `"Ich"`, the downlink pair tags `"Anrufer"` (constants `LABEL_LOCAL` / `LABEL_REMOTE`). Exposes `onTranscription(text, isFinal, speakerLabel)` and `onError` on the main thread.

**`ConversationAnalyzer`** — fires post-call against the finalised transcript using Azure OpenAI chat completions. Parses a structured response (caller name, topic, important points, dates, todos). Skipped silently when the chat deployment isn't configured or the transcript is too short.

**`MainActivity`** — SIP credential form + dialpad + Azure Speech / Azure OpenAI / summary-prompt settings + call history list. Saves everything to `SharedPreferences` (key names in the companion object). Starts `SipService` then delays 800 ms before calling `LinphoneManager.registerAccount` to allow the core to initialise.

**`CallActivity`** — in-call UI. On `Call.State.StreamsRunning` it calls `LinphoneManager.startCallRecording()` and starts `TranscriptionManager` against the recording WAV. Each finalised speaker turn is rendered as a coloured paragraph (background colour mapped from Azure's `speakerId`). On call end, fires `ConversationAnalyzer` over the labeled transcript and appends the summary inline (phone) or in a dedicated card (tablet split-pane layout).

**`CallHistory` / `CallRecord`** — JSON-persisted list of past calls (direction, name/number, start time, duration, answered flag) shown on the main screen.

## Audio capture path (split recording)

The audio that drives transcription comes from **Linphone's built-in call recording**, with a per-direction split that requires our **forked linphone-sdk**:

1. `CallActivity` builds a `recordFile` path and passes it through `makeCall`/`acceptCall` so it lands in `CallParams.recordFile` before media negotiation.
2. Before `Call.startRecording()`, `LinphoneManager.enableSplitRecording(true)` sets `[sound] split_record=1` in the Linphone Core config.
3. Our patched `MS2AudioStream::setRecordPath` (in `liblinphone/src/conference/session/audio-stream.cpp`) reads that flag and derives `<base>.ul.wav` (uplink/local mic, post-AEC) and `<base>.dl.wav` (downlink/remote, post-decoder) from the configured path. It then calls our new `audio_stream_set_split_record_files()` instead of the existing mixed-mode setter.
4. Our patched `setup_split_recorder()` in `mediastreamer2/src/voip/audiostream.c` allocates two `MS_FILE_REC` filters and links them directly to the existing `recv_tee` (downlink tap, output 1) and `outbound_mixer` (uplink tap, output 1). No `recorder_mixer` is created in this mode — there is nothing to mix.
5. `TranscriptionManager` tails both files via two `CallAudioRecorder` instances, feeds each PCM stream to its own `AzureSttEngine`, and emits each result with the channel's fixed label (`"Ich"` / `"Anrufer"`). Speaker separation no longer depends on Azure diarization.

There is no "speaker-to-mic" acoustic capture step and no on-device STT. The UI has no "Sprechen" toggle — the call runs full-duplex like a normal phone call, and the mic stays enabled throughout.

## Building the local linphone-sdk AAR

The split-recording feature requires patches to mediastreamer2 + liblinphone, so the app depends on a **locally built** AAR (not the Maven release). The patches and build live outside this repo.

**One-time setup (in WSL2 Ubuntu):**

```bash
# Source tree (already cloned at ~/linphone-sdk on the feature/Audio-transcription branch — used as base because gitlab.linphone.org was unreachable; any 5.x base works since the patch is API-stable).
cd ~/linphone-sdk

# Toolchain prerequisites (NDK r26d already at ~/android-ndk-r26d, JDK 17 at ~/jdk17 — Gradle Android Plugin requires Java 17, so don't rely on the system openjdk-11).
export ANDROID_NDK_HOME=~/android-ndk-r26d
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export ANDROID_HOME=/mnt/c/Users/<you>/AppData/Local/Android/Sdk   # Windows-side SDK is fine
export JAVA_HOME=~/jdk17
export PATH=$JAVA_HOME/bin:$PATH

# Configure for arm64 only (skips armv7 to halve build time on a single-arch app).
cmake --preset android-sdk -B build-android-sdk \
    -DENABLE_AUDIO_TRANSCRIPTION=OFF \
    -DLINPHONESDK_ANDROID_ARCHS=arm64

# Build (~30–45 min on first run).
make -C build-android-sdk sdk
```

The output AAR lands at `build-android-sdk/linphone-sdk/bin/outputs/aar/linphone-sdk-android-debug.aar`. Copy it into `app/libs/` and reference it from `app/build.gradle`.

**The patches** (kept in the linphone-sdk source tree, not vendored here):
- `mediastreamer2/include/mediastreamer2/mediastream.h` — adds `AUDIO_STREAM_FEATURE_SPLIT_RECORDING`, two `MSFilter*` and two `char*` fields on `AudioStream`, three public function declarations.
- `mediastreamer2/src/voip/audiostream.c` — adds `setup_split_recorder()`, `audio_stream_set_split_record_files()`, `audio_stream_split_record_start()`, `audio_stream_split_record_stop()`, plus the linking/unlinking/free-path branches. Reuses the existing `recv_tee` + `outbound_mixer` taps the mixed-recording feature already creates.
- `mediastreamer2/src/android/android_mediacodec.h` — adds `#pragma once` (pre-existing branch bug — header was unguarded and got included twice in `msvoip.c`).
- `liblinphone/src/conference/session/ms2-streams.h` — adds `bool mSplitRecord = false` member.
- `liblinphone/src/conference/session/audio-stream.cpp` — `setRecordPath` / `startRecording` / `stopRecording` read `[sound] split_record` and dispatch to the split path; `derive_split_paths()` mirrors the Kotlin path-derivation in `CallActivity.splitPaths`.

When upgrading the linphone-sdk you'll need to rebase these patches.

## Key dependencies

- **Locally-built `linphone-sdk-android-debug.aar`** in `app/libs/` (Maven coordinate `org.linphone:linphone-sdk-android:5.3.110` is no longer used).
- `com.microsoft.cognitiveservices.speech:client-sdk` — Azure Speech SDK for `ConversationTranscriber`
- Azure OpenAI chat completions (REST) — for post-call summary
- ViewBinding enabled; no Jetpack Compose, no ViewModel/LiveData

## SIP configuration defaults

- Server: `tel.t-online.de` (Deutsche Telekom), TLS on port 5061
- Media encryption: SRTP
- Registration expiry: 3600 s
- Credentials stored in `SharedPreferences` named `"sip_prefs"` (unencrypted)
