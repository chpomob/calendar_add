package com.calendaradd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.service.ApkDownloadResult
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
import com.calendaradd.util.InstallStatus
import com.calendaradd.util.InstallStatusReceiver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val _defaultReminderMinutes = MutableStateFlow(preferencesManager.defaultReminderMinutes)
    val defaultReminderMinutes: StateFlow<Int> = _defaultReminderMinutes.asStateFlow()

    private val _isFailureJsonDebugEnabled = MutableStateFlow(preferencesManager.isFailureJsonDebugEnabled)
    val isFailureJsonDebugEnabled: StateFlow<Boolean> = _isFailureJsonDebugEnabled.asStateFlow()

    private val _updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle())
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()

    val currentVersion: String = "v${BuildConfig.VERSION_NAME}"

    /** R10/AC18: terminal install outcome published by InstallStatusReceiver's broadcast. */
    val installStatus: StateFlow<InstallStatus?> = InstallStatusReceiver.statusFlow

    /**
     * AC16b: serializes migration passes so rapid target changes never interleave two moves
     * of the same event, process-scoped so multiple ViewModel instances (e.g. backstack
     * restoration) share one queue. Each pass reads
     * [PreferencesManager.pendingCalendarMigrationTo] at run time, so a quick A→B→C change
     * coalesces into a single move to C rather than two moves (new selection supersedes).
     */
    companion object {
        private val migrationMutex = Mutex()
    }

    private val _migrationError = MutableStateFlow<String?>(null)
    val migrationError: StateFlow<String?> = _migrationError.asStateFlow()

    init {
        loadCalendars()
        // AC16b: retry any migration left pending by a failure or process death.
        startMigration()
        // R10/AC18: surface PackageInstaller outcomes (success/failure/aborted) in the UI.
        observeInstallStatus()
    }

    private fun observeInstallStatus() {
        // A2: the terminal broadcast may have landed while no Settings screen was alive — or
        // in the process a successful self-update just replaced. Rehydrate the durable
        // outcome so the collector below still surfaces it.
        if (InstallStatusReceiver.statusFlow.value == null) {
            apkInstaller.consumeLastInstallOutcome()?.let(InstallStatusReceiver::publish)
        }
        viewModelScope.launch {
            InstallStatusReceiver.statusFlow.collect { status ->
                if (status == null) return@collect
                _updateCheckState.value = when (status) {
                    InstallStatus.SUCCESS -> UpdateCheckState.Idle("Update installed.")
                    InstallStatus.FAILURE -> UpdateCheckState.Error("The update failed to install. Start the update again.")
                    InstallStatus.ABORTED -> UpdateCheckState.Idle("Update installation was cancelled.")
                }
            }
        }
    }

    fun refreshCalendars() {
        // F08: a refresh follows a permission grant or ON_RESUME, either of which may be what
        // unblocked a pending migration — queue a retry pass (serialized; no-op when nothing
        // is pending).
        startMigration()
        loadCalendars()
    }

    private fun startMigration() {
        viewModelScope.launch {
            migrationMutex.withLock {
                // R6b: surface the failure; the pending flag survives so this retried pass
                // resumes where the failed one stopped.
                _migrationError.value = calendarUseCase.retryPendingMigration().error
            }
        }
    }

    /** R6b: explicit retry entry point after a surfaced migration failure. */
    fun retryMigration() {
        startMigration()
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
        preferencesManager.pendingCalendarMigrationTo = calendarId
        _selectedCalendarId.value = calendarId
        startMigration()
    }

    fun setFailureJsonDebugEnabled(enabled: Boolean) {
        preferencesManager.isFailureJsonDebugEnabled = enabled
        _isFailureJsonDebugEnabled.value = enabled
    }

    fun setHeavyAnalysisEnabled(enabled: Boolean) {
        preferencesManager.isHeavyAnalysisEnabled = enabled
        _isHeavyAnalysisEnabled.value = enabled
    }

    fun setDefaultReminderMinutes(minutes: Int) {
        preferencesManager.defaultReminderMinutes = minutes
        _defaultReminderMinutes.value = preferencesManager.defaultReminderMinutes
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
            // Drop any stale terminal outcome from a previous install attempt (flow + durable copy).
            InstallStatusReceiver.reset()
            apkInstaller.clearLastInstallOutcome()
            val downloadResult = apkDownloadManager.downloadApkResult(updateInfo) { progress ->
                _updateCheckState.value = UpdateCheckState.Downloading(updateInfo, progress)
            }
            val apkFile = when (downloadResult) {
                is ApkDownloadResult.Success -> downloadResult.file
                ApkDownloadResult.ChecksumUnavailable -> {
                    _updateCheckState.value = UpdateCheckState.Error(
                        "Update checksum is missing. Download the APK manually from the release page."
                    )
                    return@launch
                }
                ApkDownloadResult.ChecksumMismatch -> {
                    _updateCheckState.value = UpdateCheckState.Error(
                        "Downloaded APK checksum did not match. Try again later or download manually."
                    )
                    return@launch
                }
                ApkDownloadResult.DownloadFailed -> {
                    _updateCheckState.value = UpdateCheckState.Error("Download failed. Check your connection and try again.")
                    return@launch
                }
            }

            val installResult = withContext(Dispatchers.IO) {
                apkInstaller.install(apkFile)
            }
            when (installResult) {
                InstallResult.Started -> {
                    // A3: the status broadcast is asynchronous and may already have delivered a
                    // terminal outcome before install() resumed here — never overwrite it
                    // with "Installer opened".
                    if (InstallStatusReceiver.statusFlow.value == null) {
                        _updateCheckState.value = UpdateCheckState.Downloaded(
                            updateInfo = updateInfo,
                            apkFile = apkFile,
                            message = "Installer opened — confirm the install when Android asks."
                        )
                    }
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
