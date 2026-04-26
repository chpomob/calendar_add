# Calendar Add

`Calendar Add` is an Android app that turns rough text, photos, shared content, and short voice captures into calendar events using local LiteRT-LM models.

The app is local-first after model download: extraction runs on-device, created events are stored locally, and optional sync to the Android system calendar is available.

## Current State

This repo is active and buildable on `main`, but it is still best described as a solid beta rather than a polished finished product.

Implemented now:

- runtime download of LiteRT-LM models
- model selection in Settings
- text, image, audio, and Android share-intent import flows
- direct camera capture from the home screen
- press-and-hold microphone capture, limited to 30 seconds
- background analysis through `WorkManager` with foreground notifications
- multi-event extraction from a single input
- local Room persistence
- optional sync to the device calendar
- opt-in diagnostics mode that can surface raw model JSON on extraction failure

Important limitations:

- no fallback extraction path when no model is installed
- local inference can still be slow, especially on larger models
- some model/input combinations are more reliable than others
- event list and event detail remain basic: no search, edit, or delete UI
- success/failure handling still leans heavily on notifications rather than rich in-app progress

## Recommended Model State

The app currently exposes these models in [LiteRtModelCatalog.kt](app/src/main/java/com/calendaradd/service/LiteRtModelCatalog.kt):

| Model | Inputs | Notes |
|------|--------|-------|
| Gemma 4 E2B | Text, Image, Audio | Default model |
| Gemma 4 E4B | Text, Image, Audio | Larger Gemma 4 variant |
| Gemma 3n E2B | Text, Image, Audio | Strong multimodal candidate |
| Gemma 3n E4B | Text, Image, Audio | Larger Gemma 3n variant |
| Qwen 3.5 0.8B LiteRT | Text, Image | Experimental, CPU-only, no audio |

Practical recommendation:

- prefer the Gemma models for normal use
- treat Qwen as experimental in this app

Models are downloaded through Android `DownloadManager` into app-specific storage. When switching models, the app prunes older app-managed model files while preserving any model still required by queued background work.

## Real User Flows

The app currently supports these entry points:

- Paste or type event text directly on the home screen
- Take a photo with the camera
- Pick an image from files
- Record voice by pressing and holding the voice card
- Pick an audio file
- Share text, image, or audio into the app from another Android app

When possible, shared content is queued directly into background analysis without forcing the full home-screen flow first.

## Background Processing

Slow model runs are queued into a foreground `WorkManager` worker.

Current behavior:

- queued jobs survive normal app restarts more reliably because inputs are stored in app-private no-backup storage
- the app shows foreground progress notifications while analysis is running
- result notifications are separated from progress notifications
- repeated worker restarts are labeled as retries
- repeated background restarts are capped instead of replaying forever

This means background analysis is usable today, but it is still an area to watch closely on slower devices or with large multimodal jobs.

## Extraction Behavior

The extraction pipeline is stricter than earlier versions:

- multiple events can be returned and saved from one input
- fragmented model output for the same event is merged when compatible
- extracted events are only saved when a parseable absolute start date/time is present
- malformed dates are rejected instead of silently defaulting to the current time
- prompts now include an explicit local reference datetime and timezone so relative phrases like `tomorrow`, `tonight`, or `next Friday` are resolved more reliably

If diagnostics mode is enabled in Settings, failed background extractions can reopen the app with the raw model JSON for inspection.

## Requirements

- Android 8.0+ (`minSdk 26`)
- Android SDK configured in `local.properties`
- JDK 21
- enough free storage for the selected model

Build configuration in [app/build.gradle.kts](app/build.gradle.kts):

- `compileSdk = 35`
- `targetSdk = 35`
- `versionName = "1.0.0"`

## Build And Test

```bash
./gradlew test
./gradlew assembleDebug
./gradlew installDebug
```

Useful extra commands:

```bash
./gradlew bundleRelease
./scripts/test_build.sh
```

## Project Layout

```text
app/src/main/java/com/calendaradd/
  service/     LiteRT-LM integration, model download, background workers, calendar services
  ui/          Compose screens and view models
  usecase/     business logic, Room database, preferences
  navigation/  app navigation
  util/        image loading, recording, permissions, and helpers
app/src/test/java/
  JVM tests for services, worker helpers, and use cases
docs/
  release, privacy, model, and submission notes
```

## Development Notes

- The app no longer eagerly initializes LiteRT-LM on home-screen launch.
- Large local models can still feel slow even when they succeed.
- Android notifications matter for the background UX; if notifications are disabled, analysis may appear silent.
- App preferences are kept out of Android cloud backup rules.
- Release signing and Play submission scaffolding are present in the repo, but store assets and final publication work still need ongoing attention.

## Documentation

- [Model Integration](docs/MODEL_INTEGRATION.md)
- [Release Status](docs/RELEASE_STATUS.md)
- [Release Notes](docs/RELEASE.md)
- [Play Submission](docs/PLAY_SUBMISSION.md)
- [Store Listing Draft](docs/STORE_LISTING.md)
- [Privacy Policy Draft](docs/PRIVACY_POLICY.md)
