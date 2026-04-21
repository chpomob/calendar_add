package com.calendaradd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.service.LiteRtModelCatalog
import com.calendaradd.service.LiteRtModelConfig
import com.calendaradd.service.SystemCalendarService
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val calendarUseCase: CalendarUseCase,
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    private val _availableModels = MutableStateFlow(LiteRtModelCatalog.models)
    val availableModels: StateFlow<List<LiteRtModelConfig>> = _availableModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow(preferencesManager.selectedModelId)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _availableCalendars = MutableStateFlow<List<SystemCalendarService.CalendarInfo>>(emptyList())
    val availableCalendars: StateFlow<List<SystemCalendarService.CalendarInfo>> = _availableCalendars.asStateFlow()

    private val _isAutoAddEnabled = MutableStateFlow(preferencesManager.isAutoAddEnabled)
    val isAutoAddEnabled: StateFlow<Boolean> = _isAutoAddEnabled.asStateFlow()

    private val _selectedCalendarId = MutableStateFlow(preferencesManager.targetCalendarId)
    val selectedCalendarId: StateFlow<Long> = _selectedCalendarId.asStateFlow()

    private val _isFailureJsonDebugEnabled = MutableStateFlow(preferencesManager.isFailureJsonDebugEnabled)
    val isFailureJsonDebugEnabled: StateFlow<Boolean> = _isFailureJsonDebugEnabled.asStateFlow()

    init {
        loadCalendars()
    }

    fun refreshCalendars() {
        loadCalendars()
    }

    private fun loadCalendars() {
        viewModelScope.launch {
            val calendars = calendarUseCase.getAvailableCalendars()
            _availableCalendars.value = calendars

            val selectedId = _selectedCalendarId.value
            if (calendars.isNotEmpty() && selectedId != -1L && calendars.none { it.id == selectedId }) {
                preferencesManager.targetCalendarId = -1L
                preferencesManager.targetCalendarName = null
                _selectedCalendarId.value = -1L
            }
        }
    }

    fun setAutoAdd(enabled: Boolean) {
        preferencesManager.isAutoAddEnabled = enabled
        _isAutoAddEnabled.value = enabled
    }

    fun selectModel(modelId: String) {
        preferencesManager.selectedModelId = modelId
        _selectedModelId.value = modelId
    }

    fun selectCalendar(calendarId: Long, calendarName: String) {
        preferencesManager.targetCalendarId = calendarId
        preferencesManager.targetCalendarName = calendarName
        _selectedCalendarId.value = calendarId
    }

    fun setFailureJsonDebugEnabled(enabled: Boolean) {
        preferencesManager.isFailureJsonDebugEnabled = enabled
        _isFailureJsonDebugEnabled.value = enabled
    }
}
