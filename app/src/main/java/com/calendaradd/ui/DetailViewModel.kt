package com.calendaradd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.Event
import com.calendaradd.usecase.EventUpdateResult
import com.calendaradd.usecase.PreferencesManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
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

    private val _editStatus = MutableStateFlow<EditStatus>(EditStatus.Idle)
    val editStatus: StateFlow<EditStatus> = _editStatus.asStateFlow()

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
                    _event.value = currentEvent.copy(systemCalendarEventId = result)
                    _syncStatus.value = SyncStatus.Success
                } else {
                    _syncStatus.value = SyncStatus.Error("Failed to sync to system calendar.")
                }
            } else {
                _syncStatus.value = SyncStatus.Error("No calendars found on device.")
            }
        }
    }

    fun saveEdits(draft: EventEditDraft) {
        val currentEvent = _event.value ?: return
        val title = draft.title.trim()
        if (title.isBlank()) {
            _editStatus.value = EditStatus.Error("Title is required.")
            return
        }

        val startTime = parseEditDateTime(draft.startTime)
        if (startTime == null) {
            _editStatus.value = EditStatus.Error("Start time must use YYYY-MM-DD HH:mm.")
            return
        }

        val parsedEndTime = draft.endTime
            .takeIf { it.isNotBlank() }
            ?.let(::parseEditDateTime)
        if (draft.endTime.isNotBlank() && parsedEndTime == null) {
            _editStatus.value = EditStatus.Error("End time must use YYYY-MM-DD HH:mm.")
            return
        }

        val endTime = parsedEndTime ?: currentEvent.endTime.takeIf { it > startTime } ?: startTime + DEFAULT_EVENT_DURATION_MS
        if (endTime <= startTime) {
            _editStatus.value = EditStatus.Error("End time must be after start time.")
            return
        }

        val editedEvent = currentEvent.copy(
            title = title,
            description = draft.description.trim(),
            startTime = startTime,
            endTime = endTime,
            location = draft.location.trim(),
            attendees = draft.attendees.trim()
        )

        viewModelScope.launch {
            _editStatus.value = EditStatus.Saving
            when (val result = calendarUseCase.updateEvent(editedEvent)) {
                is EventUpdateResult.Success -> {
                    _event.value = result.event
                    _editStatus.value = EditStatus.Success(
                        syncedToSystem = result.syncedToSystem,
                        warning = result.warning
                    )
                    if (result.syncedToSystem) {
                        _syncStatus.value = SyncStatus.Success
                    } else {
                        _syncStatus.value = SyncStatus.Idle
                    }
                }
            }
        }
    }

    fun resetSyncStatus() {
        _syncStatus.value = SyncStatus.Idle
    }

    fun resetEditStatus() {
        _editStatus.value = EditStatus.Idle
    }

    companion object {
        private const val DEFAULT_EVENT_DURATION_MS = 60 * 60 * 1000L
        private val EDIT_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

        fun draftFrom(event: Event): EventEditDraft {
            return EventEditDraft(
                title = event.title,
                startTime = formatEditDateTime(event.startTime),
                endTime = formatEditDateTime(event.endTime),
                location = event.location,
                attendees = event.attendees,
                description = event.description
            )
        }

        fun formatEditDateTime(timestampMillis: Long): String {
            if (timestampMillis <= 0L) return ""
            return Instant.ofEpochMilli(timestampMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(EDIT_DATE_TIME_FORMATTER)
        }

        private fun parseEditDateTime(value: String): Long? {
            return try {
                LocalDateTime.parse(value.trim(), EDIT_DATE_TIME_FORMATTER)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

data class EventEditDraft(
    val title: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val location: String = "",
    val attendees: String = "",
    val description: String = ""
)

sealed class EditStatus {
    object Idle : EditStatus()
    object Saving : EditStatus()
    data class Success(val syncedToSystem: Boolean, val warning: String? = null) : EditStatus()
    data class Error(val message: String) : EditStatus()
}
