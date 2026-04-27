# Calendar Add AI - LiteRT-LM Model Integration

Last updated: 2026-04-27

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
  - Gemma 4 text: GPU, then CPU
  - Gemma 3n text: CPU, then GPU
  - vision: GPU, then CPU
  - audio: CPU only for audio jobs when the model supports audio
- `CPU_ONLY_MULTIMODAL`
  - text: CPU
  - vision: CPU
  - audio: disabled

The Gemma profile follows Google AI Edge Gallery's allowlist instead of using one hard-coded order for every Gemma model. Gemma 4 lists `gpu,cpu` with `visionAccelerator: gpu`; Gemma 3n lists `cpu,gpu`, uses GPU for vision, and uses CPU for audio. Like Gallery's Ask Image and Ask Audio tasks, the app initializes only the modality required by the queued job, so image jobs do not load the audio executor and audio jobs do not load the vision executor. The app also applies Gallery's minimum device RAM values before initialization: 8 GB for E2B models and 12 GB for E4B models.

Gemma 4 E4B has one additional app safety policy: on devices below 16 GB RAM, image/audio jobs start with CPU for the text backend and GPU for vision. A Pixel 8 Pro with 12 GB RAM was observed to kill the app during native E4B `GPU(text)+GPU(vision)` initialization before Kotlin fallback could run, even with audio disabled. The E2B model and 16 GB+ devices still keep Gemma 4's Gallery `gpu,cpu` text backend order.

For Qwen models, the app keeps a conservative `maxNumTokens` value during engine creation to reduce compiled-model memory pressure on Android devices. Gemma token windows stay aligned with Gallery's model allowlist values: 4000 for Gemma 4 and 4096 for Gemma 3n. The larger token windows are required for image prompts because LiteRT-LM prefill can fail if the combined image and text context is smaller than the model needs. Gemma conversations use Gallery's sampler settings: topK 64, topP 0.95, temperature 1.0.

## AI Edge Gallery Parity Notes

A local reference clone of Google AI Edge Gallery can be kept at `external/google-ai-edge-gallery/`. That directory is ignored by Git so it can be reused for comparison without versioning the upstream app.

Current parity choices based on `LlmChatModelHelper`:

- send image content as PNG `ImageBytes`
- send audio as WAV/PCM bytes
- order multimodal content as images, audio, then text
- use async LiteRT-LM callbacks and cancel the conversation on coroutine cancellation
- use per-model Gallery backend order with GPU vision and CPU audio
- initialize image/audio/text jobs with only their required LiteRT-LM modality backends
- tell audio prompts to ignore filler words, background noise, repeated fragments, and ASR mistakes
- tell image prompts to treat flyers, posters, screenshots, and event notices as the main source of title/date/time/location data
- keep regression fixtures under `app/src/test/resources/audio-fixtures/` and `app/src/test/resources/image-fixtures/`
- use `scripts/generate_audio_fixtures.sh` to refresh transcript-backed WAV samples
- use `scripts/run_audio_fixture_on_device.sh <fixture-id>` to push a fixture into app-specific storage and exercise the real share path on-device
- use `scripts/check_image_flyer_cases.sh` to validate the synthetic flyer suite against local Gemma 4
- use `scripts/benchmark_image_modes.sh` to compare classic vs heavy image scoring across the full corpus
- use `scripts/benchmark_audio_modes.sh` to compare classic vs heavy audio prompt scoring from transcript proxies
- experimental optional late web lookup is available for image heavy mode when OCR exposes event hints; it stays off by default, runs after local extraction, reads a bounded page text snippet, and may perform one extra venue-address lookup when the local result only has a venue name
- experimental web lookup supports an optional Brave Search API provider configured in Settings; if no key is configured or the API fails, the app falls back to DuckDuckGo HTML search, which can be blocked by anti-bot pages
- use `scripts/benchmark_web_lookup_queries.sh` to compare heuristic vs model-generated web search queries on the image corpus before adding more web-planning logic
- use `scripts/generate_web_lookup_fixtures.sh` to snapshot live metadata from public event pages into `app/src/test/resources/web-lookup-fixtures/`
- use `scripts/benchmark_web_lookup_live.sh` to compare query planners against live DuckDuckGo results on the snapshot corpus
- pin Gemma downloads to Gallery's Hugging Face commit hashes and exact file sizes
- follow `docs/PROMPT_POLICY.md` when deciding whether a failure belongs in the prompt, the parser, or the corpus

Known limitations from the hard-case suite:

- audio prompts can still over-trigger on bare time mentions and produce a generic `Meeting`
- flyer series with clearly separated rows are handled, but the layout must stay readable for OCR

## Input Support

- Text input is available from the home screen
- Image input is available from the home screen and Android share intents
- Audio bytes can be accepted from Android share intents
- In-app voice recording captures 16 kHz mono PCM and wraps it as WAV before inference
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
