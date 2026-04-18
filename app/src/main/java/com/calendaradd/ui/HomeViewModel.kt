package com.calendaradd.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.service.GemmaLlmService
import com.calendaradd.service.SpeechToTextService
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.EventResult
import com.google.mlkit.genai.FeatureStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Calendar Home Screen.
 * Handles AI model status, speech-to-text, and event extraction.
 */
class HomeViewModel(
    private val calendarUseCase: CalendarUseCase,
    private val gemmaLlmService: GemmaLlmService,
    private val speechToTextService: SpeechToTextService
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _modelStatus = MutableStateFlow<FeatureStatus>(FeatureStatus.UNAVAILABLE)
    val modelStatus: StateFlow<FeatureStatus> = _modelStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    init {
        checkModelStatus()
    }

    private fun checkModelStatus() {
        viewModelScope.launch {
            _modelStatus.value = gemmaLlmService.checkModelStatus()
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            gemmaLlmService.downloadModel().collect { progress ->
                _downloadProgress.value = progress
                if (progress == 100) {
                    _modelStatus.value = FeatureStatus.AVAILABLE
                }
            }
        }
    }

    fun processText(input: String) {
        if (input.isBlank()) return
        
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading("Analyzing text with Gemma 4...")
            when (val result = calendarUseCase.createEventFromText(input)) {
                is EventResult.Success -> {
                    _uiState.value = HomeUiState.Success(result.event.title)
                }
                is EventResult.Failure -> {
                    _uiState.value = HomeUiState.Error(result.message)
                }
            }
        }
    }

    fun processImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading("Performing OCR and AI analysis...")
            when (val result = calendarUseCase.createEventFromImage(bitmap)) {
                is EventResult.Success -> {
                    _uiState.value = HomeUiState.Success(result.event.title)
                }
                is EventResult.Failure -> {
                    _uiState.value = HomeUiState.Error(result.message)
                }
            }
        }
    }

    fun startVoiceInput() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Listening
            speechToTextService.transcribeMicrophone().collect { text ->
                if (text.isNotBlank()) {
                    processText(text)
                }
            }
        }
    }
}

sealed class HomeUiState {
    object Idle : HomeUiState()
    data class Loading(val message: String) : HomeUiState()
    object Listening : HomeUiState()
    data class Success(val eventTitle: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
