# Changelog

All notable changes to Calendar Add AI will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.0] - 2026-10-29

### Changed
- The app now targets Android API 36 (compileSdk and targetSdk 36).
- The published release APK now ships for arm64-v8a only. 32-bit ABIs, for which no on-device inference engine exists, are no longer included, which shrinks the APK from about 60 MB to about 31.8 MB. Debug builds keep arm64-v8a + x86_64, and `-PemulatorX86_64=true` produces an x86_64-only APK for emulators.
- Gemma 4 E4B is now the default model in the catalogue. On first launch the app picks E4B on devices reporting at least 12 GB of RAM and E2B otherwise, saves that choice once, never overrides a valid model you already selected, and keeps an existing E2B install without re-downloading it.
- Documentation (README, docs/MODEL_INTEGRATION.md) now describes the four-entry model catalogue.

### Removed
- The Gemma 3n E2B and E4B catalogue entries. They were obsolete and their download URLs required Hugging Face authentication. Leftover Gemma 3n files are still cleaned up, and any download of them still in progress stays protected.

## [Unreleased]

### Added
- Cooperative close for GemmaLlmService — cancels in-flight inference instead of blocking
- Tests for WAV audio duration fallback and GemmaLlmService close behavior

### Changed
- ShareImportActivity now processes incoming content on `Dispatchers.IO` (ANR mitigation)
- ApkDownloadManager refuses APK install when SHA-256 checksum is missing
- BackgroundAnalysisWorker WAV duration fallback (`readWavDurationMs`) is now wired
- GemmaLlmService uses `ReentrantLock` instead of `synchronized` for cooperative cancellation
- SystemCalendarService cursor reads use `.orEmpty()` for nullable platform strings
- UpdateCheckerService accepts 2-component SemVer versions (defaults missing to 0)

### Fixed
- APK self-update integrity: missing checksum now blocks installation
- Main-thread ANR on share import (bitmap decode, byte read, file persist)
- WAV audio duration guard never applied when MediaMetadataRetriever fails
- OOM callback blocked on inference mutex, defeating memory pressure relief
- Calendar event update/delete could target non-owned rows
- NPE on some calendars with null display/account name
- SemanticVersion.parse rejecting "v1.2" style tags
- build.gradle.kts minor version bumps for compatibility

## [0.1.0] - 2026-04-18

### Added
- Initial project setup with Gradle build configuration
- Basic Jetpack Compose UI (MainActivity, HomeScreen)
- Room database skeleton
- Material 3 theming (light/dark)
- TensorFlow Lite integration dependencies
- GitHub Actions CI/CD pipeline
- Version control and branch protection

### Changed
- Project structure established
- Dependency versions catalogued

### Fixed
- Initial build issues

## [0.0.1] - 2026-04-18

### Added
- Project scaffolding
- README documentation
