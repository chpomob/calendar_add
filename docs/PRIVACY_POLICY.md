# Privacy Policy Draft

Last updated: 2026-05-10

This draft is based on the current Calendar Add codebase and is intended as a release-preparation starting point. Review the final legal wording before publication.

## Overview

Calendar Add helps users create calendar events from text, images, audio, and shared content. The app is designed to process event extraction on-device after the required AI model has been downloaded.

## What The App Accesses

Depending on which features you use, the app may access:

- text you paste or share into the app
- images you select, capture, or share into the app
- microphone audio when you use live voice capture
- audio you share into the app
- your device calendar, if you enable calendar sync
- app notifications, to show progress and completion for background analysis
- network access, to download AI model files and, if enabled, perform optional web verification

## How Your Data Is Used

Calendar Add uses your data only to provide its core functionality:

- extracting event details from text, images, or audio
- storing created events locally inside the app
- optionally writing created events into your device calendar
- downloading the on-device AI model you choose
- optionally checking public web search/page results to refine event details when web verification is enabled
- showing background processing notifications

## On-Device Processing

Based on the current app implementation:

- event extraction is intended to run locally on your Android device
- pasted text, imported images, shared files, and extracted event data are processed on-device by the app during the core extraction flow
- temporary files may be created in app-private cache storage while background analysis is running

## Network Use

The app uses network access to download AI model files selected by the user. Those downloads are performed with Android's system download service into app-specific storage.

If you enable experimental web verification, the app may send event-related search queries to the selected search provider and fetch public page snippets to refine details such as venue names, addresses, dates, or times. Queries can be derived from OCR text, extracted event fields, URLs, or venue names. Web verification is optional and disabled by default.

The current app code does not include:

- an app backend
- user accounts
- analytics SDKs
- advertising SDKs
- crash reporting SDKs

The app's core event extraction flow is not designed to upload your event content to a Calendar Add server.

## Local Storage

The app stores data locally on the device, including:

- created events in the app's local database
- app preferences such as selected model and calendar-sync settings
- downloaded AI model files
- image and audio source files linked from created event details
- temporary cached analysis input files during processing

The app's own preference file and linked event source files are not opted into Android cloud backup or device-transfer backup rules.

## Calendar Integration

If you grant calendar permission and enable sync, the app can:

- read available calendars on the device so you can choose a target calendar
- write created events into the chosen device calendar

Calendar sync is optional and controlled by the user.

## Sharing

Based on the current codebase, Calendar Add does not intentionally sell or share your personal content with third parties for advertising or analytics purposes.

If optional web verification is enabled, query text and normal network metadata may be visible to the selected search provider.

## Data Retention

Data remains on your device unless you remove it. You can reduce stored data by:

- deleting events inside the app
- disabling calendar sync
- clearing app storage from Android system settings
- deleting downloaded model files by removing app data or uninstalling the app

## Security

The app relies on Android app sandboxing, app-private storage, and system permissions to protect locally stored content. No security method is perfect, and users should avoid using the app for highly sensitive information unless they are comfortable with local device storage.

## Children's Privacy

The app is not specifically directed to children.

## Contact

- Contact email: `chpomob@gmail.com`

## Changes To This Policy

This policy should be updated when the app's permissions, data flows, third-party services, or storage behavior materially change.
