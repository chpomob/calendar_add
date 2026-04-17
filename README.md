# Calendar Add AI

> Easy calendar event creation using local AI models (Gemma4) on your Android device.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-lightgray)](https://developer.android.com/guide/topics/manifest/min-sdk)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34-blue)](https://developer.android.com/topic/architecture/versions/target-sdk)
[![Privacy First](https://img.shields.io/badge/Privacy-Local%20Only-green)](https://privacy.google.com/)

## Features

- **Easy Event Import**: One-tap creation from text, audio, or images
- **Privacy First**: All AI processing happens locally on your device
- **Offline Capable**: Works without internet connection
- **Smart AI**: Gemma4 local model for accurate event extraction
- **Material 3 UI**: Modern, beautiful interface
- **Dark Mode**: Built-in dark theme

## Installation

### Requirements

- Android 8.0+ (API 26)
- 4GB RAM minimum
- 2GB free storage (for AI model)

### Manual Installation

1. Clone this repository:
   ```bash
   git clone https://github.com/username/calendar-add.git
   cd calendar-add
   ```

2. Set up Android SDK:
   ```bash
   echo "sdk.dir=/path/to/android/sdk" > local.properties
   ```

3. Build and install:
   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug
   ```

### Play Store

[Get it on Google Play](https://play.google.com/store/apps/details?id=com.calendaradd)

## Screenshots

| Text Input | Audio Import | Event List |
|------------|--------------|-------------|
| ![Text Input](docs/screenshots/text-input.png) | ![Audio](docs/screenshots/audio.png) | ![List](docs/screenshots/list.png) |

## How It Works

1. **Paste or record** your event details (text, audio, or image)
2. **AI analyzes** locally to extract event info (title, time, location, etc.)
3. **Create event** instantly to your calendar
4. **Edit or schedule** from your existing calendar app

## Architecture

```
┌─────────────────────────────────────────┐
│            UI Layer (Compose)           │
│    ┌────────────┬────────────┬─────────┐│
│    │  Home      │  Event     │ Settings││
│    │  Screen    │   List     │         ││
│    └────────────┴────────────┴─────────┘│
└─────────────────────────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│         Business Logic Layer            │
│  ┌────────────────┬──────────────────┐  │
│  │  CalendarUseCase│  Validation     │  │
│  │                 └─────────────────┘  │
│  └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│         Data & AI Layer                 │
│  ┌──────────┬──────────┬─────────────┐  │
│  │ TextAnalysis │ Event DB │  Storage │  │
│  │ Service    │  (Room) │   Manager  │  │
│  └──────────┴──────────┴─────────────┘  │
└─────────────────────────────────────────┘
```

## Project Structure

```
calendar-add/
├── app/
│   ├── src/main/
│   │   ├── java/com/calendaradd/
│   │   │   ├── MainActivity.kt              # App entry point
│   │   │   ├── service/
│   │   │   │   └── TextAnalysisService.kt   # AI inference
│   │   │   ├── ui/
│   │   │   │   ├── CalendarHomeScreen.kt    # Main UI
│   │   │   │   └── theme/                   # Material 3 theming
│   │   │   └── usecase/
│   │   │       ├── CalendarUseCase.kt       # Business logic
│   │   │       └── EventDatabase.kt         # Room DAOs
│   │   └── res/                              # Resources
│   ├── build.gradle.kts                     # App build config
│   └── proguard-rules.pro
├── build.gradle.kts                         # Project config
├── settings.gradle.kts                      # Project settings
├── gradle/libs.versions.toml                # Dependency versions
├── DEVELOPMENT_PLAN.md                      # Full development plan
└── README.md
```

## Development

### Setup

```bash
# Clone repository
git clone https://github.com/username/calendar-add.git
cd calendar-add

# Set Android SDK path
echo "sdk.dir=/home/chpo/Android/Sdk" > local.properties

# Sync Gradle
./gradlew tasks

# Build debug APK
./gradlew assembleDebug
```

### Run Tests

```bash
# Run all tests
./gradlew test

# Run instrumentation tests (on device)
./gradlew connectedAndroidTest
```

### Build Release

```bash
# Build signed release APK
./gradlew assembleRelease
```

## Features Breakdown

| Feature | Description | Status |
|---------|-------------|--------|
| Text-to-Event | Paste notes → event created | 🟢 |
| Audio-to-Event | Record audio → event created | 🔴 |
| Image-to-Event | Photo with event info → event | 🟡 |
| File Import | Import from Files app | 🔴 |
| Link Import | URL → event created | 🔴 |
| Offline Mode | Works without internet | 🟢 |
| Privacy First | No data sent to cloud | 🟢 |

### Legend

- 🟢 Implemented
- 🟡 In progress
- 🔴 Planned

## Getting Started

### For Users

1. Install from Google Play Store
2. Tap "New Event" button
3. Paste, record, or take a photo
4. AI creates your event automatically
5. Edit or save to calendar

### For Developers

1. See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) for full roadmap
2. Check out our GitHub issues for feature requests
3. Join our community Discord for support

## Privacy

We respect your privacy:

- ✅ All AI processing happens on-device
- ❌ No data sent to our servers
- ✅ Open source with MIT license
- ✅ Full GDPR compliance

See [Privacy Policy](./PRIVACY_POLICY.md) for details.

## Contributing

Contributions welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Version

Current version: `0.1.0` (alpha)

## License

```
MIT License

Copyright (c) 2024 Calendar Add AI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```
