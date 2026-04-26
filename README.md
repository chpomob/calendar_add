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
- optional heavy analysis mode for images and audio, using extra refinement rounds
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

For difficult images or audio, Settings now expose a heavy analysis mode. It keeps the expensive multimodal pass to one round, then runs extra text-only refinement passes for temporal resolution and final event composition.

## LiteRT-LM Runtime Notes

The current Gemma path was cross-checked against Google AI Edge Gallery. The app now sends multimodal requests in the same content order used there: image first, audio second, text last.

Runtime choices:

- Gemma 4 follows Gallery's `gpu,cpu` main backend order and uses GPU vision.
- Gemma 3n follows Gallery's `cpu,gpu` main backend order and uses GPU vision.
- Gemma audio uses CPU backend, matching Gallery's direct LiteRT-LM path.
- Image, audio, and text jobs initialize only the matching LiteRT-LM modality backends, matching Gallery's task-specific setup.
- Gemma 4 E4B image/audio jobs on devices below 16 GB RAM use CPU for the text backend and GPU for vision. This avoids a native GPU-main initialization kill observed on a 12 GB Pixel 8 Pro.
- Gemma models keep Gallery's minimum device RAM guards before initialization.
- Gemma downloads are pinned to Gallery's exact Hugging Face commits and exact file sizes.
- Conversations use Gallery's sampler settings: topK 64, topP 0.95, temperature 1.0.
- Gemma token windows match Gallery: 4000 for Gemma 4 and 4096 for Gemma 3n. Image prompts need the larger window because the image context is part of LiteRT-LM prefill.
- Images are passed as PNG `ImageBytes` instead of temporary JPEG files.
- In-app voice capture records 16 kHz mono PCM and sends WAV bytes to the model.
- LiteRT-LM inference uses async callbacks so timeout/cancellation can call `cancelProcess()`.

The local Gallery reference clone lives under `external/google-ai-edge-gallery/`. The `external/` directory is intentionally ignored by Git.

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

## GitHub Builds

The repository now exposes builds in two ways:

- every successful push to `main` updates a rolling prerelease named `latest-main` with the current debug APK
- alpha tags such as `v0.1.0-alpha.2` create draft prereleases with:
  - debug APK
  - unsigned release AAB

So testers do not have to rely only on the `Actions` artifact view anymore.

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
