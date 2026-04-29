# Install From GitHub

This is the non-Play-Store install path for trusted testers.

## Install

1. Open the latest GitHub release on your Android phone:
   `https://github.com/chpomob/calendar_add/releases`
2. Open the newest `v...` release.
3. Download the file ending in `.apk`.
4. If Android asks for permission, allow your browser to install unknown apps.
5. Tap `Install`.
6. Open `Calendar Add`.

Do not download `Source code.zip` or `Source code.tar.gz`; those are not Android apps.

## Update

Install the newer `.apk` from a newer GitHub release. Android should offer `Update`.

If Android says the app cannot be installed because the package conflicts with an existing app, the old app was probably installed from a different signing key, such as a debug build. Uninstall the old app first, then install this release. Uninstalling removes local app data.

## Security Notes

- Only install APKs from the official project releases page.
- Release APKs are signed locally by the maintainer.
- The signing key is not stored on GitHub.
- Android may show warnings because this app is installed outside Google Play.

## First Launch

The app runs AI extraction locally, but it needs to download a model before analysis works. Use Settings to choose a model if needed.
