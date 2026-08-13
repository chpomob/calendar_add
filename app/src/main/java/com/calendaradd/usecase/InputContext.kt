package com.calendaradd.usecase

import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Context information for input processing.
 */
data class InputContext(
    val timestamp: Long = Date().time,
    val timezone: String = java.util.TimeZone.getDefault().id,
    val language: String = Locale.getDefault().toLanguageTag(),
    val traceId: String = "req-${timestamp.toString(16)}",
    /**
     * Stable identity of the logical save request, used for extraction-batch idempotency
     * (see [CalendarUseCase] `extractionBatchKey`). Unlike [traceId]/[timestamp], this must
     * stay identical across retries of the *same* request and differ between genuinely
     * independent requests. The default (a fresh random UUID per construction) is correct for
     * one-shot interactive calls, where every call is a distinct user save. A caller that
     * retries the same logical request — currently only `BackgroundAnalysisWorker`, which
     * reconstructs `InputContext` on every retry attempt — must pass its own stable id (the
     * WorkManager job id) explicitly.
     */
    val requestId: String = UUID.randomUUID().toString()
)
