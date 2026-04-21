# Calendar Add AI

Android app for creating calendar events from local AI analysis of text, images, and shared audio files.

The app currently uses LiteRT-LM models downloaded at runtime. After the model download, extraction stays on-device.

## Current State

- Buildable and testable on `main`
- Runtime model download on first use
- Model selection in Settings
- Text input, direct camera capture, image picker, and Android share-intent import are wired
- Multiple events can be extracted from one input and saved in one pass
- Long-running analysis is queued into a foreground background worker with notifications
- Press-and-hold microphone capture with a 30-second limit, audio-file import, and shared audio analysis are wired
- Shared text, image, and audio imports try to queue directly into background analysis without opening the main UI when the model is already available
- Optional sync to the device calendar

Current gaps:

- No extraction fallback path when no model is installed
- Event list/detail screens are basic: no search, edit, or delete UI yet
- In-app completion handling still relies mainly on notifications rather than rich live progress UI

## Supported Models

Configured in [LiteRtModelCatalog.kt](app/src/main/java/com/calendaradd/service/LiteRtModelCatalog.kt):

| Model | Inputs | Notes |
|------|--------|-------|
| Gemma 4 E2B | Text, Image, Audio | Default balanced option |
| Gemma 4 E4B | Text, Image, Audio | Larger Gemma 4 variant |
| Gemma 3n E2B | Text, Image, Audio | Strong multimodal candidate |
| Gemma 3n E4B | Text, Image, Audio | Larger Gemma 3n variant |
| Qwen 3.5 0.8B LiteRT | Text, Image | CPU-only profile, no audio, experimental, conservative token cap |

Models are downloaded by the app into its app-specific downloads directory through `DownloadManager`.
After a successful model switch, the app removes older app-managed model files while preserving any model still needed by queued background analysis jobs.
Slow analysis jobs are executed through WorkManager in the foreground with visible Android notifications, and each finished job keeps its own result notification.
Queued background inputs are stored in app-private no-backup storage so they survive normal app restarts and device reboots more reliably than cache-based temp files.

## Requirements

- Android 8.0+ (`minSdk 26`)
- Android SDK configured in `local.properties`
- JDK 21
- Enough free storage for the chosen model

## Build And Test

```bash
./gradlew test
./gradlew assembleDebug
./gradlew installDebug
```

Useful local smoke check:

```bash
./scripts/test_build.sh
```

## Project Layout

```text
app/src/main/java/com/calendaradd/
  service/     LiteRT-LM integration, model download, OCR, calendar services
  ui/          Compose screens and view models
  usecase/     business logic, Room database, preferences
  navigation/  app navigation
  util/        shared helpers
app/src/test/java/
  JVM tests for services and use cases
docs/
  current model/runtime notes and release-state docs
```

## User Flow

1. Open the app and download a model.
2. Optionally switch models in Settings.
   New model choices apply to the next analysis job; the app no longer eagerly initializes LiteRT-LM on the home screen.
3. Paste text, take a photo, pick an image, or share content into the app.
4. Review the created events in the event list.
5. Optionally sync events to the system calendar.

## Notes

- The app is local-first, but the one-time model download requires network access.
- Qwen models do not support audio in this app and remain experimental.
- Large local models can still be slow, but analysis can now continue in the background with a notification.
- Live microphone capture uses a press-and-hold interaction and stops automatically after 30 seconds.
- Extracted events are only saved when the model returns a parseable start date/time; malformed dates are rejected instead of defaulting to the current time.
- Prompting now supplies an explicit local reference datetime and timezone so relative phrases such as `tomorrow` or `next Friday` are resolved more reliably.
- App preferences stay local to the device and are not included in Android cloud backup rules.
- If Android notifications are disabled for the app, the home screen now warns that background analysis may run silently.

## Documentation

- [Model Integration](docs/MODEL_INTEGRATION.md)
- [Release Status](docs/RELEASE_STATUS.md)
- [Development Plan](DEVELOPMENT_PLAN.md)
