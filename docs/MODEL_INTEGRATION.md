# Calendar Add AI - LiteRT-LM Model Integration

Last updated: 2026-04-20

## Overview

The app no longer uses bundled GGUF assets or a manual workstation-side download step.

Current model behavior:

- models are defined in `LiteRtModelCatalog`
- the user selects the model in Settings
- the app downloads the selected `.litertlm` file at runtime with `DownloadManager`
- the downloaded file is stored in the app-specific downloads directory
- `GemmaLlmService` initializes backends from the selected model profile

Relevant code:

- `app/src/main/java/com/calendaradd/service/LiteRtModelCatalog.kt`
- `app/src/main/java/com/calendaradd/service/ModelDownloadManager.kt`
- `app/src/main/java/com/calendaradd/service/GemmaLlmService.kt`
- `app/src/main/java/com/calendaradd/ui/CalendarSettingsScreen.kt`
- `app/src/main/java/com/calendaradd/ui/HomeViewModel.kt`

## Available Models

| Model | File Type | Inputs | Execution Profile |
|------|-----------|--------|-------------------|
| Gemma 4 E2B | `.litertlm` | Text, Image, Audio | Accelerated Gemma |
| Gemma 4 E4B | `.litertlm` | Text, Image, Audio | Accelerated Gemma |
| Gemma 3n E2B | `.litertlm` | Text, Image, Audio | Accelerated Gemma |
| Gemma 3n E4B | `.litertlm` | Text, Image, Audio | Accelerated Gemma |
| Qwen 3.5 0.8B LiteRT | `.litertlm` | Text, Image | CPU-only multimodal, experimental, capped tokens |

## Download And Storage

The app downloads models with Android `DownloadManager` and stores them in:

- `context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`
- fallback: `context.filesDir`

The app checks:

- enough free space before download
- a minimum downloaded size threshold before treating a model as usable

There is no longer a supported flow that downloads models into `app/src/main/assets/`.

After the selected model initializes successfully, the app removes older app-managed model files from the same storage directory and keeps the active model file.

## Backend Profiles

`GemmaLlmService` selects backends from the chosen model:

- `ACCELERATED_GEMMA`
  - text: prefer NPU
  - vision: CPU
  - audio: CPU when the model supports audio
- `CPU_ONLY_MULTIMODAL`
  - text: CPU
  - vision: CPU
  - audio: disabled

This is why Qwen models now load correctly in the app: they are initialized with the CPU-only multimodal profile instead of the Gemma-oriented backend path.

For Qwen models, the app also sets conservative `maxNumTokens` values during engine creation to reduce compiled-model memory pressure on Android devices.

## Input Support

- Text input is available from the home screen
- Image input is available from the home screen and Android share intents
- Audio bytes can be accepted from Android share intents
- In-app voice recording UI is still unfinished
- Slow analysis jobs are executed through a foreground WorkManager worker with a notification

## Event Extraction Contract

The app now expects the LLM to return JSON in this shape:

```json
{
  "events": [
    {
      "title": "",
      "description": "",
      "startTime": "ISO-8601",
      "endTime": "ISO-8601",
      "location": "",
      "attendees": []
    }
  ]
}
```

Single-event responses are still represented as an `events` array with one item.

`TextAnalysisService` can also tolerate:

- a single root object
- a root array
- fenced JSON output

It merges compatible fragments of the same event and keeps distinct events separate.

## Verification

Current verified commands:

```bash
./gradlew test
./gradlew assembleDebug
```

## Obsolete Guidance

The following old assumptions are no longer correct:

- GGUF model files in `assets/`
- llama.cpp integration
- manual `download_model.sh` setup
- “fallback extraction without a model”
