---
name: "calendar-add-release-blockers"
version: "1.0"
author: "adversarial-spec"
status: "draft"
tags: [adversarial, spec]
targets:
  - file: app/src/main/java/com/calendaradd/MainActivity.kt
    description: "F01: accept ACTION_SEND content only with a valid one-shot consent handoff token; route unverified external sends to the consent preview"
  - file: app/src/main/java/com/calendaradd/ShareImportActivity.kt
    description: "F01: attach a one-shot handoff token bound to the approved content when forwarding ACTION_SEND to MainActivity; the no-preview fallback path (~line 135) must terminate locally with an unsupported-content outcome instead of forwarding a credential-less intent"
  - file: app/src/main/java/com/calendaradd/util/ShareImportTokenStore.kt
    description: "F01: reuse the existing one-shot token store for the MainActivity handoff (extend only if its content-key binding is insufficient)"
  - file: app/src/main/java/com/calendaradd/service/BackgroundAnalysisScheduler.kt
    description: "F02: never delete the persisted input when the enqueue acknowledgment times out with unknown commit state; classify it for safe recovery instead"
  - file: app/src/main/java/com/calendaradd/service/SystemCalendarService.kt
    description: "F03/F04/F05/F08: crash-safe idempotent event insert (adopt-or-insert), write Attendees rows and a default Reminder row, support moving an event between calendars"
  - file: app/src/main/java/com/calendaradd/usecase/CalendarUseCase.kt
    description: "F03/F04/F05: pass attendees through sync, adopt an already-inserted provider event after a crash instead of re-inserting a duplicate"
  - file: app/src/main/java/com/calendaradd/ui/SettingsViewModel.kt
    description: "F08: changing the target calendar triggers migration of already-synced events to the newly selected calendar"
  - file: app/src/main/java/com/calendaradd/usecase/PreferencesManager.kt
    description: "F05: persist the user-configurable default reminder minutes (default 10) alongside existing preferences"
  - file: app/src/main/java/com/calendaradd/ui/CalendarSettingsScreen.kt
    description: "F05: expose the default reminder minutes setting in the existing settings UI"
  - file: app/src/main/java/com/calendaradd/util/ApkInstaller.kt
    description: "F07: guarantee the OS package-install confirmation UI on every supported API level (26-35) and surface the install session status"
  - file: app/src/main/java/com/calendaradd/service/GemmaLlmService.kt
    description: "F06: replace the thread-blocking ReentrantLock held across suspend calls with suspension-safe serialization; keep single-session semantics"
---

# Calendar Add — P1 release blockers and critical UX fixes (part 1 of 2)

## Problem

Two independent reviews of the calendar_add app found 3 P1 and 18 P2 defects.
This spec is part 1 of the fix campaign and covers the 3 P1 release blockers
(F01-F03) plus the 5 highest-impact P2 findings by user impact (F05, F07, F08,
F04, F06), matching the campaign brief's "3 P1 and 5 critical P2" scope. The
next 5 critical P2 findings (F09, F10, F15, F18, F19) are specified in
`spec-2.md`; the split exists because the spec cap is 8 non-trivial
requirements. Scope accounting: the reviews found 18 P2 findings in total and
these two specs cover 10 of them (F04-F10, F15, F18, F19). The remaining 8
P2 findings are lower-impact and explicitly deferred to a follow-up campaign;
they are out of scope for both this spec and `spec-2.md` and are not assumed
fixed by either.

The defects, verified against the current code:

- F01: `MainActivity.handleIntent` accepts `ACTION_SEND` extras from any
  sender without proof of consent. `CalendarHomeScreen` auto-starts analysis
  on shared content, so any third-party app can deliver an explicit
  `ACTION_SEND` intent to the exported `MainActivity` and drive on-device
  inference and calendar writes without the user ever seeing the consent
  preview that `ShareImportActivity` shows.
- F02: `BackgroundAnalysisScheduler.persistAndEnqueue` deletes the persisted
  input file when the enqueue acknowledgment times out. A timed-out
  observation does not mean the enqueue failed: the operation can still
  commit, leaving a runnable worker whose input file no longer exists —
  permanent loss of the user's shared content.
- F03: `CalendarUseCase.syncEventToSystem` inserts into CalendarProvider and
  only then persists the provider id in Room. A crash between the two leaves
  a local event with `systemCalendarEventId = null`; the next sync inserts a
  second, duplicate provider event.
- F04: extracted attendees are stored locally (Room `Event.attendees`) but
  never written to `CalendarContract.Attendees`.
- F05: no `CalendarContract.Reminders` row is ever created, so synced events
  produce zero notifications.
- F06: `GemmaLlmService` holds a `ReentrantLock` across suspending calls
  (`conversation.awaitResponse` runs inside `mutex.withLock` in a suspend
  function), pinning a `Dispatchers.IO` thread per in-flight request and
  risking deadlock with `close()`.
- F07: `ApkInstaller`'s PackageInstaller path sets user-action semantics only
  on API 31+ and its status PendingIntent targets the app's own activity; on
  the supported API range the OS install confirmation is never reliably
  presented.
- F08: changing the target calendar only writes a preference; events already
  synced to the previous calendar are never moved (and
  `SystemCalendarService.updateEvent` never writes `CALENDAR_ID`, so even a
  later edit syncs in place).

Cross-cutting constraint: each fix must keep
`./gradlew :app:testDebugUnitTest` green (227 tests currently pass) and
Android SDK API usage must be validated against AOSP 15 semantics (the
campaign's MCP `aosp-rag` server; unavailable at spec time, so validation is
a fix-phase obligation). No refactoring beyond each fix's scope; one
conventional commit per finding.

## Requirements

- R1: [F01] MainActivity must treat `ACTION_SEND` content as untrusted until
  a consent handoff is proven. Shared content may only be prefilled or
  analyzed when the delivery carries a valid, unconsumed one-shot handoff
  credential issued in-process by the app's own consent flow and bound to
  that exact content. Any other `ACTION_SEND` delivery to MainActivity
  (external explicit intent, forwarded intent without a valid credential, or
  a replayed credential) must not populate shared-content state, must not
  trigger analysis, and must be re-routed to the share-consent preview
  flow. Implicit share-sheet deliveries to `ShareImportActivity` (preview +
  explicit user tap) must keep working unchanged. In particular,
  `ShareImportActivity`'s existing no-preview fallback (its preview builder
  returns null for the delivered content, e.g. `text/plain` with a null
  `EXTRA_TEXT`; see `loadPreview`, ~line 133) must never forward a
  credential-less intent to MainActivity — under this requirement
  MainActivity would re-route such a delivery back to ShareImportActivity
  and loop forever. Unpreviewable content must terminate in the consent
  activity with a user-visible unsupported-content outcome: no forward to
  MainActivity, no prefill, no analysis, no re-delivery.
- R2: [F02] When the WorkManager enqueue acknowledgment does not complete
  within the observation window (current constant `WORK_MANAGER_DEADLINE_MS`
  = 10,000 ms) and the commit state is therefore unknown, the persisted
  input file must not be deleted. Recovery must either confirm the enqueue
  or leave the input classifiable by the existing reconciliation/grace
  rules; no state may exist where a committed worker references a deleted
  input file. A genuine enqueue failure (the operation completes
  exceptionally within the window) must still roll back its own input file.
- R3: [F03] Local-to-provider sync must be idempotent across process death.
  If the process dies after the provider insert but before the provider id is
  persisted locally, the next sync of that same local event must adopt the
  already-inserted provider event rather than inserting a second copy, using
  only public `CalendarContract` APIs (no account-level sync-adapter
  assumptions). A local event may end up linked to at most one provider
  event, no matter how many times sync is retried.
- R4: [F05] Every event synced to the system calendar must carry at least one
  native `CalendarContract.Reminders` row so the OS notifies the user. The
  default is 10 minutes before start with `METHOD_ALERT`; the value must be
  user-configurable from the app's settings UI, persisted across process
  restarts. Reminder rows must be maintained idempotently on
  re-sync/update (no accumulation of duplicate rows).
- R5: [F07] Initiating an in-app update must present the OS package-install
  confirmation to the user before installation can proceed, on every
  supported API level (26-35), and the app must surface the install session
  status (user accepted / aborted / failed). If a device flow cannot present
  the confirmation, the install attempt must fail with a user-actionable
  error rather than report success.
- R6: [F08] When the user changes the target calendar, every local event
  previously synced to the old calendar must be moved to the new one: after
  the change completes, each such event exists exactly once in the new
  calendar and zero times in the old calendar, and its local
  `systemCalendarEventId` link remains valid for the new calendar. Events
  that were never synced are unaffected. A partially failed migration must
  never duplicate an event: each local event still maps to exactly one
  provider event (old or new), and the failure is surfaced and retriable.
  A calendar change issued while a previous migration is still in progress
  or retrying must not duplicate or orphan events: migrations are
  serialized (at most one in flight at a time), the newer target
  supersedes, and after all pending changes settle each previously synced
  event exists exactly once in the currently selected calendar.
- R7: [F04] Attendees stored on a local event must be written to the
  provider event's attendees (`CalendarContract.Attendees`) on sync and on
  update, one row per attendee, idempotently: re-syncing the same event must
  not add duplicate attendee rows.
- R8: [F06] The serialization guarding the single LiteRT-LM engine must be
  safe under structured concurrency: it must never hold a thread-blocking
  lock across a suspension point, must not pin one OS thread per waiting
  request, and `close()` must remain responsive while a request is in
  flight. Existing externally observable semantics are unchanged: requests
  are serialized, only one LiteRT-LM session is active at a time, and the
  backend-switch retry on first failure is preserved.

## Acceptance criteria

- AC1 (R1): An explicit `ACTION_SEND` intent delivered directly to
  MainActivity from outside the app, with no credential, a credential bound
  to different content, or an already-consumed credential, results in zero
  `BackgroundAnalysisScheduler` enqueues, zero prefill of shared text/image/
  audio state, and a re-route to the consent preview activity. All three
  invalid-credential variants are covered.
- AC2 (R1): A consented handoff (credential minted for that exact content,
  consumed exactly once) prefills or enqueues the shared content as before;
  a second delivery replaying the same credential is rejected. First use
  succeeds, second use fails: 1 successful consumption max.
- AC3 (R1): An implicit `text/plain`, `image/*`, and `audio/*` share-sheet
  delivery to ShareImportActivity still shows the preview and enqueues only
  after the Analyze tap — 0 enqueues before user confirmation across all
  three types.
- AC4 (R2): With an enqueue whose acknowledgment never completes within the
  10,000 ms window but whose operation commits afterwards, the input file
  still exists when the worker runs and the analysis completes; the number
  of input files deleted as a direct result of the timeout is 0.
- AC5 (R2): An enqueue that completes exceptionally within the window still
  rolls back exactly its own just-persisted input file (file gone, other
  inputs untouched — count of other files unchanged).
- AC6 (R3): Simulated crash between provider insert and Room persist of the
  provider id (insert succeeds, local update never runs), then re-running
  sync for the same local event, leaves exactly 1 provider event for that
  local event (not 2), linked locally by a non-null `systemCalendarEventId`.
- AC7 (R3): Serial sync retries of one local event (retry count >= 2)
  produce exactly 1 provider event, while two genuinely distinct events with
  identical title/start/end in the same calendar both persist — 2 separate
  provider events, 0 merges or losses.
- AC8 (R4): After sync, `CalendarContract.Reminders` for the provider event
  contains >= 1 row with `METHOD_ALERT` and `MINUTES` equal to the configured
  value (10 by default); after a re-sync/update of the same event the
  reminder count for that event is exactly 1 (with one configured reminder).
- AC9 (R5): On each API level 26-35, initiating an update whose session is
  committed via PackageInstaller results in a user-visible OS confirmation
  step before installation: the install does not complete without user
  action, and a user abort is observable by the app as a session status
  (not a silent no-op). Every one of the 10 levels (26-35) gets a seam test
  asserting that configuration's session user-action semantics and status
  callback wiring — this is unconditional and not contingent on device
  availability. End-to-end device/emulator verification is additionally
  required only for levels whose emulator or device image is available in
  the build environment at fix time; any level covered only by the seam
  test must be named in the fix commit message.
- AC10 (R6): With N >= 1 events synced to calendar A and the target changed
  A -> B, after migration: count of the app's events in calendar B == N,
  count in calendar A == 0, and each local event's `systemCalendarEventId`
  resolves to an existing event in B.
- AC11 (R6): A migration where one move fails (provider rejects it) leaves
  every local event linked to exactly one provider event (total provider
  copies across both calendars == N), with the failure surfaced for retry —
  0 duplicated events.
- AC12 (R7): Syncing an event whose local attendee list has N >= 1 entries
  produces exactly N attendee rows on the provider event matching the local
  values; after a re-sync/update the attendee row count is still exactly N
  (0 duplicate rows added).
- AC13 (R8): With a fake engine whose response suspends indefinitely, one
  in-flight request plus a concurrent second request both use no more than 1
  blocked OS thread waiting on the guard, and `close()` called during the
  in-flight request returns within 5 seconds without deadlock while
  releasing the waiting request.
- AC14 (R8): Serialized semantics hold: 2 concurrent requests never run
  engine conversations simultaneously (exactly 1 active session at any
  time), and a first-failure backend switch still retries exactly once —
  existing GemmaLlmService test expectations remain green.
- AC15 (R1): A share whose content cannot produce a preview (for example
  `text/plain` with a null or blank `EXTRA_TEXT`) terminates in
  ShareImportActivity with a user-visible unsupported-content outcome:
  0 forwards to MainActivity, 0 enqueues, and no re-delivery loop (the
  delivered intent reaches each activity at most once).
- AC16 (R6): A calendar change B -> C issued while an A -> B migration (or
  its retry) is still pending results in serialized migrations; after both
  settle, every previously synced event exists exactly once in calendar C
  (total provider copies across calendars A, B, and C == N) and each local
  event's `systemCalendarEventId` resolves to an event in C.

## Test failure allowlist

- `com.calendaradd.service.BackgroundAnalysisSchedulerTest::enqueueText rolls back the input when the enqueue observation times out` — asserts that an enqueue-observation timeout deletes the just-persisted input file; contradicts R2, which forbids deleting an input while the enqueue commit state is unknown. The new behavior (retain for reconciliation) is correct per R2; the test must be replaced with a timeout-retains-input assertion as part of the F02 fix.

## Caller enumeration

Behavioral contracts of these seams change; signatures are expected to stay
compatible or extend additively (implementation choice left to the plan).

| File | Function/Method | Migration Note |
|------|----------------|----------------|
| app/src/main/java/com/calendaradd/MainActivity.kt | `handleIntent` (internal; callers `onCreate`, `onNewIntent`) | Add credential validation before accepting ACTION_SEND content; unverified deliveries re-routed to ShareImportActivity |
| app/src/main/java/com/calendaradd/ShareImportActivity.kt | `forwardToMainActivity` / `createMainActivityIntent` (internal) | Attach one-shot handoff credential to forwarded intents; the no-preview fallback call site (~line 135) must be replaced with terminal unsupported-content handling |
| app/src/main/java/com/calendaradd/service/SystemCalendarService.kt | `insertEvent` | Caller: `CalendarUseCase.syncEventToSystem` (1 call site) — gains dedup/adopt behavior and attendee/reminder writing; signature may extend additively |
| app/src/main/java/com/calendaradd/service/SystemCalendarService.kt | `updateEvent` | Caller: `CalendarUseCase.syncEventToSystem` (1 call site) — must write CALENDAR_ID changes when calendar moves |
| app/src/main/java/com/calendaradd/usecase/CalendarUseCase.kt | `syncEventToSystem` | Callers: `syncCommittedItems` (internal), `DetailViewModel` line 57 — public behavior unchanged from caller view |
| app/src/main/java/com/calendaradd/ui/SettingsViewModel.kt | `selectCalendar` | Caller: settings UI — triggers migration after preference write |
| app/src/main/java/com/calendaradd/usecase/PreferencesManager.kt | new reminder-minutes property (additive) | Readers: CalendarUseCase/SystemCalendarService sync path (read), SettingsViewModel via CalendarSettingsScreen (write) — no existing caller changes |
| app/src/main/java/com/calendaradd/util/ApkInstaller.kt | `install` | Caller: `SettingsViewModel` line 164 — return-value contract (Started/PermissionRequired/Failed) unchanged |
| app/src/main/java/com/calendaradd/service/GemmaLlmService.kt | `generateResponse` / `close` (guard internal) | Callers: `TextAnalysisService.extractEventJson` paths (7 call sites) — public contract unchanged |

Search method: `grep -rn` for each symbol over `app/src/main/java`
(2026-08-24, HEAD ace0041); test doubles and dynamic dispatch call sites are
not enumerated. No other callers found.
