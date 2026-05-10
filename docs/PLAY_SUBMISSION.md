# Play Submission Notes

This document is a release-prep scaffold based on the current codebase state.

## Release Identity

- App name: `Calendar Add`
- Application ID: `com.calendaradd`
- Version code: `5`
- Version name: `0.3.0-alpha.0`
- Contact email: `chpomob@gmail.com`

## Suggested Store Copy

### Short Description

Create calendar events from text, photos, and shared content with private on-device AI.

### Full Description Draft

Calendar Add turns messy real-world inputs into structured calendar events directly on your Android device.

Use it to:

- paste text or copied invitations
- take a photo of a flyer or schedule
- import an image from files
- share text, images, or audio from other apps
- edit extracted event details
- review extracted events before syncing them to your calendar

Core behavior:

- local-first extraction with downloadable on-device models
- background analysis with Android notifications
- support for multi-event extraction from a single input
- optional sync to the system calendar

## Required Store Assets Checklist

- app icon
- phone screenshots
- 7-inch tablet screenshots if targeting tablets
- 10-inch tablet screenshots if targeting tablets
- feature graphic
- category
- contact email
- privacy policy URL

## Repository Scaffolding

The repository now contains:

- `docs/PRIVACY_POLICY.md`
- `docs/DATA_SAFETY.md`
- `docs/STORE_LISTING.md`

There is also an in-app `Privacy & data` screen reachable from Settings. It is a user-facing summary, not a substitute for the final hosted privacy policy URL required by Play.

## Permission And Feature Notes

From the current manifest:

- `INTERNET`
  Used to download local AI models.
- `POST_NOTIFICATIONS`
  Used for background analysis progress and completion notifications.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`
  Used for long-running background analysis through WorkManager foreground execution.
- `READ_CALENDAR` and `WRITE_CALENDAR`
  Used only for optional sync to the device calendar.
- `RECORD_AUDIO`
  Present for audio input support. The app now supports press-and-hold in-app voice capture and audio file import.
- `CAMERA`
  Used for direct photo capture before local image analysis.

Optional hardware features are marked non-required:

- microphone
- camera

## Data Safety Draft Notes

Based on the current codebase:

- event extraction runs locally after the model download
- the app downloads models from external URLs into app-specific storage
- there is no app backend, account system, analytics SDK, or crash reporting SDK currently wired in the project
- shared text, images, audio, and calendar data are processed on-device for the app’s core functionality
- the app optionally offers a heavier staged extraction mode for image/audio inputs, which stays on-device but may run longer
- the app optionally offers experimental web verification; when enabled, event hints from OCR/extraction may be sent to the selected search provider to refine public event details

This should still be reviewed carefully before filling the Play Console data safety form.

## Privacy Policy Draft Topics

The public privacy policy should explicitly cover:

- what input types the app can access: text, images, audio, calendar
- that model downloads require network access
- that event extraction is intended to happen on-device
- that optional web verification can send event-related search queries to third-party search providers when enabled
- how calendar sync works and that it is optional
- where downloaded models are stored
- how users can remove app data and downloaded models

## Open Release Risks

- local multimodal speed and reliability still vary by device and model
- event list search/filter/delete remains basic
- event editing uses text-entry date/time fields and still needs picker polish
