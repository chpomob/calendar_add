package com.calendaradd.usecase

import java.util.Date

/**
 * Context information for input processing.
 */
data class InputContext(
    val timestamp: Long = Date().time,
    val timezone: String = java.util.TimeZone.getDefault().id,
    val language: String = "en",
    val traceId: String = "req-${timestamp.toString(16)}"
)
