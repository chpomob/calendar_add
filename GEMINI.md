# GEMINI.md (Updated 2026)

## Project Overview

**Calendar Add AI** is a privacy-first Android application that autonomously generates calendar events from text, audio, and images using on-device Generative AI. 

### Core Technologies
- **Platform:** Android (Min SDK 26, Target SDK 34)
- **AI Engine:** **Gemma 4 E2B** via Google ML Kit GenAI Prompt API.
- **Speech-to-Text:** ML Kit GenAI Speech Recognition (Advanced Mode).
- **OCR:** ML Kit Text Recognition v2.
- **Calendar Integration:** Android `CalendarProvider`.
- **Database:** Room (Reactive Flow support).
- **UI:** Jetpack Compose (Material 3).

### Architecture
The project follows **Clean Architecture** with a clear separation of concerns:

1.  **UI Layer (`com.calendaradd.ui`):** 
    - Compose Screens.
    - `HomeViewModel` for managing AI lifecycle (model downloads, inference states).
2.  **Domain/Use Case Layer (`com.calendaradd.usecase`):** 
    - `CalendarUseCase`: Orchestrates the flow from raw analysis to database persistence.
3.  **Service Layer (`com.calendaradd.service`):** 
    - `GemmaLlmService`: Wrapper for GenAI Prompt API.
    - `SpeechToTextService`: Handles local audio transcription.
    - `OcrService`: Extracts text from images.
    - `SystemCalendarService`: Directly interacts with the Android System Calendar.
    - `TextAnalysisService`: Pipeline orchestrator (Text/Image -> Gemma -> JSON).

---

## Building and Running

### Prerequisites
- Android Studio (2026 version recommended).
- Device with NPU/GPU support (Pixel 7+, Galaxy S24+).
- Internet connection for *initial* model download (Gemma 4 is ~1.5GB).

### Key Commands
- **Build Project:**
  ```bash
  ./gradlew assembleDebug
  ```
- **Run Tests:**
  ```bash
  ./gradlew test
  ```

---

## Development Conventions

### AI & Privacy
- **Local-Only:** No user data (audio, images, or text) must ever leave the device.
- **Streaming:** Use Kotlin `Flow` for long-running AI tasks (model downloading, transcription).
- **Structured Output:** Gemma 4 is prompted to return JSON. Always use `TextAnalysisService` to parse and validate this output.

### Data Handling
- **Time:** Store all timestamps as `Long` (UTC milliseconds).
- **Reactive UI:** Room DAOs must return `Flow<T>` for automatic UI updates.

---

## Key Files
- `GemmaLlmService.kt`: Core AI integration.
- `TextAnalysisService.kt`: Event extraction logic.
- `SystemCalendarService.kt`: System calendar integration.
- `HomeViewModel.kt`: App state management.
