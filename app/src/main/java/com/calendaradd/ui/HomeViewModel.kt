package com.calendaradd.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.service.*
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.EventResult
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
    private val modelDownloadManager: ModelDownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    init {
        checkModelAvailability()
    }

    private fun checkModelAvailability() {
        if (modelDownloadManager.isModelDownloaded()) {
            initializeModel()
        } else {
            // Model not present, needs download
            _uiState.value = HomeUiState.ModelMissing
        }
    }

    private fun initializeModel() {
        viewModelScope.launch {
            try {
                _uiState.value = HomeUiState.Loading("Initializing Gemma 4 Engine...")
                gemmaLlmService.initialize(modelDownloadManager.getModelFile().absolutePath)
                _isModelReady.value = true
                _uiState.value = HomeUiState.Idle
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error("Failed to initialize Gemma 4: ${e.message}")
            }
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading("Starting download of Gemma 4 (~1.5GB)...")
            val downloadId = modelDownloadManager.startDownload()
            modelDownloadManager.trackProgress(downloadId).collect { status ->
                when (status) {
                    is DownloadStatus.Progress -> {
                        _downloadProgress.value = status.percentage
                        _uiState.value = HomeUiState.Loading("Downloading model: ${status.percentage}%")
                    }
                    is DownloadStatus.Success -> {
                        _downloadProgress.value = 100
                        initializeModel()
                    }
                    is DownloadStatus.Failed -> {
                        _uiState.value = HomeUiState.Error(status.error)
                    }
                }
            }
        }
    }

    fun processText(input: String) {
        if (input.isBlank() || !_isModelReady.value) return
        
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading("Analyzing text with Gemma 4...")
            when (val result = calendarUseCase.createEventFromText(input)) {
                is EventResult.Success -> _uiState.value = HomeUiState.Success(result.event.title)
                is EventResult.Failure -> _uiState.value = HomeUiState.Error(result.message)
            }
        }
    }

    fun processImage(bitmap: Bitmap) {
        if (!_isModelReady.value) return
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading("Analyzing image with Gemma 4...")
            when (val result = calendarUseCase.createEventFromImage(bitmap)) {
                is EventResult.Success -> _uiState.value = HomeUiState.Success(result.event.title)
                is EventResult.Failure -> _uiState.value = HomeUiState.Error(result.message)
            }
        }
    }

    fun processAudio(audioData: ByteArray) {
        if (!_isModelReady.value) return
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading("Analyzing audio with Gemma 4...")
            when (val result = calendarUseCase.createEventFromAudio(audioData)) {
                is EventResult.Success -> _uiState.value = HomeUiState.Success(result.event.title)
                is EventResult.Failure -> _uiState.value = HomeUiState.Error(result.message)
            }
        }
    }

    fun resetState() {
        _uiState.value = HomeUiState.Idle
    }
}

sealed class HomeUiState {
    object Idle : HomeUiState()
    object ModelMissing : HomeUiState()
    data class Loading(val message: String) : HomeUiState()
    data class Success(val eventTitle: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
