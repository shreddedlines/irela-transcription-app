# Contributing

Irela is a personal project. This file explains how to set up the project and
what a change needs before it can be merged. No license has been specified (see
the README), so please open an issue to discuss a change before working on it.

## Setup

Follow **Setup** in the [README](README.md#setup): create the backend virtual
environment, fetch whisper.cpp with `scripts/fetch-whisper-cpp.sh`, and point
`android/local.properties` at your Android SDK.

## Before opening a pull request

Run both test suites and make sure they pass:

```bash
cd backend && python -m pytest -q
```

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

If you change UI behaviour that the JVM tests cannot reach, also run the
relevant instrumented tests on a device (`./gradlew :app:connectedDebugAndroidTest`)
and say in the pull request which ones you ran.

## Ground rules

These are enforced by tests or reviews, and changes that break them will not be
accepted:

- **No secrets in the repository or the APK.** Provider keys, the admin token,
  the owner code, billing keys, keystores and service-account files come from
  the environment or user-level Gradle properties only. The app knows a backend
  URL, never a provider key.
- **Never log or store transcript text** on the backend, and do not add
  transcript content to Android logs or crash reports.
- **The backend is the security boundary.** Limits checked in the app are a
  courtesy; every limit must also be enforced server-side.
- **Audio limits change in one place.** Edit `shared/limits.json` and keep the
  Android constants in step; the parity tests fail otherwise. Environment
  overrides may only tighten limits.
- **Never delete user data as a side effect.** Expired plans, refusals and
  failures limit new transcription only; recordings, transcripts and history
  remain.
- **The provider data-use opt-out (`mip_opt_out=true`) must stay forced** on
  every request.
- **Keep the application ID `com.whispercppdemo`.**
- Match the surrounding code style: Kotlin official style on Android, the
  existing module layout and comment density in the backend.

## Commit and pull request hygiene

- Keep pull requests focused on one change, with tests for new behaviour.
- Do not commit build outputs, `local.properties`, `.env` files, databases,
  audio files or model weights; `.gitignore` covers the common cases.
- Describe what you verified and how in the pull request.
