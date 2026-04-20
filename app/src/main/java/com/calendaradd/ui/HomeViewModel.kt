package com.calendaradd.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.service.*
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.EventResult
import com.calendaradd.usecase.InputContext
import com.calendaradd.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Calendar Home Screen.
 * Handles AI analysis for text, audio, and images.
 */
class HomeViewModel(
    private val calendarUseCase: CalendarUseCase,
    private val gemmaLlmService: GemmaLlmService,
    private val modelDownloadManager: ModelDownloadManager,
    private val backgroundAnalysisScheduler: BackgroundAnalysisScheduler
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
    }
    private var isDownloadingModel = false
    private var isInitializingModel = false

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    private val _selectedModel = MutableStateFlow(modelDownloadManager.getSelectedModel())
    val selectedModel: StateFlow<LiteRtModelConfig> = _selectedModel.asStateFlow()

    init {
        refreshModelState()
    }

    fun refreshModelState() {
        viewModelScope.launch {
            val currentModel = modelDownloadManager.getSelectedModel()
            val hasModelChanged = currentModel.id != _selectedModel.value.id

            if (hasModelChanged) {
                AppLog.i(TAG, "Switching selected model to ${currentModel.displayName}")
                gemmaLlmService.close()
                _selectedModel.value = currentModel
                _isModelReady.value = false
                _downloadProgress.value = null
                if (_uiState.value !is HomeUiState.Loading) {
                    _uiState.value = HomeUiState.Idle
                }
            }

            if (!modelDownloadManager.isModelDownloaded(currentModel)) {
                _isModelReady.value = false
                _uiState.value = HomeUiState.ModelMissing
                return@launch
            }

            if (backgroundAnalysisScheduler.hasPendingWork()) {
                _isModelReady.value = true
                _uiState.value = HomeUiState.Queued(
                    "Analysis is still running in the background. You can leave the app and watch the notification."
                )
                return@launch
            }

            if (_uiState.value is HomeUiState.Queued) {
                _uiState.value = HomeUiState.Idle
            }
            initializeModel()
        }
    }

    private fun initializeModel() {
        if (_isModelReady.value || isInitializingModel) return
        val currentModel = _selectedModel.value
        isInitializingModel = true
        viewModelScope.launch {
            try {
                _uiState.value = HomeUiState.Loading("Initializing ${currentModel.shortName}...")
                gemmaLlmService.initialize(
                    modelPath = modelDownloadManager.getModelFile(currentModel).absolutePath,
                    modelConfig = currentModel
                )
                modelDownloadManager.cleanupUnusedModelFiles(currentModel)
                _isModelReady.value = true
                _uiState.value = HomeUiState.Idle
            } catch (e: Exception) {
                val backendInfo = gemmaLlmService.lastBackendUsed ?: "none"
                val failureInfo = gemmaLlmService.lastInitializationFailure
                val details = failureInfo ?: (e.message ?: "Unknown initialization error")
                _uiState.value = HomeUiState.Error(
                    "Init failed for ${currentModel.shortName} (active backend: $backendInfo). $details"
                )
            } finally {
                isInitializingModel = false
            }
        }
    }

    fun downloadModel() {
        if (_isModelReady.value || isDownloadingModel) return
        val currentModel = _selectedModel.value
        
        if (!modelDownloadManager.hasEnoughSpace(currentModel)) {
            _uiState.value = HomeUiState.Error(
                "Not enough disk space. Please free up at least ${currentModel.requiredFreeSpaceLabel}."
            )
            return
        }

        isDownloadingModel = true
        viewModelScope.launch {
            try {
                _downloadProgress.value = 0
                _uiState.value = HomeUiState.Loading("Starting download of ${currentModel.shortName} (${currentModel.sizeLabel})...")
                val downloadId = modelDownloadManager.startDownload(currentModel)
                modelDownloadManager.trackProgress(downloadId).collect { status ->
                    when (status) {
                        is DownloadStatus.Progress -> {
                            _downloadProgress.value = status.percentage
                            _uiState.value = HomeUiState.Loading("Downloading ${currentModel.shortName}: ${status.percentage}%")
                        }
                        is DownloadStatus.Success -> {
                            isDownloadingModel = false
                            _downloadProgress.value = 100
                            initializeModel()
                        }
                        is DownloadStatus.Failed -> {
                            isDownloadingModel = false
                            _downloadProgress.value = null
                            _uiState.value = HomeUiState.Error(status.error)
                        }
                    }
                }
            } catch (e: Exception) {
                isDownloadingModel = false
                _uiState.value = HomeUiState.Error("Download initialization failed: ${e.message}")
            }
        }
    }

    fun processText(input: String) {
        if (input.isBlank() || !_isModelReady.value) return
        val currentModel = _selectedModel.value

        try {
            val workId = backgroundAnalysisScheduler.enqueueText(input, currentModel)
            gemmaLlmService.close()
            AppLog.i(TAG, "Queued background text analysis workId=$workId model=${currentModel.shortName}")
            _uiState.value = HomeUiState.Queued(
                "Text analysis was queued in the background. You can leave the app and wait for the notification."
            )
        } catch (e: Exception) {
            _uiState.value = HomeUiState.Error("Failed to queue background text analysis: ${e.message}")
        }
    }

    fun processImage(bitmap: Bitmap) {
        val currentModel = _selectedModel.value
        if (!_isModelReady.value) return
        if (!currentModel.supportsImage) {
            _uiState.value = HomeUiState.Error("${currentModel.displayName} does not support image input.")
            return
        }
        try {
            val workId = backgroundAnalysisScheduler.enqueueImage(bitmap, currentModel)
            gemmaLlmService.close()
            AppLog.i(TAG, "Queued background image analysis workId=$workId model=${currentModel.shortName}")
            _uiState.value = HomeUiState.Queued(
                "Image analysis was queued in the background. You can leave the app and wait for the notification."
            )
        } catch (e: Exception) {
            _uiState.value = HomeUiState.Error("Failed to queue background image analysis: ${e.message}")
        }
    }

    fun processAudio(audioData: ByteArray) {
        val currentModel = _selectedModel.value
        if (!_isModelReady.value) return
        if (!currentModel.supportsAudio) {
            _uiState.value = HomeUiState.Error("${currentModel.displayName} does not support audio input.")
            return
        }
        try {
            val workId = backgroundAnalysisScheduler.enqueueAudio(audioData, currentModel)
            gemmaLlmService.close()
            AppLog.i(TAG, "Queued background audio analysis workId=$workId model=${currentModel.shortName}")
            _uiState.value = HomeUiState.Queued(
                "Audio analysis was queued in the background. You can leave the app and wait for the notification."
            )
        } catch (e: Exception) {
            _uiState.value = HomeUiState.Error("Failed to queue background audio analysis: ${e.message}")
        }
    }

    fun resetState() {
        if (_isModelReady.value) {
            _downloadProgress.value = null
        }
        _uiState.value = HomeUiState.Idle
    }

    private fun newTraceId(prefix: String): String {
        return "$prefix-${System.currentTimeMillis().toString(16)}"
    }
}

sealed class HomeUiState {
    object Idle : HomeUiState()
    object ModelMissing : HomeUiState()
    data class Loading(val message: String) : HomeUiState()
    data class Queued(val message: String) : HomeUiState()
    data class Success(val createdCount: Int, val firstEventTitle: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
