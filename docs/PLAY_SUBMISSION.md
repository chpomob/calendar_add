# Play Submission Notes

This document is a release-prep scaffold based on the current codebase state.

## Release Identity

- App name: `Calendar Add`
- Application ID: `com.calendaradd`
- Version code: `3`
- Version name: `1.0.0`

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
  Present for audio input support. In-app voice capture UI is still incomplete.
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

This should still be reviewed carefully before filling the Play Console data safety form.

## Privacy Policy Draft Topics

The public privacy policy should explicitly cover:

- what input types the app can access: text, images, audio, calendar
- that model downloads require network access
- that event extraction is intended to happen on-device
- whether any user content is sent off-device by the app
- how calendar sync works and that it is optional
- where downloaded models are stored
- how users can remove app data and downloaded models

## Open Release Risks

- voice capture UI is still unfinished
- event list/detail editing remains basic
- R8 emits a non-blocking Kotlin metadata warning during release bundling
