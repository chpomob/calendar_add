package com.calendaradd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.calendaradd.service.*
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.PreferencesManager

class AppViewModelFactory(
    private val calendarUseCase: CalendarUseCase,
    private val gemmaLlmService: GemmaLlmService? = null,
    private val modelDownloadManager: ModelDownloadManager? = null,
    private val backgroundAnalysisScheduler: BackgroundAnalysisScheduler? = null,
    private val preferencesManager: PreferencesManager? = null,
    private val eventId: Long? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                HomeViewModel(
                    calendarUseCase,
                    gemmaLlmService!!,
                    modelDownloadManager!!,
                    backgroundAnalysisScheduler!!
                ) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                SettingsViewModel(calendarUseCase, preferencesManager!!) as T
            }
            modelClass.isAssignableFrom(DetailViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                DetailViewModel(eventId!!, calendarUseCase, preferencesManager!!) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
