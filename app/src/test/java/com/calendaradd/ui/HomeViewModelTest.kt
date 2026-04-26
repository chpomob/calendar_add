package com.calendaradd.ui

import com.calendaradd.service.BackgroundAnalysisScheduler
import com.calendaradd.service.DownloadStatus
import com.calendaradd.service.GemmaLlmService
import com.calendaradd.service.LiteRtModelCatalog
import com.calendaradd.service.ModelDownloadManager
import com.calendaradd.service.PendingWorkStatus
import com.calendaradd.usecase.CalendarUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false
        )

        val viewModel = HomeViewModel(
            calendarUseCase = calendarUseCase,
            gemmaLlmService = gemmaLlmService,
            modelDownloadManager = modelDownloadManager,
            backgroundAnalysisScheduler = backgroundAnalysisScheduler
        )

        advanceUntilIdle()

        assertTrue(viewModel.isModelReady.first())
        assertEquals(HomeUiState.Idle, viewModel.uiState.first())
        coVerify(exactly = 0) { gemmaLlmService.initialize(any(), any(), any(), any()) }
    }

    @Test
    fun `downloadModel should keep pending work models during cleanup`() = runTest(dispatcher) {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        val queuedModel = LiteRtModelCatalog.find("gemma-4-e4b")

        every { modelDownloadManager.getSelectedModel() } returns currentModel
        every { modelDownloadManager.isModelDownloaded(currentModel) } returns false
        every { modelDownloadManager.hasEnoughSpace(currentModel) } returns true
        every { modelDownloadManager.startDownload(currentModel) } returns 42L
        every { modelDownloadManager.trackProgress(42L) } returns flowOf(DownloadStatus.Success)
        every { modelDownloadManager.cleanupUnusedModelFiles(any()) } returns Unit
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false
        )
        coEvery { backgroundAnalysisScheduler.getPendingModels() } returns setOf(queuedModel)

        val viewModel = HomeViewModel(
            calendarUseCase = calendarUseCase,
            gemmaLlmService = gemmaLlmService,
            modelDownloadManager = modelDownloadManager,
            backgroundAnalysisScheduler = backgroundAnalysisScheduler
        )

        advanceUntilIdle()
        viewModel.downloadModel()
        advanceUntilIdle()

        io.mockk.verify {
            modelDownloadManager.cleanupUnusedModelFiles(match { keepModels ->
                keepModels.map { it.id }.toSet() == setOf(currentModel.id, queuedModel.id)
            })
        }
    }
}
