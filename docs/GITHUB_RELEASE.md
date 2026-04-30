# GitHub Release Process

This process publishes a signed APK through GitHub Releases without uploading signing keys to GitHub.

## Local Signing Files

Keep these files local only:

- `keystore.properties`
- the `.jks` or `.keystore` file referenced by `storeFile`

Both are ignored by Git.

`keystore.properties` format:

```properties
storeFile=/absolute/path/to/release-keystore.jks
storePassword=...
keyAlias=calendaradd
keyPassword=...
```

## Build Locally

Make sure the Android SDK is configured through local `local.properties` or an
exported `ANDROID_HOME`.

Before building a signed release, update `versionName` and the README signed APK
link together, then run:

```bash
scripts/check_release_docs.sh
```

```bash
./gradlew clean test assembleRelease
```

Signed APK output:

```text
app/build/outputs/apk/release/app-release.apk
```

If `keystore.properties` is missing, the release APK is not suitable for GitHub distribution.

## Publish

Use a new tag and attach the signed APK:

```bash
TAG=v0.2.0-alpha.1
APK=app/build/outputs/apk/release/app-release.apk
gh release create "$TAG" "$APK#calendar-add-$TAG.apk" \
  --title "Calendar Add $TAG" \
  --notes-file docs/GITHUB_INSTALL.md \
  --prerelease
```

After publishing, verify that the release contains the renamed `.apk` asset and that the install instructions are visible on the release page.

## Versioning Rule

Increase `versionCode` for every APK users may install as an update. Android will reject same-signature updates when the new APK has an equal or lower `versionCode`.
