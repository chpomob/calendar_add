# Calendar Add

[![Android CI](https://github.com/chpomob/calendar_add/actions/workflows/android.yml/badge.svg)](https://github.com/chpomob/calendar_add/actions/workflows/android.yml)

`Calendar Add` is an Android app that turns event information from text, flyers, shared images, and short voice captures into calendar events.

The app is local-first after model download: AI extraction runs on-device with LiteRT-LM models, events are stored locally, and syncing to the Android system calendar is optional.

## Install The Latest APK

For non-technical Android testers, use the signed APK from GitHub Releases:

https://github.com/chpomob/calendar_add/releases/download/v0.2.0-alpha.1/calendar-add-v0.2.0-alpha.1-signed.apk

Install steps:

1. Open the link on an Android phone.
2. Download the `.apk`.
3. Tap the downloaded file.
4. If Android asks, allow the browser to install unknown apps.
5. Open `Calendar Add`.

More details are available in [Install From GitHub](docs/GITHUB_INSTALL.md).

## What Works Today

- Create calendar events from typed or pasted text.
- Take a photo of a flyer directly from the app.
- Import images and audio files from Android file pickers.
- Record short voice notes with press-and-hold capture.
- Share text, images, or audio into the app from another Android app.
- Extract multiple events from one input when the model returns several events.
- Run slow analysis jobs in the background with foreground notifications.
- Store the original source image or audio file with created events for later review.
- Optionally sync extracted events to the Android system calendar.
- Enable heavy analysis mode for harder image and audio inputs.
- Enable experimental web verification to refine public event details when online lookup succeeds.

## Project Status

This project is active and buildable on `main`, but it is still an alpha/beta-quality app rather than a finished consumer release.

Known limitations:

- A local model must be downloaded before extraction works.
- Local inference can be slow on large multimodal models.
- Image and audio accuracy depends strongly on the selected model and input quality.
- The event list and event detail screens are still basic.
- Progress and failure reporting still relies heavily on Android notifications.
- Web verification is experimental and can be limited by search provider restrictions.

## Recommended Models

The app exposes these LiteRT-LM models in [LiteRtModelCatalog.kt](app/src/main/java/com/calendaradd/service/LiteRtModelCatalog.kt):

| Model | Inputs | Notes |
|------|--------|-------|
| Gemma 4 E2B | Text, Image, Audio | Default model |
| Gemma 4 E4B | Text, Image, Audio | Larger Gemma 4 variant |
| Gemma 3n E2B | Text, Image, Audio | Strong multimodal candidate |
| Gemma 3n E4B | Text, Image, Audio | Larger Gemma 3n variant |
| Qwen 3.5 0.8B LiteRT | Text, Image | Experimental, CPU-only, no audio |

Practical recommendation:

- Use Gemma models for normal testing.
- Treat Qwen as experimental in this app.
- Prefer smaller Gemma variants on memory-constrained devices.

Models are downloaded through Android `DownloadManager` into app-specific storage. When switching models, the app prunes older app-managed model files while preserving models still needed by queued background work.

## Build Locally

Requirements:

- Android 8.0+ device or emulator for runtime testing
- Android SDK configured in `local.properties`
- JDK 21
- Enough free storage for the selected model

Common commands:

```bash
./gradlew test
./gradlew assembleDebug
./gradlew installDebug
```

Full CI-style build:

```bash
./gradlew build
```

Quick local smoke check:

```bash
./scripts/test_build.sh
```

Current Android configuration in [app/build.gradle.kts](app/build.gradle.kts):

- `compileSdk = 35`
- `targetSdk = 35`
- `minSdk = 26`
- `versionName = "0.2.0-alpha.1"`

## Signed Releases

Release APKs are signed locally. Signing keys are not stored on GitHub and must stay out of version control.

For the maintainer release flow, see [GitHub Release Process](docs/GITHUB_RELEASE.md).

For testers, use the APK attached to a GitHub release, not `Source code.zip` or `Source code.tar.gz`.

## Architecture

```text
app/src/main/java/com/calendaradd/
  service/     LiteRT-LM integration, model download, background workers, calendar services
  ui/          Compose screens, view models, and theme
  usecase/     business logic, Room database, preferences
  navigation/  app navigation
  util/        image loading, recording, permissions, helpers

app/src/test/java/
  JVM tests for services, worker helpers, and use cases

app/src/test/resources/
  audio-fixtures/       audio transcripts, generated samples, expected events
  image-fixtures/       flyer fixtures, metadata, expected events
  web-lookup-fixtures/  public-event lookup fixtures

scripts/
  fixture generation, benchmark, and device-test helpers

docs/
  release, install, privacy, model, and submission notes
```

## Quality And Benchmarks

The project includes regression fixtures for text, image, audio, and web-lookup behavior. The fixture manifests are designed to grow over time so prompt and extraction changes can be evaluated against a broader corpus instead of isolated examples.

Useful benchmark scripts:

```bash
scripts/benchmark_image_modes.sh
scripts/benchmark_audio_modes.sh
scripts/benchmark_web_lookup_queries.sh
```

These scripts compare classic mode, heavy mode, and web-assisted refinement where applicable.

## LiteRT-LM Notes

The Gemma integration was cross-checked against Google AI Edge Gallery.

Current runtime choices:

- Gemma 4 follows Gallery's `gpu,cpu` main backend order and uses GPU vision.
- Gemma 3n follows Gallery's `cpu,gpu` main backend order and uses GPU vision.
- Gemma audio uses CPU backend, matching Gallery's direct LiteRT-LM path.
- Image, audio, and text jobs initialize only the required modality backends.
- Gemma downloads are pinned to Gallery's Hugging Face commits and exact file sizes.
- Conversations use Gallery's sampler settings: topK 64, topP 0.95, temperature 1.0.
- Images are passed as PNG `ImageBytes`.
- In-app voice capture records 16 kHz mono PCM and sends WAV bytes to the model.

The local Gallery reference clone lives under `external/google-ai-edge-gallery/`. The `external/` directory is intentionally ignored by Git.

## Documentation

- [Install From GitHub](docs/GITHUB_INSTALL.md)
- [GitHub Release Process](docs/GITHUB_RELEASE.md)
- [Release Notes](docs/RELEASE.md)
- [Release Status](docs/RELEASE_STATUS.md)
- [Model Integration](docs/MODEL_INTEGRATION.md)
- [Play Submission](docs/PLAY_SUBMISSION.md)
- [Store Listing Draft](docs/STORE_LISTING.md)
- [Privacy Policy Draft](docs/PRIVACY_POLICY.md)
