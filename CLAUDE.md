# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

Requires JDK 21 (LiteRT-LM 0.10.0+ hard requirement) and `sdk.dir` set in `local.properties`.

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Run JVM unit tests
./gradlew lint                   # Run Android lint
./gradlew build                  # Full CI build (compile + test + lint)
./gradlew connectedAndroidTest   # Instrumentation tests (requires device/emulator)
./scripts/test_build.sh          # Quick smoke check: build + unit tests
```

To run a single test class: `./gradlew test --tests "com.calendaradd.SomeTest"`

## Version Bump Checklist

When bumping `versionName` in `app/build.gradle.kts`, update these files too:

1. **README.md** — the APK download URL (line containing `releases/download/v.../app-release.apk`) and the `versionName` in the "Current Android configuration" code block
2. **docs/RELEASE.md** — `versionCode` and `versionName` in the "Current Release Build Status" section

Verify with:
```bash
scripts/check_release_docs.sh
```

The CI runs `check_release_docs.sh` as its first step. If the README URL doesn't match `versionName`, the build fails immediately.

## Architecture

Clean Architecture with three layers, all in the single module `app/src/main/java/com/calendaradd/`:

**UI** (`ui/`) → **Use Cases** (`usecase/`) → **Services** (`service/`)

**Data flow for event extraction:**
1. User provides text/image/audio via `HomeViewModel` or `ShareImportActivity`
2. `CalendarUseCase` routes to `TextAnalysisService`
3. `GemmaLlmService` runs on-device inference via LiteRT-LM
4. `TextAnalysisService` parses the JSON response (strips markdown, merges duplicates)
5. Events are persisted to Room (`EventDatabase`) and optionally synced to the Android system calendar via `SystemCalendarService`

**Background work:** `BackgroundAnalysisWorker` (WorkManager `CoroutineWorker`) runs heavy multimodal jobs as foreground services with notifications, max 2 retries and 10-minute timeout.

## LiteRT-LM Integration Notes

`GemmaLlmService` is the central engine. Key design decisions:
- **Backend ordering matters**: Gemma 4 E2B uses `[GPU, CPU]` (GPU first). Gemma 4 E4B uses `[CPU, GPU]` to avoid an OpenCL runtime error that appears after long conversations on some Pixel devices.
- **Single active session**: LiteRT-LM only supports one active session at a time. The service creates a new conversation per request and stays stateless.
- **Memory guards**: Validates device RAM before initialization (8 GB min for E2B, 12 GB for E4B). Logs a warning if OpenCL is unavailable but doesn't abort.
- **Multimodal input**: Combine `Content.Text`, `Content.ImageBytes` (PNG), and `Content.AudioBytes` (WAV, 16 kHz mono PCM) in a single `Content.builder()` call.
- **Model cache**: Compiled LiteRT-LM artifacts are stored in app-specific storage; cache directory must be passed during engine initialization.

Models are downloaded via `ModelDownloadManager` (Android `DownloadManager`) and defined in `LiteRtModelCatalog`. Old models are pruned when the user switches, unless a background job holds a reference.

## Prompting and JSON Parsing

`TextAnalysisService` has two analysis modes:
- **Classic** (single-pass): direct input → JSON
- **Heavy** (3-stage): observation → temporal resolution → final composition (slower, more thorough for images/audio)

The model returns `{"events": [{title, description, startTime, endTime, location, attendees}]}`. `TextAnalysisService` strips markdown code fences, extracts the JSON payload, and handles malformed responses. Times are ISO-8601 in prompts and stored as milliseconds (`Long`) in Room.

## Testing

Tests live in `app/src/test/`. Naming: `*Test.kt` for unit tests, `*IntegrationTest.kt` for multi-class flows.

Test fixtures for regression testing:
- `app/src/test/resources/audio-fixtures/` — 29 audio samples with transcripts and expected event JSON
- `app/src/test/resources/image-fixtures/` — 30 flyer images with expected extractions
- `app/src/test/resources/web-lookup-fixtures/` — web verification test data

The `AudioFixtureSuiteTest` and image fixture suite are the primary regression gates for extraction quality.

## Coding Conventions

Follow `.editorconfig`: 4-space indent for `.kt`, LF line endings. Class names `PascalCase`, functions and properties `camelCase`, packages `lowercase`.

Match existing layer boundaries: Compose UI in `ui/`, business logic in `usecase/`, platform integrations in `service/`, shared helpers in `util/`.

Commits use Conventional Commits: `feat:`, `fix:`, `refactor:` — imperative, scoped to one change.
