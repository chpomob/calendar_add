# Calendar Add Development Plan

Last updated: 2026-05-10

This file is the current roadmap. Older prototype assumptions about bundled TensorFlow/GGUF assets, llama.cpp, and a manual model download flow are obsolete.

## Current State

Calendar Add is a single-module Android app that creates calendar events from text, images, shared files, and short audio captures.

Current implementation:

- Jetpack Compose UI with home, settings, event list, editable event detail, and privacy screens
- Room database for locally saved events
- WorkManager foreground jobs for slow analysis
- runtime LiteRT-LM `.litertlm` model downloads through Android `DownloadManager`
- selectable Gemma and Qwen model catalog
- local text, image, and audio extraction pipeline
- optional heavy image/audio analysis mode
- optional experimental web verification for public event details
- optional sync to the Android system calendar, including updates to already-synced events after local edits
- GitHub Actions for build, test, lint, security scan, and debug APK artifacts

Current Gradle release identity:

- `compileSdk = 35`
- `targetSdk = 35`
- `minSdk = 26`
- `versionCode = 5`
- `versionName = "0.3.0-alpha.0"`
- JDK 21

## Quality Baseline

Local verification commands:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Regression coverage currently focuses on:

- prompt policy and prompt budgets
- JSON parsing and multi-event extraction
- use-case validation and persistence behavior
- model catalog and LiteRT-LM backend configuration
- background worker helpers and scheduler behavior
- audio, image, and web lookup fixture suites

## Near-Term Priorities

1. Keep release documentation synchronized with Gradle version values and model behavior.
2. Add device-level smoke tests for model download, background analysis, share intents, and calendar sync.
3. Add Compose UI tests for the primary capture, settings, list, and detail flows.
4. Improve event list and detail workflows with search, filtering, delete actions, and date/time picker polish.
5. Consolidate manual service wiring into a small app container or dependency injection layer.
6. Expand model/backend runtime verification across supported devices.

## Release Readiness Gaps

- final hosted privacy policy URL
- Play Store screenshots and feature graphic
- Play Console data-safety review for optional web verification
- device matrix notes for each supported model/backend combination
- stronger UX for failed or partial extraction results
- final event search/delete workflow polish
- date/time picker polish for event editing

## Product Guardrails

- Keep core extraction local-first.
- Keep web verification optional and clear in privacy documentation.
- Do not ship placeholder model assets.
- Prefer regression fixtures before prompt changes.
- Avoid claiming deterministic extraction accuracy or speed across devices.

## Historical Notes

Historical planning material before May 2026 referenced bundled assets, TensorFlow Lite placeholders, and manual model conversion. Those flows are no longer supported by the current app.
