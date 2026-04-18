package com.calendaradd.usecase

import java.util.Date

/**
 * User preferences for app settings.
 */
data class UserPreferences(
    val exportData: Boolean = false,
    val showDate: String = Date().toString(),
    val version: String = "1.0.0"
)
