package com.calendaradd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.Event
import com.calendaradd.usecase.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(
    private val eventId: Long,
    private val calendarUseCase: CalendarUseCase,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        loadEvent()
    }

    private fun loadEvent() {
        viewModelScope.launch {
            calendarUseCase.getAllEvents().collect { events ->
                _event.value = events.find { it.id == eventId }
            }
        }
    }

    fun syncToSystemCalendar() {
        val currentEvent = _event.value ?: return
        viewModelScope.launch {
            _syncStatus.value = SyncStatus.Syncing
            val calendars = calendarUseCase.getAvailableCalendars()
            val preferredId = preferencesManager.targetCalendarId
                .takeIf { id -> id != -1L && calendars.any { it.id == id } }
            val primaryId = preferredId ?: calendars.find { it.isPrimary }?.id ?: calendars.firstOrNull()?.id
            
            if (primaryId != null) {
                val result = calendarUseCase.syncEventToSystem(currentEvent, primaryId)
                if (result != null) {
                    _syncStatus.value = SyncStatus.Success
                } else {
                    _syncStatus.value = SyncStatus.Error("Failed to sync to system calendar.")
                }
            } else {
                _syncStatus.value = SyncStatus.Error("No calendars found on device.")
            }
        }
    }

    fun resetSyncStatus() {
        _syncStatus.value = SyncStatus.Idle
    }
}

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}
