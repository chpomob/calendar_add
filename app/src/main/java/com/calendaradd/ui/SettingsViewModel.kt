package com.calendaradd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _availableCalendars = MutableStateFlow<List<SystemCalendarService.CalendarInfo>>(emptyList())
    val availableCalendars: StateFlow<List<SystemCalendarService.CalendarInfo>> = _availableCalendars.asStateFlow()

    private val _isAutoAddEnabled = MutableStateFlow(preferencesManager.isAutoAddEnabled)
    val isAutoAddEnabled: StateFlow<Boolean> = _isAutoAddEnabled.asStateFlow()

    private val _selectedCalendarId = MutableStateFlow(preferencesManager.targetCalendarId)
    val selectedCalendarId: StateFlow<Long> = _selectedCalendarId.asStateFlow()

    init {
        loadCalendars()
    }

    private fun loadCalendars() {
        viewModelScope.launch {
            _availableCalendars.value = calendarUseCase.getAvailableCalendars()
        }
    }

    fun setAutoAdd(enabled: Boolean) {
        preferencesManager.isAutoAddEnabled = enabled
        _isAutoAddEnabled.value = enabled
    }

    fun selectCalendar(calendarId: Long, calendarName: String) {
        preferencesManager.targetCalendarId = calendarId
        preferencesManager.targetCalendarName = calendarName
        _selectedCalendarId.value = calendarId
    }
}
