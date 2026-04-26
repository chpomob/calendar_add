package com.calendaradd.usecase

import android.content.Context
import android.content.SharedPreferences
import com.calendaradd.service.LiteRtModelCatalog

/**
 * Manages user preferences persistence.
 */
class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("calendar_add_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AUTO_ADD = "auto_add_to_calendar"
        private const val KEY_TARGET_CALENDAR_ID = "target_calendar_id"
        private const val KEY_TARGET_CALENDAR_NAME = "target_calendar_name"
        private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        private const val KEY_HEAVY_ANALYSIS = "heavy_analysis"
        private const val KEY_DEBUG_FAILURE_JSON = "debug_failure_json"
    }

    var isAutoAddEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ADD, value).apply()

    var targetCalendarId: Long
        get() = prefs.getLong(KEY_TARGET_CALENDAR_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_TARGET_CALENDAR_ID, value).apply()

    var targetCalendarName: String?
        get() = prefs.getString(KEY_TARGET_CALENDAR_NAME, null)
        set(value) = prefs.edit().putString(KEY_TARGET_CALENDAR_NAME, value).apply()

    var selectedModelId: String
        get() = prefs.getString(KEY_SELECTED_MODEL_ID, LiteRtModelCatalog.DEFAULT_MODEL_ID)
            ?: LiteRtModelCatalog.DEFAULT_MODEL_ID
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()

    var isHeavyAnalysisEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEAVY_ANALYSIS, false)
        set(value) = prefs.edit().putBoolean(KEY_HEAVY_ANALYSIS, value).apply()

    var isFailureJsonDebugEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_FAILURE_JSON, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_FAILURE_JSON, value).apply()
}
