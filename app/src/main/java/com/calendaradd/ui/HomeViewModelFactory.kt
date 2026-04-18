package com.calendaradd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.calendaradd.service.*
import com.calendaradd.usecase.CalendarUseCase

class HomeViewModelFactory(
    private val calendarUseCase: CalendarUseCase,
    private val gemmaLlmService: GemmaLlmService,
    private val modelDownloadManager: ModelDownloadManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(calendarUseCase, gemmaLlmService, modelDownloadManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
