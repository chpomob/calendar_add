# Changelog

All notable changes to Calendar Add AI will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- System calendar ownership verification via ExtendedProperties marker (CVE-2025-XXXX mitigation)
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
