# Calendar Add AI - V1 Development Plan

## Overview

Build a stable, production-ready Android app for creating calendar events via local AI (Gemma4). Features easy importing of events from text, audio, and images.

---

## Goals

| Goal | Description | Priority |
|------|-------------|----------|
| Privacy-first | All AI processing happens locally on device | P0 |
| Easy import | One-tap event creation from any input | P0 |
| Offline capable | Works without internet | P0 |
| Stable release | 95% test coverage, crash-free rate > 99% | P0 |
| Version control | Semantic versioning, changelogs | P0 |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    MainActivity                           │
│              (Jetpack Compose UI Layer)                   │
└─────────────────────────┬─────────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
┌───────────────┐  ┌──────────────┐  ┌──────────────┐
│  Input Screen │  │  Event List  │  │ Settings     │
│  (Text/Audio/ │  │              │  │              │
│   Image)      │  │              │  │              │
└───────────────┘  └──────────────┘  └──────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                   Use Case Layer                         │
│         (CalendarUseCase - Business Logic)               │
└─────────────────────────┬─────────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
┌───────────────┐  ┌──────────────┐  ┌──────────────┐
│ TextAnalysis  │  │   Event DB   │  │   Storage    │
│   Service     │  │      (Room)  │  │    Manager   │
│  (LLM Engine) │  │              │  │              │
└───────────────┘  └──────────────┘  └──────────────┘
```

---

## Phase 1: Project Foundation (Week 1-2)

### Tasks

- [x] Initialize Git repository
- [ ] Set up Gradle build configuration
- [ ] Configure Android SDK minimum/target SDK (minSdk 26, targetSdk 34)
- [ ] Set up CI/CD pipeline (GitHub Actions or GitLab CI)
- [ ] Configure version catalog (`libs.versions.toml`)
- [ ] Set up local development environment guide

### Deliverables

- Working Gradle build
- `.gitignore` with Android-specific patterns
- Base UI theme (Material 3)
- CI pipeline (build + test)

### Acceptance Criteria

- ✅ `./gradlew build` succeeds on clean slate
- ✅ CI builds pass on PR to main branch
- ✅ App builds on Android 6.0+ (minSdk 26)

---

## Phase 2: Core UI & Navigation (Week 2-3) - IN PROGRESS

### Tasks

- [x] Implement main screen navigation (Jetpack Compose Navigation)
- [x] Create home screen with input selection
- [ ] Implement event list with search/filter
- [x] Create event detail view with edit mode
- [ ] Add onboarding/tutorial flow
- [x] Implement Material 3 theming (light/dark)

### Deliverables

- Complete navigation flow ✅
- All core screens implemented (Home, Event List, Event Detail, Settings) ✅
- Material 3 theming in place ✅
- Navigation bar component ✅

### Completed Components

1. **AppNavGraph.kt** - Navigation graph with all routes
2. **NavigationBar.kt** - Animated bottom navigation
3. **CalendarHomeScreen.kt** - Main screen with FAB and input options
4. **CalendarEventListScreen.kt** - Event list with empty state
5. **CalendarEventDetailScreen.kt** - Event details with edit mode
6. **CalendarSettingsScreen.kt** - Settings screen with privacy controls
7. **NavColors.kt** - Navigation bar theming

### Acceptance Criteria

- ✅ App navigates between screens smoothly
- ✅ Dark mode works correctly  
- ✅ Accessibility labels present on all elements
- ✅ All screens use Material 3 components
- ✅ Navigation transitions are smooth

### Next Steps

1. Add search/filter to event list
2. Implement file/audio/image input handling
3. Create onboarding flow

---

*Last updated: 2026-04-18*

---

## Phase 3: Event Database Layer (Week 3-4) - COMPLETED

### Tasks

- [x] Setup Room database
- [x] Create Event entity (id, title, description, startTime, endTime, location, attendees, notes)
- [x] Implement DAOs (insert, getAll, getUpcoming, delete)
- [x] Create migration strategies for future versions
- [x] Add database constraints (NOT NULL, INDEX on datetime)

### Deliverables

- Working Room database ✅
- Type-safe DAOs with Kotlin Flow support ✅
- Type converters for Date/Time strings ✅
- Migration strategies documented ✅

### Completed Components

1. **Event.kt** - Room entity with all fields
2. **EventDao.kt** - CRUD operations and query helpers
3. **EventDatabase.kt** - Room database with type converters

### Acceptance Criteria

- ✅ Events persist after app restart
- ✅ Database migrations ready for testing
- ✅ No data loss on version upgrades planned

### Next Steps

1. Add search/filter to event list
2. Implement file/audio/image input handling
3. Create onboarding flow
4. Start Phase 4: Local LLM Integration

---

## Phase 4: Local LLM Integration (Week 4-6)

### Tasks

- [x] Create LlmEngine class for model loading
- [ ] Download and convert Gemma4 model to TF Lite format
- [ ] Implement model loading in `app/src/main/assets/`
- [ ] Create model inference wrapper
- [ ] Handle model initialization asynchronously
- [ ] Implement fallback when model fails to load
- [ ] Add model version checking

### Deliverables

- LlmEngine class for model management
- Model loading and inference pipeline
- Fallback handling for offline scenarios

### Acceptance Criteria

- Model loads within 10s on modern devices
- Inference completes in < 5s
- Graceful degradation without model

### Next Steps

1. Download Gemma4 TF Lite model
2. Implement model inference
3. Add model version tracking

---

## Phase 4: Local LLM Integration (Week 4-6)

### Tasks

- [ ] Research and select Gemma4 compatible TF Lite model
- [ ] Download and convert Gemma4 model to TF Lite format
- [ ] Implement model loading in `app/src/main/assets/`
- [ ] Create model inference wrapper
- [ ] Handle model initialization asynchronously
- [ ] Implement fallback when model fails to load
- [ ] Add model version checking

### Model Selection

| Model | Size | Latency | Notes |
|-------|------|---------|-------|
| Gemma4-2B | ~1.5GB | ~500ms | Good balance |
| Gemma4-7B | ~4GB | ~1-2s | Better accuracy |
| Gemma4-9B | ~5GB | ~2s | Best accuracy |

**Recommendation**: Start with Gemma4-2B, allow upgrade to larger models if space permits.

### Deliverables

- Model loading working on device
- Inference wrapper for event extraction
- Model management utilities

### Acceptance Criteria

- Model loads within 10s of app start
- Inference completes in < 5s on mid-range devices
- Graceful degradation if model unavailable

---

## Phase 5: Event Extraction Logic (Week 5-7)

### Tasks

- [ ] Implement prompt engineering for event extraction
- [ ] Create extraction pipeline:
  - Input text → LLM analysis → structured output
  - Audio → transcription → analysis → event
  - Image → OCR → analysis → event
- [ ] Handle ambiguous inputs with clarification flow
- [ ] Add timezone detection and conversion
- [ ] Validate extracted event data

### Prompt Template

```
Extract calendar event from this input:
- title (optional)
- date
- time
- duration (default 60min)
- location (optional)
- attendees (list, optional)
- description (optional)

Input: "{user_input}"

Output in JSON format with extracted fields, or null if no valid event.
```

### Deliverables

- Working extraction for text input
- Audio-to-event pipeline
- Image-to-event pipeline
- Validation layer

### Acceptance Criteria

- 80%+ of test inputs produce valid events
- Fallback prompts reduce error rate
- Timezone handling works correctly

---

## Phase 6: File & Link Import (Week 6-8)

### Tasks

- [ ] Implement file sharing intent (Files app integration)
- [ ] Handle Google Drive, Dropbox, Nextcloud links
- [ ] Support PDF, DOCX, TXT file imports
- [ ] Create web clipper/shortcut
- [ ] Implement link preview for URL sharing

### Deliverables

- File picker integration
- URL handler
- Share sheet actions

### Acceptance Criteria

- Can import from Files app
- Can handle email/clipboard share
- URL links preview before import

---

## Phase 7: Permissions & Privacy (Week 7-8)

### Tasks

- [ ] Implement runtime permission requests
- [ ] Request storage permission for file import
- [ ] Create privacy policy (required for Play Store)
- [ ] Add privacy dashboard explaining data usage
- [ ] Implement data export feature

### Deliverables

- Proper permission flow
- Privacy policy document
- Data export functionality

### Acceptance Criteria

- All permissions requested at first use
- Privacy policy easily accessible
- Users can export all their data

---

## Phase 8: Testing (Week 8-10)

### Unit Tests

- [ ] 80%+ code coverage
- [ ] EventUseCase tests
- [ ] Database CRUD operations
- [ ] UI component tests
- [ ] LLM inference mock tests

### Integration Tests

- [ ] Full event creation flow
- [ ] Database migration tests
- [ ] File import flow tests

### Instrumentation Tests

- [ ] Home screen tests
- [ ] Event creation flow
- [ ] Search/filter tests
- [ ] Crash testing

### Deliverables

- Unit tests (>80% coverage)
- Integration test suite
- Instrumentation tests for E2E flow

### Acceptance Criteria

- All tests pass in CI
- Coverage report meets threshold
- No critical bugs in release build

---

## Phase 9: Versioning & Release Preparation (Week 9-10)

### Semantic Versioning

- Format: `MAJOR.MINOR.PATCH` (e.g., `1.0.0`)
- BREAKING CHANGES → MAJOR version bump
- New features → MINOR version bump
- Bug fixes → PATCH version bump

### Tasks

- [ ] Implement version tracking in manifest
- [ ] Create changelog system (keep-a-changelog)
- [ ] Add proguard rules for release
- [ ] Configure release signing
- [ ] Set up version code/increment logic

### Deliverables

- Semantic versioning in place
- Changelog auto-generated from commits
- Release build artifacts

### Acceptance Criteria

- Version increments on each release
- Changelog updated
- Release builds signed

---

## Phase 10: Documentation (Week 9-11)

### Tasks

- [ ] User guide (video + text)
- [ ] Developer setup guide
- [ ] API documentation (if exposed)
- [ ] Troubleshooting guide
- [ ] Privacy policy and terms
- [ ] Update README.md with all sections

### Deliverables

- Complete documentation suite
- Video tutorials
- API docs (if applicable)

### Acceptance Criteria

- New developer can setup in < 30min
- User can create first event in < 2min

---

## Phase 11: Polish & Optimization (Week 10-12)

### Tasks

- [ ] Performance profiling (memory, startup time)
- [ ] Optimize model loading (eager vs lazy)
- [ ] Reduce APK size (proguard, remove unused deps)
- [ ] Fix bugs from testing phase
- [ ] UI/UX polish (animations, haptics)
- [ ] Accessibility audit

### Deliverables

- Optimized release build
- Bug fixes merged
- Accessibility improvements

### Acceptance Criteria

- Cold start < 3s
- Memory usage < 50% on low-end devices
- All accessibility issues resolved

---

## Phase 12: Beta & Release (Week 12+)

### Tasks

- [ ] Create internal test build
- [ ] Distribute to beta testers (TestFlight, Google Play Console Internal)
- [ ] Gather and address feedback
- [ ] Final QA pass
- [ ] Submit to Google Play Store
- [ ] Monitor crash reports (Firebase Crashlytics)

### Deliverables

- Beta release
- Play Store submission
- Crash monitoring active

### Acceptance Criteria

- Beta crash rate < 1%
- Beta feedback addressed
- Play Store approval received

---

## Git Workflow

### Branching Strategy

```
main          → Production, tagged releases
  └── release/* → Release candidates
  └── hotfix/* → Critical bug fixes

develop       → Integration branch for new features
  └── feature/* → Feature branches
  └── issue-*/* → Issue-specific branches
```

### Pre-commit Hooks

- Commit message validation (conventional commits)
- ktlint formatting check
- Test execution on commit

### Commit Convention

```
feat: Add audio import capability
fix: Resolve crash on empty event list
docs: Update privacy policy
perf: Optimize model loading
chore: Update dependencies
refactor: Clean up use case layer
test: Add instrumentation tests
ci: Update GitHub Actions
```

---

## Key Metrics (V1 Success Criteria)

| Metric | Target |
|--------|--------|
| Crash-free sessions | > 99% |
| Test coverage | > 80% |
| Cold start time | < 3s |
| Event extraction accuracy | > 80% |
| APK size (release) | < 50MB |
| Build time | < 5min on dev machine |

---

## Dependencies

- Android Gradle Plugin: 8.2.0
- Kotlin: 1.9.0
- Compose BOM: 2024.04.00
- TensorFlow Lite: 2.13.0
- Room: 2.6.1
- Navigation Compose: 2.8.0

---

## Timeline Summary

| Phase | Duration | Status |
|-------|----------|--------|
| Foundation | W1-2 | 🟢 |
| Core UI | W2-3 | ⬜ |
| Database | W3-4 | ⬜ |
| LLM Integration | W4-6 | ⬜ |
| Event Extraction | W5-7 | ⬜ |
| File Import | W6-8 | ⬜ |
| Permissions/Privacy | W7-8 | ⬜ |
| Testing | W8-10 | ⬜ |
| Versioning/Release | W9-10 | ⬜ |
| Documentation | W9-11 | ⬜ |
| Polish | W10-12 | ⬜ |
| Beta/Release | W12+ | ⬜ |

---

## Next Actions

1. ✅ Update `local.properties` with Android SDK path
2. ✅ Run `./gradlew tasks` to verify build
3. ✅ Commit all changes to git
4. ✅ Create GitHub repository with this plan
5. 🟡 Start Phase 2: Core UI implementation
6. 🟡 Update development plan as we progress

---

*This plan is living documentation. Update it as the project evolves.*

## Current Status

### ✅ Completed

- Project foundation (Phase 1)
- CI/CD pipeline setup (GitHub Actions)
- Documentation framework
- Branch structure (main/develop)
- Code style configuration (.editorconfig)
- All documentation files created

### 🟡 In Progress

- Phase 2: Core UI implementation
- Phase 3: Database layer

---

*Last updated: 2026-04-18*
