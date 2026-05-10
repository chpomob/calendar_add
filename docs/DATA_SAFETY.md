# Data Safety Checklist

Last reviewed: 2026-05-10

This document is an engineering checklist based on the current codebase. It is not legal advice and should be reviewed before submission in Play Console.

## Current Engineering Assessment

Likely assessment from the current app code:

- core user content is processed on-device
- there is no app backend in the repository
- there are no analytics, ads, or crash reporting SDKs wired in the app
- the app downloads AI model files from external URLs
- the app can optionally read device calendar metadata and write calendar events when the user enables that feature
- the app can optionally send event-related search queries to a selected search provider when experimental web verification is enabled

## Data Types Touched By The App

The codebase can handle:

- text supplied by the user
- images supplied by the user
- audio supplied by the user
- calendar data on the device
- app preferences
- downloaded model files
- event hints used for optional web verification queries

## Likely Play Console Notes

### Data Collected

Engineering view:

- no clear evidence in the current codebase that user text, images, audio, or event contents are transmitted to a Calendar Add server
- no analytics or advertising collection libraries found in the app dependencies

This suggests the likely answer may be that the app does **not** collect user data off-device for its own backend processing.

Exception to review: if experimental web verification is enabled, the app may send search queries derived from OCR text, event titles, dates, locations, URLs, or venue names to the selected search provider. That flow is optional, disabled by default, and used to refine public event details.

### Data Shared

Engineering view:

- no evidence of advertising SDKs or third-party sharing SDKs in the current codebase

This suggests the likely answer may be that the app does **not** share user data with third parties as part of the app's business logic.

Exception to review: optional web verification can disclose query text and network metadata to the selected search provider.

### Data Processed Only On Device

Likely yes for:

- text content
- imported or captured images
- imported audio
- event extraction results

Not always on-device when enabled:

- optional web verification queries and fetched public page snippets

### Calendar Data

The app can:

- read available calendars on the device
- write events to the selected calendar

This is optional and only relevant if the user enables calendar integration and grants permission.

### Network Access

The app uses network access for:

- downloading selected AI model files through Android `DownloadManager`
- optional web verification through the selected search provider

Review whether Play Console declarations should mention any provider-side logging associated with those downloads.

## Permission Mapping

### `INTERNET`

- used to download AI model files
- used for optional web verification

### `POST_NOTIFICATIONS`

- used for background analysis progress and completion notifications

### `FOREGROUND_SERVICE`
### `FOREGROUND_SERVICE_DATA_SYNC`

- used for long-running background analysis

### `READ_CALENDAR`
### `WRITE_CALENDAR`

- used for optional system calendar integration

### `RECORD_AUDIO`

- used for audio input support

### `CAMERA`

- used for direct photo capture

## Before Filling The Form

Review these points carefully:

1. Confirm whether any third-party model host or download endpoint logs request metadata that should influence legal disclosures.
2. Confirm whether Android backup behavior for shared preferences should be mentioned in policy text.
3. Confirm the final answers with whoever is responsible for legal/privacy review.
4. Re-check this document if analytics, crash reporting, sign-in, cloud sync, or server APIs are added later.
5. Re-check this document if web verification defaults, providers, query contents, or fetched page handling changes.
