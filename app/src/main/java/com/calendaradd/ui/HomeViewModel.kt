package com.calendaradd.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calendaradd.service.GemmaLlmService
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
    private val gemmaLlmService: GemmaLlmService
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    fun initializeModel(modelPath: String) {
        viewModelScope.launch {
            try {
                gemmaLlmService.initialize(modelPath)
                _isModelReady.value = true
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error("Failed to initialize Gemma 4: ${e.message}")
            }
        }
    }

    fun processText(input: String) {
        if (input.isBlank()) return
        
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading("Analyzing text with Gemma 4...")
            when (val result = calendarUseCase.createEventFromText(input)) {
                is EventResult.Success -> _uiState.value = HomeUiState.Success(result.event.title)
                is EventResult.Failure -> _uiState.value = HomeUiState.Error(result.message)
            }
        }
    }

    fun processImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading("Analyzing image with Gemma 4...")
            when (val result = calendarUseCase.createEventFromImage(bitmap)) {
                is EventResult.Success -> _uiState.value = HomeUiState.Success(result.event.title)
                is EventResult.Failure -> _uiState.value = HomeUiState.Error(result.message)
            }
        }
    }

    fun processAudio(audioData: ByteArray) {
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
    data class Loading(val message: String) : HomeUiState()
    data class Success(val eventTitle: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
