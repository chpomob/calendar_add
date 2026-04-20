# Release Notes

## Current Release Build Status

- `compileSdk = 35`
- `targetSdk = 35`
- Release minify and shrink are enabled
- Release Android App Bundle builds successfully
- Release signing is wired through local `keystore.properties`

Current output after `./gradlew bundleRelease`:

- `app/build/outputs/bundle/release/app-release.aab`

## What This Means

The project can now produce a release bundle artifact suitable for the next distribution steps.

Gradle now reads release signing values from a local `keystore.properties` file when it exists.

Expected keys:

- `storeFile`
- `storePassword`
- `keyAlias`
- `keyPassword`

Use `keystore.properties.example` as the template.

## Recommended Next Steps

1. Back up the release keystore safely. Losing it means losing the signing identity for future updates unless you rotate through Play processes.
2. Replace `versionName = "1.0-RECOVERY"` with a production version.
3. Prepare Play Console assets and declarations:
   - privacy policy
   - data safety form
   - screenshots and listing copy
4. Build and verify:
   - `./gradlew clean test`
   - `./gradlew bundleRelease`

## Known Release Debt

- There is an R8 warning about Kotlin metadata compatibility during `bundleRelease`. It is currently non-blocking, but should be cleaned up before store publication.
- In-app voice capture UI is still incomplete.
- Event list and detail UX remain basic.
