<!-- Approved specification of phase A (29/10/2026), kept for provenance only.
     The implementation shipped as 0.5.0, so the versionCode/versionName clause of AC1
     and the "unchanged" assertions below are historical and no longer hold.
     The requirements themselves are left as approved. -->
---
name: "phase-a-build-catalogue"
version: "1.0"
author: "adversarial-spec"
status: "draft"
tags: [adversarial, spec, build, model-catalog, android]
targets:
  - file: app/build.gradle.kts
    description: "compileSdk 35→36 (line 21) and targetSdk 35→36 (line 26); restrict the published release APK's native ABIs to arm64-v8a and add an opt-in way to build an x86_64-only emulator artifact, without product flavors."
  - file: app/src/main/java/com/calendaradd/service/LiteRtModelCatalog.kt
    description: "Delete the gemma-3n-e2b (lines 120-136) and gemma-3n-e4b (lines 137-153) entries; DEFAULT_MODEL_ID (line 66) becomes gemma-4-e4b; expose a memory-aware initial-selection rule (E4B at >=12 GB, E2B below)."
  - file: app/src/main/java/com/calendaradd/usecase/PreferencesManager.kt
    description: "selectedModelId getter (lines 58-60) no longer returns a bare constant when nothing valid is stored: it resolves a device-appropriate first selection (or preserves an upgrading install's effective E2B), persists it once, and never overwrites a valid stored choice."
  - file: app/src/main/java/com/calendaradd/service/ModelDownloadManager.kt
    description: "Keep leftover Gemma 3n model files (gemma-3n-E2B-it-int4.litertlm, gemma-3n-E4B-it-int4.litertlm) in the set of managed files that startup cleanup may delete (cleanupUnusedModelFiles, lines 162-190; LEGACY_MODEL_FILE_NAMES, lines 71-75)."
  - file: app/src/test/java/com/calendaradd/service/LiteRtModelCatalogTest.kt
    description: "Update default-model test (lines 10-14) to E4B; remove Gemma 3n assertions (lines 16-29, 79-80, 87-88, 101-108, 139-152); expected asset table has exactly 4 entries; add tests for the memory-aware initial selection."
  - file: app/src/test/java/com/calendaradd/service/ModelDownloadManagerTest.kt
    description: "Replace find(\"gemma-3n-e2b\") at lines 105 and 405 with a distinct, still-existing catalog model so the tests keep exercising distinct files; add a test that a leftover Gemma 3n file is deleted by cleanup."
  - file: app/src/test/java/com/calendaradd/service/GemmaLlmServiceTest.kt
    description: "Remove or retarget `initialize should use Gallery backend order for Gemma 3n` (lines 283-302), which loads find(\"gemma-3n-e2b\")."
  - file: app/src/test/java/com/calendaradd/usecase/PreferencesManagerModelSelectionTest.kt
    description: "New JVM test class covering first selection by device memory, never-overwrite of a valid stored choice, stale Gemma 3n ids, and upgrade preservation of an existing E2B install."
  - file: README.md
    description: "Model table (lines 60-66) lists exactly the 4 remaining models with Gemma 4 E4B as default and the memory-based first selection; drop the Gemma 3n backend note (line 171); fix the 'default Gemma 4 E2B profile' wording (line 72); document the arm64-only release APK and the x86_64 emulator build command."
  - file: docs/MODEL_INTEGRATION.md
    description: "'Available Models' table (lines 27-34) lists exactly the 4 remaining models; remove Gemma 3n backend text (line 81 and the Gemma 3n sentence of line 89); state E4B default and the first-selection rule."
---

# Phase A — Build target API 36, arm64-only release, and Gemma 4 E4B default catalogue

READ: /home/chpo/.hermes/cache/scratch/adversarial-spec-write-rxyifrui/brief.md

## Problem

Three independent defects in the current build/catalogue configuration (HEAD
`f39fcd4`, versionCode 13 / versionName 0.4.1, `app/build.gradle.kts:27-28`):

1. **Outdated API target.** `compileSdk = 35` (`app/build.gradle.kts:21`) and
   `targetSdk = 35` (`app/build.gradle.kts:26`). Platform `android-36` and
   build-tools 36.0.0 are installed locally (verified in the brief), so the
   project can move to API 36 while staying an offline build.

2. **Published APK installs where inference is impossible.** No ABI
   restriction exists in `app/build.gradle.kts` (no `abiFilters`/`splits`/`ndk`
   block). The LiteRT-LM engine (`liblitertlm_jni.so`, `libLiteRt.so`,
   `libLiteRtClGlAccelerator.so`) ships only for `arm64-v8a` and `x86_64`, but
   Compose's graphics library contributes `armeabi-v7a` and `x86` libraries.
   The 60 076 462-byte release APK therefore declares 32-bit ABIs and installs
   on 32-bit processes where the model can never run.

3. **Catalogue contains dead entries and the wrong default.**
   `LiteRtModelCatalog.models` (`service/LiteRtModelCatalog.kt:68-170`) has six
   entries. The two Gemma 3n entries (`:120-136`, `:137-153`) point at gated
   `google/gemma-3n-*` repos whose pinned URLs return HTTP 401 anonymously,
   while `DownloadManager` sends no credentials — they can never download.
   The default is `gemma-4-e2b` (`:66`); the owner wants Gemma 4 E4B. But the
   default is a bare constant used by `PreferencesManager.selectedModelId`
   (`usecase/PreferencesManager.kt:58-60`) and `LiteRtModelCatalog.find()`
   (`:172-174`), while the device-memory guard
   (`service/GemmaBackendConfig.kt:121-143`, reading `advertisedMem` with
   `totalMem` fallback) only produces an error at initialization time
   (`service/GemmaLlmService.kt:119-120`). Simply flipping the constant would
   make every device below 12 GB start a 3.65 GB E4B download
   (`sizeBytes = 3_654_467_584L`, `LiteRtModelCatalog.kt:111`) that the guard
   then refuses to run (`minimumDeviceMemoryGb = 12`, `:118`).

Secondary risks created by removing entries, which this spec must close:

- `find()` silently falls back to the default for unknown ids (`:173`), so a
  user whose stored selection is `gemma-3n-*` would be silently moved.
- Startup cleanup only deletes files whose names are in the catalogue or
  `LEGACY_MODEL_FILE_NAMES` (`service/ModelDownloadManager.kt:71-75`,
  `:171-173`). Removing the Gemma 3n entries would orphan up to
  3.66 GB / 4.92 GB files (`LiteRtModelCatalog.kt:128`, `:145`) forever.
- Existing installs that never wrote `selected_model_id` currently resolve to
  E2B implicitly (`PreferencesManager.kt:59`). If they silently re-resolve to
  E4B, startup cleanup (`ModelDownloadManager.kt:162-190`, which keeps only the
  selected/queued/active files) would delete their verified 2.58 GB E2B file
  and force a 3.65 GB download — an irreversible loss for the user.
- Pending WorkManager jobs are tagged with a model id; the scheduler drops a
  job whose id is not in the catalogue (`service/BackgroundAnalysisScheduler.kt:329-333`)
  and the worker resolves the id through `find()`
  (`service/BackgroundAnalysisWorker.kt:102`).

### Assumptions (documented, not in the brief)

- "Published artifact" = the APK produced by `assembleRelease`. Debug builds
  are not published and may keep the engine ABIs (`arm64-v8a`, `x86_64`) so
  `installDebug` keeps working on emulators; they must never contain 32-bit ABIs either.
- "Upgrading install" is identified by on-device state that already exists
  (no stored `selected_model_id` and the Gemma 4 E2B model file present in the
  model storage root). No new persisted flag is required by this spec, but the
  builder may add one.
- The memory figure used for the first selection is the same figure the
  existing guard uses (`Context.deviceMemoryGb()`, `GemmaBackendConfig.kt:131-143`),
  so selection and guard can never disagree. If that figure is unavailable
  (`null`), the first selection is Gemma 4 E2B (the conservative choice whose
  guard is 8 GB).
- `scripts/check_model_catalog_hashes.sh:14` and `.github/workflows/android.yml:47`
  mention gated `google/gemma-3n-*` repos in comments/error text only; they
  read the catalogue source dynamically and need no change for correctness.
  They are left untouched (CI is out of scope).
- Hard constraints from the brief: no product flavors; no change to
  `UpdateCheckerService`, `ApkDownloadManager`, `ApkInstaller`,
  `InstallStatusReceiver`, `AndroidManifest.xml`, R8/ProGuard rules,
  `litertlm-android` (stays 0.12.0), `versionCode`/`versionName`; no new
  downloadable model.

## Requirements

- R1: The app compiles against and targets API 36 (`compileSdk = 36`, `targetSdk = 36`), and the full offline build and JVM unit-test suite succeed with no new failures.
- R2: The release APK (the published artifact) contains native libraries for `arm64-v8a` only; no published artifact contains `armeabi-v7a`, `x86`, or `x86_64` libraries. A separate, explicitly opted-in build command produces an x86_64 emulator APK containing `x86_64` libraries and no 32-bit ABI, without changing what the default release command produces and without introducing product flavors.
- R3: The model catalogue contains exactly four entries — `gemma-4-e2b`, `gemma-4-e2b-compact`, `gemma-4-e4b`, `qwen-3_5-0_8b` — with their existing pinned URLs, file names, sizes, SHA-256 digests and runtime parameters unchanged; no Gemma 3n catalogue id or `huggingface.co/google/gemma-3n-*` URL remains in production code.
- R4: The catalogue default is Gemma 4 E4B, and when no valid model choice is stored the app makes a device-memory-aware first selection: `gemma-4-e4b` when the device reports ≥ 12 GB, otherwise `gemma-4-e2b`; `gemma-4-e2b-compact` and `qwen-3_5-0_8b` are never chosen automatically. The first selection is persisted once so later reads are stable.
- R5: A stored selection that names an existing catalogue entry is never changed by the app (only by the user via Settings, which keeps working for all four entries). A stored selection naming a removed id (`gemma-3n-e2b`, `gemma-3n-e4b`) is treated as "no valid choice" and resolved per R4. An upgrading install with no stored selection whose Gemma 4 E2B model file is already present keeps `gemma-4-e2b` (no re-download, no deletion of that file).
- R6: Leftover Gemma 3n model files on disk (`gemma-3n-E2B-it-int4.litertlm`, `gemma-3n-E4B-it-int4.litertlm`) are deleted by the existing unused-model cleanup unless an active download targets them; a pending background job tagged with a removed model id never triggers a Gemma 3n download and does not crash the app or worker.
- R7: The existing JVM tests that assert the six-entry catalogue, the E2B default, or use Gemma 3n ids are updated to the new catalogue (not skipped, `@Ignore`d, or deleted without a replacement assertion), new tests cover R4–R6, and `./gradlew --offline :app:testDebugUnitTest` finishes with 0 failures.
- R8: `README.md` (model section) and `docs/MODEL_INTEGRATION.md` ("Available Models") describe exactly the four catalogue entries, E4B as default with the memory-based first selection, and README documents the arm64-only release APK plus the x86_64 emulator build command; `scripts/check_release_docs.sh` still passes.

## Acceptance criteria

- AC1 (R1): `app/build.gradle.kts` contains `compileSdk = 36` and `targetSdk = 36`, and `minSdk = 31`, `versionCode = 13`, `versionName = "0.4.1"` are unchanged (`git diff` shows no change to those three lines).
- AC2 (R1): `./gradlew --offline :app:assembleDebug :app:assembleRelease` exits 0 on the dev machine; `aapt2 dump badging` (build-tools 36.0.0) on the release APK reports `targetSdkVersion:'36'`.
- AC3 (R2): `unzip -Z1 app/build/outputs/apk/release/app-release.apk | grep -oE '^lib/[^/]+/' | sort -u` (entry names only, no size/timestamp columns, so each ABI directory appears once) yields exactly one line, `lib/arm64-v8a/`, `lib/arm64-v8a/`, and that directory contains `liblitertlm_jni.so`, `libLiteRt.so` and `libLiteRtClGlAccelerator.so` (3 named files present).
- AC4 (R2): The release APK is strictly smaller than the pre-change 60 076 462 bytes.
- AC5 (R2): The x86_64 emulator command documented in README, run offline, produces an APK for which the AC3 command (`unzip -Z1 <apk> | grep -oE '^lib/[^/]+/' | sort -u`) yields exactly one line, `lib/x86_64/`, and that directory includes `liblitertlm_jni.so`; running the default `assembleRelease` afterwards (without the opt-in) again satisfies AC3.
- AC6 (R2): `app/build.gradle.kts` declares no `productFlavors` / `flavorDimensions`, and `git diff --stat` shows no change to `AndroidManifest.xml`, `proguard-rules.pro`, `gradle/libs.versions.toml` (litertlm stays 0.12.0), or any of `UpdateCheckerService.kt`, `ApkDownloadManager.kt`, `ApkInstaller.kt`, `InstallStatusReceiver.kt`.
- AC7 (R3): A JVM test asserts `LiteRtModelCatalog.models.map { it.id }` equals exactly `["gemma-4-e2b", "gemma-4-e2b-compact", "gemma-4-e4b", "qwen-3_5-0_8b"]` (size 4) and the pinned-asset table test (`LiteRtModelCatalogTest.kt:111-176`) contains exactly those 4 ids with the unchanged revisions (`7fa1d784…`, `9695417f…`, `766ec4af…`), sizes (2 583 085 056 / 3 654 467 584 / 1 159 757 824 bytes) and SHA-256 digests currently at `LiteRtModelCatalog.kt:78`, `:95`, `:112`, `:163`.
- AC8 (R3): `grep -rnE 'gemma-3n-e[24]b|huggingface.co/google/gemma-3n' app/src/main` returns no match (0 lines).
- AC9 (R3): For each of the 4 remaining entries, an anonymous request (no credentials, no auth header or cookie) to its `downloadUrl` that follows all redirects — e.g. `curl -sIL -o /dev/null -w '%{http_code}' <downloadUrl>` — ends with final status exactly `200` (manual check, recorded in the PR description: 4 final status codes, all equal to 200; any 3xx-terminal, 401, 403, 404 or 5xx fails).
- AC10 (R4): `LiteRtModelCatalog.DEFAULT_MODEL_ID == "gemma-4-e4b"` and `LiteRtModelCatalog.find(null).id == "gemma-4-e4b"`.
- AC11 (R4): With empty preferences and no model file present, the first read of the selected model returns `gemma-4-e4b` for a reported device memory of 12.0 GB and of 16.0 GB, and `gemma-4-e2b` for 11.99 GB, 8.0 GB, 6.0 GB, 4.0 GB and for an unavailable (`null`) figure — 7 parameterized cases, none returning `gemma-4-e2b-compact` or `qwen-3_5-0_8b`.
- AC12 (R4): After the first read in AC11, the stored `selected_model_id` equals the returned id, and a second read with a different reported memory (e.g. 16.0 GB after a first read at 8.0 GB) returns the same id as the first read.
- AC13 (R5): For each of the 4 valid ids pre-stored in preferences, reading the selection with reported memory 4.0 GB and with 16.0 GB returns the stored id unchanged and leaves the stored value unchanged (8 cases).
- AC14 (R5): With `selected_model_id = "gemma-3n-e2b"` (and separately `"gemma-3n-e4b"`) stored, reading the selection returns `gemma-4-e4b` at 16.0 GB and `gemma-4-e2b` at 8.0 GB, and the stored value is replaced by the returned id (4 cases).
- AC15 (R5): With empty preferences, reported memory 16.0 GB, and a file named `gemma-4-E2B-it.litertlm` present in the model storage root, the selection resolves to `gemma-4-e2b`; a subsequent `ModelDownloadManager.cleanupUnusedModelFiles()` leaves that file on disk.
- AC16 (R5): In Settings, selecting each of the 4 models via `SettingsViewModel` (`ui/SettingsViewModel.kt:161-162`) persists that id; a JVM test covers at least `gemma-4-e2b-compact` being persisted on a 16.0 GB device and returned unchanged on the next read.
- AC17 (R6): A `ModelDownloadManagerTest` case creates `gemma-3n-E2B-it-int4.litertlm` and `gemma-3n-E4B-it-int4.litertlm` in the model dir, runs `cleanupUnusedModelFiles()` with no active download targeting them, and asserts both files no longer exist (2 deletions) while the selected model's file still exists.
- AC18 (R6): A `ModelDownloadManagerTest` case with an active download whose target is `gemma-3n-E4B-it-int4.litertlm` asserts that file still exists after cleanup.
- AC19 (R6): A JVM test feeding the scheduler/worker a pending job tagged with `gemma-3n-e2b` asserts no exception is thrown and no download is enqueued for any file name starting with `gemma-3n`.
- AC20 (R7): `./gradlew --offline :app:testDebugUnitTest` exits 0 with 0 failures, and the executed test count is ≥ 269 minus the number of test methods deleted in this change plus the number added (the PR description states both numbers).
- AC21 (R7): `git diff` on `app/src/test` adds no `@Ignore`, and every test listed in the Test failure allowlist below is either rewritten against the 4-entry catalogue or deleted with its covered behavior re-asserted by a test named in AC7, AC10, AC17 or AC19.
- AC22 (R7): `grep -rnE 'gemma-3n-e[24]b' app/src/test` matches only tests whose purpose is the removed-id behavior of AC14 and AC19 (no test calls `LiteRtModelCatalog.find("gemma-3n-…")` expecting a Gemma 3n config).
- AC23 (R8): The model table in `README.md` and the "Available Models" table in `docs/MODEL_INTEGRATION.md` each contain exactly 4 model rows (Gemma 4 E2B, Gemma 4 E2B Compact, Gemma 4 E4B, Qwen 3.5 0.8B LiteRT); `grep -n 'Gemma 3n' README.md docs/MODEL_INTEGRATION.md` returns 0 lines; README marks Gemma 4 E4B (not E2B) as default and states the 12 GB threshold for the first selection.
- AC24 (R8): README contains the x86_64 emulator build command used in AC5 and states that the published APK is arm64-v8a only; `scripts/check_release_docs.sh` exits 0.

## Test failure allowlist

These existing tests assert behavior that R3/R4 intentionally change. They must be rewritten per R7/AC21, not skipped:

- `app/src/test/java/com/calendaradd/service/LiteRtModelCatalogTest.kt::default model is Gemma 4 E2B` — asserts `DEFAULT_MODEL_ID == "gemma-4-e2b"`; contradicts R4 (default is E4B).
- `app/src/test/java/com/calendaradd/service/LiteRtModelCatalogTest.kt::Gemma 3n supports text image and audio` — asserts Gemma 3n display names; contradicts R3 (entries removed).
- `app/src/test/java/com/calendaradd/service/LiteRtModelCatalogTest.kt::Gemma models use AI Edge Gallery token windows` — asserts 4096 tokens for Gemma 3n via `find()`, which now falls back to E4B (4000); contradicts R3.
- `app/src/test/java/com/calendaradd/service/LiteRtModelCatalogTest.kt::Gemma models use AI Edge Gallery backend orders and memory minimums` — asserts Gemma 3n sizes 3 655 827 456 / 4 919 541 760 bytes; contradicts R3.
- `app/src/test/java/com/calendaradd/service/LiteRtModelCatalogTest.kt::every catalog entry is pinned to its exact HuggingFace asset` — expects 6 entries; contradicts R3 (4 entries).
- `app/src/test/java/com/calendaradd/service/ModelDownloadManagerTest.kt::cleanupUnusedModelFiles should keep every queued model file` — `find("gemma-3n-e2b")` now resolves to `gemma-4-e4b`, colliding with the queued model's file, so `assertFalse(otherFile.exists())` fails; contradicts R3.
- `app/src/test/java/com/calendaradd/service/ModelDownloadManagerTest.kt::AC1 startup keep-set preserves selected queued and active-download and removes only unused` — `find("gemma-3n-e2b")` now collides with the queued `gemma-4-e4b` file; contradicts R3.
- `app/src/test/java/com/calendaradd/service/GemmaLlmServiceTest.kt::initialize should use Gallery backend order for Gemma 3n` — loads `find("gemma-3n-e2b")`, which no longer exists; contradicts R3.

## Caller Enumeration

API surface whose semantics change: `LiteRtModelCatalog.DEFAULT_MODEL_ID` (value), `LiteRtModelCatalog.models` (contents), `LiteRtModelCatalog.find()` (fallback target), `PreferencesManager.selectedModelId` getter (memory-aware first selection, persisted). Search method: `grep -rn "selectedModelId\|DEFAULT_MODEL_ID\|LiteRtModelCatalog\.\(find\|models\)" app/src` at `f39fcd4`. Blind spot: reflection/dynamic access (none known).

| File | Function/Method | Migration Note |
|------|----------------|----------------|
| app/src/main/java/com/calendaradd/usecase/PreferencesManager.kt:58-60 | `selectedModelId` getter | Implements R4/R5 resolution instead of returning the bare constant. |
| app/src/main/java/com/calendaradd/service/LiteRtModelCatalog.kt:172-174 | `find()` | Fallback now yields E4B; no signature change. |
| app/src/main/java/com/calendaradd/ui/SettingsViewModel.kt:45,48,161 | `availableModels`, `selectedModelId`, model selection | Shows 4 models; initial value comes from the resolved selection; no change expected. |
| app/src/main/java/com/calendaradd/service/ModelDownloadManager.kt:112 | `getSelectedModel()` | Reads resolved selection; no change expected. |
| app/src/main/java/com/calendaradd/service/ModelDownloadManager.kt:171,423 | `cleanupUnusedModelFiles()`, download matching | Must still treat Gemma 3n file names as managed (R6). |
| app/src/main/java/com/calendaradd/service/BackgroundAnalysisScheduler.kt:203,210,329 | `getPendingModels()`, `reEnqueueSurvivor()` | Removed ids map to `null` and are dropped/logged; verify per AC19. |
| app/src/main/java/com/calendaradd/service/BackgroundAnalysisWorker.kt:102 | `doWork()` | `find(modelId)` falls back for removed ids; must not download Gemma 3n (AC19). |
| app/src/main/java/com/calendaradd/MainActivity.kt:116 | active-download model lookup | Gemma 3n target file no longer maps to a model; must tolerate `null`. |
| app/src/main/java/com/calendaradd/ui/CalendarSettingsScreen.kt:77,157,176,347 | selection display | No change expected. |
| app/src/test/java/com/calendaradd/ui/HomeViewModelTest.kt:56 | `defaultModel()` | Now E4B; tests must still pass (AC20). |
| app/src/test/java/com/calendaradd/service/ModelDownloadManagerTest.kt:58,105,335,405 | test fixtures | Lines 105/405 retargeted (allowlist). |
| app/src/test/java/com/calendaradd/service/LiteRtModelCatalogTest.kt:12-152 | catalogue tests | Rewritten (allowlist). |
| app/src/test/java/com/calendaradd/service/GemmaLlmServiceTest.kt:296 | Gemma 3n backend test | Removed/retargeted (allowlist). |
