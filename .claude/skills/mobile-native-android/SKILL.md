---
name: mobile-native-android
description: Conventions for a Kotlin / Jetpack Compose Android app targeting Google Play. Use when .claude/HARNESS.toml selects mobile/native-android, or when building Android UI with Compose 1.9+, ViewModel + StateFlow, Hilt + KSP, a gradle --console=plain build loop, ML Kit GenAI on-device AI, foregroundServiceType discipline, and emulator screenshot verification.
---

# native-android rules

### Stack lockdown
- Kotlin 2.x (≥2.1 for Compose Compiler Gradle Plugin).
- Jetpack Compose 1.9+ as the primary UI; do not write new XML layouts.
- ViewModel + StateFlow + `collectAsStateWithLifecycle()`; no LiveData in new code.
- Hilt 2.56 with KSP. KAPT is banned in new modules.
- Target SDK 35 minimum (Android 15); raise to 36 (Android 16) by Aug 31, 2026.
- Android Studio Otter (2025.2.x) or newer.

### Build loop
- Use `./gradlew --console=plain` for agent-readable output; never the IDE button.
- For UI verification: boot emulator via `emulator -avd`, install with `adb install`, screenshot with `adb exec-out screencap -p`.
- Strong Skipping Mode is the default since Compose 1.7; keep state classes stable.

### Gemini Nano / on-device AI
- Use ML Kit GenAI APIs (Summarization, Proofreading, Rewriting, Image Description preview) for on-device LLM workloads.
- Device-gate carefully: Pixel 9 series / Galaxy S25 / Snapdragon / Tensor only. Ship a fallback path.

### Foreground services
- Every `<service>` with `android:foregroundServiceType` MUST also be declared in Play Console "App content"; missing the Console declaration breaks store review.
- Android 15: `dataSync` and `mediaProcessing` are time-bounded; `shortService` capped at 3 minutes.

### Never do
- Never paste a Play service account JSON or `FIREBASE_TOKEN` into a tool call.
- Never write KAPT-only annotation processors in new modules.
- Never claim a UI change works without an emulator screenshot.
