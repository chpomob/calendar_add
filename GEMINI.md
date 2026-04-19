# GEMINI.md (Updated 2026 - LiteRT-LM Edition)

## Project Overview

**Calendar Add AI** is a privacy-first Android application that uses **LiteRT-LM** to natively process multimodal inputs (text, image, audio) and generate calendar events locally with **Gemma 4**.

### Core Technologies
- **Platform:** Android (Min SDK 26, Target SDK 34)
- **AI Engine:** **LiteRT-LM** (`com.google.ai.edge.litertlm`) for Gemma 4.
- **Multimodality:** Native support for Text, Image, and Audio via `Content` builder.
- **Hardware Acceleration:** **NPU** support via NeuroPilot Accelerator.
- **Calendar Integration:** Android `CalendarProvider`.
- **Database:** Room (Reactive Flow support).
- **UI:** Jetpack Compose (Material 3).

### Architecture
The project follows **Clean Architecture** with a simplified AI pipeline:

1.  **UI Layer (`com.calendaradd.ui`):** 
    - `HomeViewModel`: Manages LiteRT-LM initialization and multimodal inference flows.
2.  **Domain/Use Case Layer (`com.calendaradd.usecase`):** 
    - `CalendarUseCase`: Orchestrates the transition from multimodal analysis to Room and System Calendar.
3.  **Service Layer (`com.calendaradd.service`):** 
    - `GemmaLlmService`: High-performance wrapper for LiteRT-LM. Handles NPU initialization.
    - `TextAnalysisService`: Orchestrator that formats prompts and parses JSON outputs.
    - `SystemCalendarService`: Native Android Calendar integration.

---

## Building and Running

### Prerequisites
- Android Studio (2026 version).
- Device with NPU (Pixel 8+, Galaxy S24+, or 2026-era MediaTek/Qualcomm chips).
- **Model:** Gemma 4 E2B/E4B in `.litertlm` format.

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
- **Native Multimodality:** Use `Content.builder()` to combine text, bitmaps, and audio bytes.
- **Local-Only:** No data leaves the device. LiteRT-LM ensures all processing is on-device.
- **Performance:** **NPU-First Strategy.** The app attempts to use `Backend.NPU()` for inference to save battery and reduce latency, with an automatic fallback to `Backend.CPU()` if hardware acceleration is unavailable.
- **Java Requirement:** Due to LiteRT-LM 0.10.0+ requirements, the project must be compiled with **Java 21**.

### Structured Data
- **JSON extraction:** Gemma 4 is prompted to return structured JSON. The extraction pipeline is hardened to strip markdown prose and handle malformed outputs.
- **Time Handling:** ISO-8601 for AI exchange, `Long` (milliseconds) for database and calendar.

---

## Key Files
- `GemmaLlmService.kt`: LiteRT-LM engine management.
- `TextAnalysisService.kt`: Multimodal pipeline logic.
- `HomeViewModel.kt`: App lifecycle and AI state.
