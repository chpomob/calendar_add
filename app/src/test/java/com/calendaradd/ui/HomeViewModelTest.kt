package com.calendaradd.ui

import com.calendaradd.service.BackgroundAnalysisScheduler
import com.calendaradd.service.GemmaLlmService
import com.calendaradd.service.LiteRtModelCatalog
import com.calendaradd.service.ModelDownloadManager
import com.calendaradd.usecase.CalendarUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var gemmaLlmService: GemmaLlmService
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var backgroundAnalysisScheduler: BackgroundAnalysisScheduler

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        calendarUseCase = mockk(relaxed = true)
        gemmaLlmService = mockk(relaxed = true)
        modelDownloadManager = mockk(relaxed = true)
        backgroundAnalysisScheduler = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refreshModelState should mark downloaded model ready without initializing engine`() = runTest(dispatcher) {
        val model = LiteRtModelCatalog.find(LiteRtModelCatalog.DEFAULT_MODEL_ID)
        every { modelDownloadManager.getSelectedModel() } returns model
        every { modelDownloadManager.isModelDownloaded(model) } returns true
        coEvery { backgroundAnalysisScheduler.hasPendingWork() } returns false

        val viewModel = HomeViewModel(
            calendarUseCase = calendarUseCase,
            gemmaLlmService = gemmaLlmService,
            modelDownloadManager = modelDownloadManager,
            backgroundAnalysisScheduler = backgroundAnalysisScheduler
        )

        advanceUntilIdle()

        assertTrue(viewModel.isModelReady.first())
        assertEquals(HomeUiState.Idle, viewModel.uiState.first())
        coVerify(exactly = 0) { gemmaLlmService.initialize(any(), any()) }
    }
}
