# Release Notes

Last updated: 2026-04-30

## Current Release Build Status

- `compileSdk = 35`
- `targetSdk = 35`
- `versionCode = 5`
- `versionName = "0.3.0-alpha.0"`
- Release minify and shrink are enabled
- Release Android App Bundle builds successfully
- Release signing is wired through local `keystore.properties`
- GitHub Actions publishes a debug APK on normal pushes
- Successful pushes to `main` update a rolling `latest-main` prerelease with the current debug APK
- GitHub Actions publishes an unsigned release AAB for `v*` tags
- Alpha tags like `v0.1.0-alpha.2` now draft a GitHub prerelease with attached build artifacts
- GitHub Releases can also be created manually with a locally signed APK for testers outside the Play Store
- Settings now expose an optional heavy analysis mode for staged image/audio extraction
- `v0.3.0-alpha.0` is the pre-UI-revamp minor checkpoint release

Current output after `./gradlew bundleRelease`:

- `app/build/outputs/bundle/release/app-release.aab`

## What This Means

The project can now produce a release bundle artifact suitable for the next distribution steps.

Gradle now reads release signing values from a local `keystore.properties` file when it exists.
In CI, release bundles are intentionally built without the local keystore so signing secrets do not need to be stored on GitHub.

Expected keys:

- `storeFile`
- `storePassword`
- `keyAlias`
- `keyPassword`

Use `keystore.properties.example` as the template.

For GitHub APK distribution, build and publish locally so the signing key never leaves the development machine. See [GitHub Release Process](GITHUB_RELEASE.md) and [Install From GitHub](GITHUB_INSTALL.md).

## Recommended Next Steps

1. Back up the release keystore safely. Losing it means losing the signing identity for future updates unless you rotate through Play processes.
2. Prepare Play Console assets and declarations:
   - privacy policy
   - data safety form
   - screenshots and listing copy
3. Build and verify:
   - `./gradlew clean test`
   - `./gradlew bundleRelease`
4. For tester distribution:
   - use the GitHub Actions debug APK artifact on normal commits
   - use alpha tags to draft a prerelease with attached APK and unsigned AAB

## Known Release Debt

- There is an R8 warning about Kotlin metadata compatibility during `bundleRelease`. It is currently non-blocking, but should be cleaned up before store publication.
- Local multimodal inference can still be slow and may still be device-sensitive on large jobs.
- Event list and detail UX remain basic.
