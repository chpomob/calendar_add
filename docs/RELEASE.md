# Release Notes

## Current Release Build Status

- `compileSdk = 35`
- `targetSdk = 35`
- Release minify and shrink are enabled
- Release Android App Bundle builds successfully

Current output after `./gradlew bundleRelease`:

- `app/build/outputs/bundle/release/app-release.aab`

## What This Means

The project can now produce a release bundle artifact suitable for the next distribution steps.

At the moment, the bundle is generated with the default local release setup. For production distribution, use a dedicated release keystore and stable release versioning.

## Recommended Next Steps

1. Create a dedicated release keystore.
2. Copy `keystore.properties.example` to `keystore.properties` and fill in the real values locally.
3. Wire explicit release signing in Gradle.
4. Replace `versionName = "1.0-RECOVERY"` with a production version.
5. Prepare Play Console assets and declarations:
   - privacy policy
   - data safety form
   - screenshots and listing copy
6. Build and verify:
   - `./gradlew clean test`
   - `./gradlew bundleRelease`

## Known Release Debt

- There is an R8 warning about Kotlin metadata compatibility during `bundleRelease`. It is currently non-blocking, but should be cleaned up before store publication.
- In-app voice capture UI is still incomplete.
- Event list and detail UX remain basic.
