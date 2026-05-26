package com.calendaradd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.BuildConfig
import com.calendaradd.service.ApkDownloadManager
import com.calendaradd.service.LiteRtModelCatalog
import com.calendaradd.service.LiteRtModelConfig
import com.calendaradd.service.SystemCalendarService
import com.calendaradd.service.UpdateCheckerService
import com.calendaradd.service.UpdateInfo
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.ApkInstaller
import com.calendaradd.util.InstallResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UpdateCheckState {
    data class Idle(val message: String? = null) : UpdateCheckState()
    object Checking : UpdateCheckState()
    data class Available(val updateInfo: UpdateInfo) : UpdateCheckState()
    data class Downloading(val updateInfo: UpdateInfo, val progress: Int) : UpdateCheckState()
    data class Downloaded(val updateInfo: UpdateInfo, val apkFile: File, val message: String) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

class SettingsViewModel(
    private val calendarUseCase: CalendarUseCase,
    private val preferencesManager: PreferencesManager,
    private val updateCheckerService: UpdateCheckerService,
    private val apkDownloadManager: ApkDownloadManager,
    private val apkInstaller: ApkInstaller
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

    private val _isHeavyAnalysisEnabled = MutableStateFlow(preferencesManager.isHeavyAnalysisEnabled)
    val isHeavyAnalysisEnabled: StateFlow<Boolean> = _isHeavyAnalysisEnabled.asStateFlow()

    private val _isFailureJsonDebugEnabled = MutableStateFlow(preferencesManager.isFailureJsonDebugEnabled)
    val isFailureJsonDebugEnabled: StateFlow<Boolean> = _isFailureJsonDebugEnabled.asStateFlow()

    private val _updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle())
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()

    val currentVersion: String = "v${BuildConfig.VERSION_NAME}"

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

    fun setHeavyAnalysisEnabled(enabled: Boolean) {
        preferencesManager.isHeavyAnalysisEnabled = enabled
        _isHeavyAnalysisEnabled.value = enabled
    }

    fun checkForUpdates() {
        if (_updateCheckState.value is UpdateCheckState.Checking) return
        viewModelScope.launch {
            _updateCheckState.value = UpdateCheckState.Checking
            val updateInfo = withContext(Dispatchers.IO) {
                updateCheckerService.checkForUpdates(forceRefresh = true)
            }
            _updateCheckState.value = if (updateInfo.available && updateInfo.downloadUrl != null) {
                UpdateCheckState.Available(updateInfo)
            } else {
                UpdateCheckState.Idle("Calendar Add is up to date.")
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val updateInfo = when (val state = _updateCheckState.value) {
            is UpdateCheckState.Available -> state.updateInfo
            is UpdateCheckState.Downloaded -> state.updateInfo
            else -> return
        }
        if (_updateCheckState.value is UpdateCheckState.Downloading) return

        viewModelScope.launch {
            _updateCheckState.value = UpdateCheckState.Downloading(updateInfo, 0)
            val apkFile = apkDownloadManager.downloadApk(updateInfo) { progress ->
                _updateCheckState.value = UpdateCheckState.Downloading(updateInfo, progress)
            }

            if (apkFile == null) {
                _updateCheckState.value = UpdateCheckState.Error("Download failed. Check your connection and try again.")
                return@launch
            }

            when (val installResult = apkInstaller.install(apkFile)) {
                InstallResult.Started -> {
                    _updateCheckState.value = UpdateCheckState.Downloaded(
                        updateInfo = updateInfo,
                        apkFile = apkFile,
                        message = "Installer opened."
                    )
                }
                is InstallResult.PermissionRequired -> {
                    _updateCheckState.value = UpdateCheckState.Downloaded(
                        updateInfo = updateInfo,
                        apkFile = apkFile,
                        message = installResult.message
                    )
                }
                is InstallResult.Failed -> {
                    _updateCheckState.value = UpdateCheckState.Error(installResult.message)
                }
            }
        }
    }

    fun dismissUpdate() {
        _updateCheckState.value = UpdateCheckState.Idle()
    }
}
