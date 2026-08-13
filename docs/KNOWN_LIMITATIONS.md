# Known Limitations

This note records what the P1-P13 adversarial hardening campaign
(model download/storage integrity, worker reliability, input handling,
persistence, sharing, and update-checker fixes) deliberately did **not**
address. These are accepted, out-of-scope risks rather than open bugs.

## OS/OEM storage purges can still delete model files

`ModelDownloadManager` validates, quarantines, and prunes model files it
manages, but it has no way to prevent the OS or an OEM's aggressive
storage-management feature from deleting files out from under the app
(e.g. low-storage cleanup, "free up space" tools, or the user manually
clearing app storage). If a model file disappears externally between
launches, the app detects the missing/corrupt file on next use, but it
does not re-download automatically: `HomeViewModel` surfaces a
`ModelMissing` state that requires the user to tap "Download" (and
confirm a network consent dialog on metered/roaming connections) before
`startDownload` runs. A background job (`BackgroundAnalysisWorker`) that
finds the model missing posts a notification and retries or fails the
job without fetching the model itself. There is no way to intercept or
block the deletion, since it happens outside the app's process.

## LiteRT compile cache recompiles on model-file change

The persistent LiteRT-LM compile cache (`GemmaBackendConfig.liteRtCacheDir`)
is keyed by the model file path/content, with least-recently-used eviction
bounded by `LITERT_CACHE_MAX_BYTES`. This means any change to the
underlying model file — a re-download, a switch to a different model
variant, or a corrupted file being replaced — invalidates the cached
compiled graph and forces a fresh multi-second recompilation on next
initialization. This is expected behavior, not a bug, but it means model
switches and re-downloads are not free even after the download itself
succeeds.

## System-calendar writes depend on CalendarProvider availability

`SystemCalendarService` writes events through Android's `CalendarContract`
content provider. If no calendar accounts are configured, the provider is
disabled by device policy, or a given OEM's calendar app/sync adapter is
unavailable, calendar sync will fail even though event extraction and
local persistence (Room) succeed. The app does not bundle or provide a
fallback calendar store — it depends entirely on the platform's
CalendarProvider being present and writable.
