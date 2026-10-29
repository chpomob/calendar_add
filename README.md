# Calendar Add

[![Android CI](https://github.com/chpomob/calendar_add/actions/workflows/android.yml/badge.svg)](https://github.com/chpomob/calendar_add/actions/workflows/android.yml)

`Calendar Add` is an Android app that turns event information from text, flyers, shared images, and short voice captures into calendar events.

The app is local-first after model download: AI extraction runs on-device with LiteRT-LM models, events are stored locally, and syncing to the Android system calendar is optional.

## Install The Latest APK

For non-technical Android testers, use the signed APK from GitHub Releases:

https://github.com/chpomob/calendar_add/releases/download/v0.4.1/app-release.apk

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
- Edit extracted events before syncing them to the Android system calendar.
- Optionally sync extracted events to the Android system calendar.
- Update an already-synced Android calendar event after local edits without creating a duplicate.
- Apply conservative event-type time defaults when a flyer omits a start or end time.
- Enable heavy analysis mode for harder image and audio inputs.
- Enable experimental web verification to refine public event details when online lookup succeeds.

## Project Status

This project is active and buildable on `main`, but it is still an alpha/beta-quality app rather than a finished consumer release.

Known limitations:

- A local model must be downloaded before extraction works.
- Local inference can be slow on large multimodal models.
- Image and audio accuracy depends strongly on the selected model and input quality.
- Missing flyer times are resolved with conservative heuristics for strong event types, but they still need user review.
- The event list is still basic and does not yet have search, filters, or delete actions.
- Progress and failure reporting still relies heavily on Android notifications.
- Web verification is experimental and can be limited by search provider restrictions.

## Recommended Models

The app exposes these LiteRT-LM models in [LiteRtModelCatalog.kt](app/src/main/java/com/calendaradd/service/LiteRtModelCatalog.kt):

| Model | ID | Inputs | Notes |
|------|----|--------|-------|
| Gemma 4 E2B | `gemma-4-e2b` | Text, Image, Audio | Balanced option for 8 GB devices; first selection when memory is below 12 GB or unknown |
| Gemma 4 E2B Compact | `gemma-4-e2b-compact` | Text, Image | Conservative CPU-only profile for constrained devices; reuses the Gemma 4 E2B file |
| Gemma 4 E4B | `gemma-4-e4b` | Text, Image, Audio | Catalogue default; larger Gemma 4 variant, first selection on devices reporting 12 GB or more |
| Qwen 3.5 0.8B LiteRT | `qwen-3_5-0_8b` | Text, Image | Experimental, CPU-only, no audio |

First model selection:

- On a fresh install with no stored choice, the app picks Gemma 4 E4B when the device reports 12 GB of memory or more (>= 12 GB), and Gemma 4 E2B otherwise or when the reported memory is unknown. That choice is then persisted.
- An explicit model choice made in Settings always wins and is never replaced automatically.
- An upgrading install that already has the Gemma 4 E2B model file and no stored choice keeps Gemma 4 E2B instead of downloading E4B.
- Gemma 4 E2B Compact and Qwen 3.5 0.8B are never selected automatically; they are only used when chosen in Settings.

Practical recommendation:

- Use Gemma models for normal testing.
- Try Gemma 4 E2B Compact on constrained devices where the regular Gemma 4 E2B profile fails. It supports text/image only.
- Treat Qwen as experimental in this app.
- Prefer smaller Gemma variants on memory-constrained devices.
- Treat 12 GB RAM as the safer floor for Gemma multimodal/audio reliability; 8 GB devices may still fail depending on available runtime memory.
- Pixel 9a-class devices can fail with Gemma 4 E2B even though they are not low-end; validate on-device before recommending a model to testers.

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

### Supported ABIs And Emulator Builds

The published release APK is arm64-only (`arm64-v8a`). LiteRT-LM ships no inference engine (`liblitertlm_jni.so`, `libLiteRt.so`) for 32-bit ABIs, so a 32-bit device could install the app but never run inference.

Regular debug builds package `arm64-v8a` and `x86_64`. To build a debug APK for an x86_64 emulator only:

```bash
./gradlew --offline -PemulatorX86_64=true :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The `emulatorX86_64` property only affects the debug build; it does not affect release packaging, which stays arm64-only.

Current Android configuration in [app/build.gradle.kts](app/build.gradle.kts):

- `compileSdk = 35`
- `targetSdk = 35`
- `minSdk = 26`
- `versionName = "0.4.1"`

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

- Gemma 4 E2B follows Gallery's `gpu,cpu` main backend order, GPU vision, and 4000 max tokens.
- Gemma 4 E4B keeps Gallery's 4000-token window and 12 GB memory guard, but uses CPU-first text in this app because Pixel release testing showed GPU generation failing after conversation creation with an OpenCL runtime error.
- Gemma audio uses CPU backend, matching Gallery's direct LiteRT-LM path.
- Image, audio, and text jobs initialize only the required modality backends.
- Gemma downloads are pinned to Gallery's Hugging Face commits and exact file sizes.
- Conversations use Gallery's sampler settings: topK 64, topP 0.95, temperature 1.0.
- Release builds keep LiteRT-LM's JNI-facing classes from R8 renaming.
- Images are passed as PNG `ImageBytes`.
- In-app voice capture records 16 kHz mono PCM and sends WAV bytes to the model.

The local Gallery reference clone lives under `external/google-ai-edge-gallery/`. The `external/` directory is intentionally ignored by Git.

## License

Calendar Add is released under the [WTFPL](LICENSE).

In plain language: use it, copy it, fork it, modify it, publish it, sell it, or reuse parts of it without asking permission. This project is shared freely.

Project guidelines such as the code of conduct, contributing guide, and security policy govern interaction with this repository. They do not add restrictions to the WTFPL license. See [Usage Freedom](docs/USAGE_FREEDOM.md).

## Documentation

- [Install From GitHub](docs/GITHUB_INSTALL.md)
- [GitHub Release Process](docs/GITHUB_RELEASE.md)
- [Usage Freedom](docs/USAGE_FREEDOM.md)
- [Release Notes](docs/RELEASE.md)
- [Release Status](docs/RELEASE_STATUS.md)
- [Model Integration](docs/MODEL_INTEGRATION.md)
- [Play Submission](docs/PLAY_SUBMISSION.md)
- [Store Listing Draft](docs/STORE_LISTING.md)
- [Privacy Policy Draft](docs/PRIVACY_POLICY.md)
