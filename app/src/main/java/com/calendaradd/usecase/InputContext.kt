package com.calendaradd.usecase

import java.util.Date
import java.util.Locale

/**
 * Context information for input processing.
 */
data class InputContext(
    val timestamp: Long = Date().time,
    val timezone: String = java.util.TimeZone.getDefault().id,
    val language: String = Locale.getDefault().toLanguageTag(),
    val traceId: String = "req-${timestamp.toString(16)}"
)
