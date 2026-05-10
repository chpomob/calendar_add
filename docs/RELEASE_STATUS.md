# Calendar Add - Release Status

Last updated: 2026-05-10

## Summary

The project is in a usable beta state, not a finished release branch.

Verified on the current codebase:

- `./gradlew test` passes
- `./gradlew assembleDebug` passes

## Working Now

- Runtime download of selectable LiteRT-LM models
- Text analysis flow from the home screen
- Image analysis flow from picker/share input
- Press-and-hold live microphone capture with a 30-second limit, audio-file import, and shared audio-byte analysis pipeline
- Multi-event extraction and persistence
- Optional heavy analysis mode for staged image/audio extraction
- Background analysis through a foreground worker notification flow
- Local Room persistence
- Optional sync to the Android system calendar
- Event detail editing before calendar sync
- Settings for model choice and calendar target

## Recently Hardened

- Qwen models now use a CPU-only multimodal initialization profile
- Image decoding uses modern Android decoding paths and bounded image sizes
- Extraction can parse and persist multiple events from one response
- Extraction failures no longer create empty placeholder events
- Invalid extracted dates are rejected instead of silently defaulting to the current time
- Relative dates like `tomorrow` and `next Friday` are now prompted against an explicit local reference datetime and timezone
- Heavy analysis mode now uses one multimodal pass plus text-only refinement rounds for image and audio inputs
- An optional diagnostics mode can surface the raw model JSON in the app when a background extraction fails
- Background worker retries are now labeled, capped after repeated restarts, and result notifications use a separate channel from progress notifications
- Background analysis results keep separate notifications instead of overwriting each other
- Stale WorkManager background chains are repaired on startup/enqueue, and queued inputs are no longer stored only in cache
- Event detail now hides empty metadata fields instead of showing blank labels
- Event edits are saved locally and update an existing Android Calendar row when the event was already synced
- Request tracing/logging is present across the extraction pipeline

## Still Missing Or Incomplete

- No extraction fallback when the selected model is absent
- No waveform/live recording UI polish yet beyond press-and-hold voice capture
- Event list is basic: no search, filter, or delete UI
- Event editing uses direct text fields for date/time rather than a full calendar/date-picker flow
- No on-device runtime verification for every model/backend combination

## Documentation Status

Current docs kept up to date in this cleanup:

- `README.md`
- `docs/MODEL_INTEGRATION.md`
- `docs/RELEASE_STATUS.md`
- `docs/STORE_LISTING.md`
- `docs/RELEASE.md`

Historical planning material still exists in `DEVELOPMENT_PLAN.md`, but it should not be treated as the source of truth for the current implementation.

---

## Conclusion

Current state:

- usable beta
- good enough for internal and alpha testing
- not yet a polished final consumer release

Recommended distribution path:

- debug APK artifacts from GitHub Actions for commit-by-commit testing
- draft GitHub prereleases for alpha tags
- local signing for final release publication

Main remaining release debt:

- local multimodal performance and reliability still vary by device and model
- event list UX is still basic
- Play listing assets and final hosted privacy policy still need completion
