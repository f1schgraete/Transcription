# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore.properties with signing credentials)
./gradlew assembleRelease

# Install to connected device
./gradlew installDebug

# Release App Bundle (for Play Store)
./gradlew releaseAppBundle

# Format Kotlin code (also runs automatically pre-build)
./gradlew ktlintFormat

# Show which Linphone SDK version/source is being used
./gradlew linphoneSdkSource
```

Build outputs land in `app/build/outputs/apk/{debug,release}/` as `linphone-android-{buildType}-{version}.apk`.

There are no unit or instrumentation tests in this project.

## Architecture

**MVVM with Android Jetpack Navigation.** Data Binding is enabled throughout.

### Layer overview

| Layer | Location | Role |
|---|---|---|
| Application entry | `LinphoneApplication.kt` | App singleton, SDK init |
| Core orchestration | `core/CoreContext.kt` | Central hub — wraps the Linphone native SDK, manages accounts, calls, contacts, push, and all background services |
| Preferences | `core/CorePreferences.kt` | Typed wrapper over SharedPreferences |
| Background services | `core/Core*Service.kt` | Foreground services for calls, file transfer, push, keep-alive |
| UI | `ui/` | Fragment/ViewModel pairs per feature area |
| Utilities | `utils/` | Extension functions and helpers |

### Key architectural patterns

- **`CoreContext`** (`core/CoreContext.kt`, ~54 KB) is the most important file in the codebase. It owns the Linphone `Core` object and exposes coroutines/LiveData that the rest of the app listens to. Almost every feature traces back through here.
- ViewModels never touch the Linphone SDK directly — they go through `CoreContext`.
- `GenericActivity`, `GenericFragment`, `GenericViewModel` in `ui/` are the base classes all UI components extend; look here first when debugging lifecycle or theming issues.
- Navigation uses Safe Args; fragment destinations are defined in the navigation XML resources.

### Major UI areas

- `ui/assistant/` — account setup wizard
- `ui/call/` — in-call screen (fragments, viewmodels, conference, adapters)
- `ui/main/` — main app shell (conversations, contacts, history, settings)
- `ui/welcome/` — onboarding

### Linphone SDK dependency

The app depends on `org.linphone:linphone-sdk-android:5.5.+` (AAR). By default it is downloaded from `https://download.linphone.org/maven_repository`. To use a locally-built SDK, set the `LinphoneSdkBuildDir` Gradle property to the SDK output directory.

ABI filters are `armeabi-v7a` and `arm64-v8a`.

## Optional Features

- **Firebase/Crashlytics** — only activated when `google-services.json` is present. Without it, push notifications and crash reporting are gracefully disabled.
- **Release signing** — configured via a `keystore.properties` file (not committed).
- **Debug package name** — can be separated from release via `useDifferentPackageNameForDebugBuild` in `app/build.gradle.kts`.

## Version Management

Version is computed automatically from `git describe` (last tag + commit count + short hash) and written into `BuildConfig` and string resources. The fallback hardcoded version is `6.1.0-alpha`.

## Common Build Gotchas

- **AAPT "resource not found" errors** — run `git clean -f` then rebuild.
- **Missing `libc++_shared.so` crash at runtime** — do a clean rebuild; usually caused by mismatched CPU architecture in the SDK AAR.
- **Crashlytics symbol upload** — requires both debug native libraries and a valid `google-services.json`.
- **ktlint** runs automatically before compilation and will fail the build on style violations. Run `./gradlew ktlintFormat` to auto-fix before committing.