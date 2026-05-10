# Calendar Add - LiteRT-LM Model Integration

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
| Gemma 4 E2B Compact | `.litertlm` | Text, Image | Conservative CPU-only Gemma, reduced tokens |
| Gemma 4 E4B | `.litertlm` | Text, Image, Audio | Accelerated Gemma |
| Gemma 3n E2B | `.litertlm` | Text, Image, Audio | Accelerated Gemma |
| Gemma 3n E4B | `.litertlm` | Text, Image, Audio | Accelerated Gemma |
| Qwen 3.5 0.8B LiteRT | `.litertlm` | Text, Image | CPU-only multimodal, experimental, capped tokens |

## Constrained-Device Notes

The app exposes a Gemma 4 E2B Compact profile for constrained devices. It reuses the normal Gemma 4 E2B `.litertlm` file but changes the runtime profile:

- CPU-only text backend
- CPU-only vision backend
- 1024 max tokens
- text and image support only
- no audio support
- 5 GB reported-memory guard

This is a conservative Gemma-only fallback for devices where the default E2B profile fails. It should still be treated as a test profile until verified on the target device set.

Practical RAM guidance from current testing:

- 8 GB-class devices: Gemma 4 E2B may still fail depending on available runtime memory; try Gemma 4 E2B Compact for text/image
- 12 GB+ devices: safer target for Gemma multimodal/audio jobs

Android's reported memory can differ from marketing RAM because the OS, GPU, resident services, and runtime allocator overhead reduce available headroom. A phone sold as 8 GB can still behave like a constrained device for local multimodal inference.

Qwen 3.5 0.8B remains listed as experimental and is not the recommended fallback until it has been tested on the target devices and fixture corpus.

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
  - Gemma 4 E2B text: GPU, then CPU
  - Gemma 4 E4B text: CPU, then GPU
  - Gemma 3n text: CPU, then GPU
  - vision: GPU, then CPU
  - audio: CPU only for audio jobs when the model supports audio
- `CPU_ONLY_MULTIMODAL`
  - text: CPU
  - vision: CPU
  - audio: disabled

The Gemma profile follows Google AI Edge Gallery's allowlist where it has proven stable in this app instead of using one hard-coded order for every Gemma model. Gemma 4 E2B keeps Gallery's `gpu,cpu` text order, GPU vision, and 4000 max tokens. Gemma 4 E4B keeps Gallery's 4000-token window and 12 GB memory guard, but uses CPU-first text because Pixel release testing showed GPU generation failing after conversation creation with an OpenCL runtime error. Gemma 3n lists `cpu,gpu`, uses GPU for vision, CPU for audio, and 4096 max tokens. Like Gallery's Ask Image and Ask Audio tasks, the app initializes only the modality required by the queued job, so image jobs do not load the audio executor and audio jobs do not load the vision executor. The app applies Gallery's device RAM guards before initialization: 8 GB for E2B models and 12 GB for E4B models.

Release builds keep `com.google.ai.edge.litertlm.**` from R8 renaming because LiteRT-LM native code looks up Java classes and accessors by JNI name. Without that keep rule, release builds can initialize the engine and then abort while creating a conversation.

## AI Edge Gallery Parity Notes

A local reference clone of Google AI Edge Gallery can be kept at `external/google-ai-edge-gallery/`. That directory is ignored by Git so it can be reused for comparison without versioning the upstream app.

Current parity choices based on `LlmChatModelHelper`:

- send image content as PNG `ImageBytes`
- send audio as WAV/PCM bytes
- order multimodal content as images, audio, then text
- use async LiteRT-LM callbacks and cancel the conversation on coroutine cancellation
- read generated chunks using `Message.toString()`, matching Gallery's callback path
- use per-model backend order with GPU vision and CPU audio, with Gemma 4 E4B text forced CPU-first after Pixel release testing exposed GPU OpenCL generation failures
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
- Gemma 4 E2B Compact still needs on-device validation on Pixel 9a-class devices

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
