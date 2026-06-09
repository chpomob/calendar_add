package com.calendaradd.usecase

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
        private const val KEY_LAST_ANALYSIS_OUTCOME = "last_analysis_outcome"
        private const val KEY_ACTIVE_MODEL_DOWNLOAD_ID = "active_model_download_id"
        private const val KEY_ACTIVE_MODEL_DOWNLOAD_MODEL_ID = "active_model_download_model_id"
    }

    var isAutoAddEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADD, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_ADD, value) }

    var targetCalendarId: Long
        get() = prefs.getLong(KEY_TARGET_CALENDAR_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_TARGET_CALENDAR_ID, value) }

    var targetCalendarName: String?
        get() = prefs.getString(KEY_TARGET_CALENDAR_NAME, null)
        set(value) = prefs.edit { putString(KEY_TARGET_CALENDAR_NAME, value) }

    var selectedModelId: String
        get() = prefs.getString(KEY_SELECTED_MODEL_ID, LiteRtModelCatalog.DEFAULT_MODEL_ID)
            ?: LiteRtModelCatalog.DEFAULT_MODEL_ID
        set(value) = prefs.edit { putString(KEY_SELECTED_MODEL_ID, value) }

    var isHeavyAnalysisEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEAVY_ANALYSIS, false)
        set(value) = prefs.edit { putBoolean(KEY_HEAVY_ANALYSIS, value) }

    var isFailureJsonDebugEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_FAILURE_JSON, false)
        set(value) = prefs.edit { putBoolean(KEY_DEBUG_FAILURE_JSON, value) }

    var lastAnalysisOutcome: String?
        get() = prefs.getString(KEY_LAST_ANALYSIS_OUTCOME, null)
        set(value) = prefs.edit { putString(KEY_LAST_ANALYSIS_OUTCOME, value) }

    var activeModelDownloadId: Long
        get() = prefs.getLong(KEY_ACTIVE_MODEL_DOWNLOAD_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_ACTIVE_MODEL_DOWNLOAD_ID, value) }

    var activeModelDownloadModelId: String?
        get() = prefs.getString(KEY_ACTIVE_MODEL_DOWNLOAD_MODEL_ID, null)
        set(value) = prefs.edit { putString(KEY_ACTIVE_MODEL_DOWNLOAD_MODEL_ID, value) }

    fun clearActiveModelDownload(downloadId: Long? = null) {
        if (downloadId != null && activeModelDownloadId != downloadId) return
        prefs.edit {
            remove(KEY_ACTIVE_MODEL_DOWNLOAD_ID)
            remove(KEY_ACTIVE_MODEL_DOWNLOAD_MODEL_ID)
        }
    }
}
