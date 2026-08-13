package com.calendaradd.ui

import com.calendaradd.service.BackgroundAnalysisScheduler
import com.calendaradd.service.DownloadStatus
import com.calendaradd.service.GemmaLlmService
import com.calendaradd.service.LiteRtModelCatalog
import com.calendaradd.service.ModelDownloadManager
import com.calendaradd.service.NetworkPolicy
import com.calendaradd.service.PendingWorkStatus
import com.calendaradd.usecase.CalendarUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun defaultModel() = LiteRtModelCatalog.find(LiteRtModelCatalog.DEFAULT_MODEL_ID)

    /** Common init-refresh stubs that drive the ViewModel into ModelMissing after construction. */
    private fun stubMissing(model: com.calendaradd.service.LiteRtModelConfig = defaultModel()) {
        every { modelDownloadManager.getSelectedModel() } returns model
        every { modelDownloadManager.isModelDownloaded(any()) } returns false
        every { modelDownloadManager.hasEnoughSpace(any()) } returns true
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false
        )
    }

    private fun buildViewModel(model: com.calendaradd.service.LiteRtModelConfig = defaultModel()): HomeViewModel =
        HomeViewModel(
            calendarUseCase = calendarUseCase,
            gemmaLlmService = gemmaLlmService,
            modelDownloadManager = modelDownloadManager,
            backgroundAnalysisScheduler = backgroundAnalysisScheduler,
            ioDispatcher = dispatcher
        )

    @Test
    fun `refreshModelState should mark downloaded model ready without initializing engine`() = runTest(dispatcher) {
        val model = defaultModel()
        every { modelDownloadManager.getSelectedModel() } returns model
        every { modelDownloadManager.isModelDownloaded(model) } returns true
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false
        )

        val viewModel = buildViewModel(model)
        advanceUntilIdle()

        assertTrue(viewModel.isModelReady.first())
        assertEquals(HomeUiState.Idle, viewModel.uiState.first())
        coVerify(exactly = 0) { gemmaLlmService.initialize(any(), any(), any(), any()) }
    }

    @Test
    fun `requestDownload should keep pending work models during cleanup`() = runTest(dispatcher) {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        val queuedModel = LiteRtModelCatalog.find("gemma-4-e4b")

        every { modelDownloadManager.getSelectedModel() } returns currentModel
        every { modelDownloadManager.isModelDownloaded(any()) } returns false
        every { modelDownloadManager.hasEnoughSpace(any()) } returns true
        every { modelDownloadManager.startDownload(any(), any()) } returns 42L
        every { modelDownloadManager.trackProgress(42L, currentModel, any()) } returns flowOf(DownloadStatus.Success)
        every { modelDownloadManager.cleanupUnusedModelFiles(any()) } returns Unit
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false
        )
        coEvery { backgroundAnalysisScheduler.getPendingModels() } returns setOf(queuedModel)

        val viewModel = buildViewModel(currentModel)
        advanceUntilIdle()
        viewModel.requestDownload(NetworkCondition.UNMETERED_NON_ROAMING)
        advanceUntilIdle()

        verify {
            modelDownloadManager.cleanupUnusedModelFiles(match { keepModels ->
                keepModels.size == 2 && keepModels.any { it === queuedModel }
            })
        }
    }

    // ----- R3 / AC3: policy selection by network condition -----

    @Test
    fun `AC3 unmetered non-roaming download starts directly under conservative policy`() = runTest(dispatcher) {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        stubMissing(currentModel)
        every { modelDownloadManager.startDownload(any(), any()) } returns 42L
        every { modelDownloadManager.trackProgress(42L, currentModel, any()) } returns flowOf(DownloadStatus.Success)

        val viewModel = buildViewModel(currentModel)
        advanceUntilIdle()
        viewModel.requestDownload(NetworkCondition.UNMETERED_NON_ROAMING)
        advanceUntilIdle()

        verify { modelDownloadManager.startDownload(currentModel, NetworkPolicy.CONSERVATIVE) }
    }

    @Test
    fun `AC3 metered download surfaces consent and denial keeps model missing`() = runTest(dispatcher) {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        stubMissing(currentModel)

        val viewModel = buildViewModel(currentModel)
        advanceUntilIdle()

        viewModel.requestDownload(NetworkCondition.METERED_OR_ROAMING)
        // Consent is set synchronously; no download is enqueued.
        val state = viewModel.uiState.first()
        assertTrue(state is HomeUiState.DownloadConsentNeeded)
        assertEquals(currentModel, (state as HomeUiState.DownloadConsentNeeded).consent.model)
        verify(exactly = 0) { modelDownloadManager.startDownload(any(), any()) }

        viewModel.denyConsent()
        assertEquals(HomeUiState.ModelMissing, viewModel.uiState.first())
        assertFalse(viewModel.isModelReady.first())
        verify(exactly = 0) { modelDownloadManager.startDownload(any(), any()) }
    }

    @Test
    fun `AC3 affirmative consent authorizes exactly one unrestricted download`() = runTest(dispatcher) {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        stubMissing(currentModel)
        every { modelDownloadManager.startDownload(any(), any()) } returns 42L
        every { modelDownloadManager.trackProgress(42L, currentModel, any()) } returns flowOf(DownloadStatus.Success)

        val viewModel = buildViewModel(currentModel)
        advanceUntilIdle()

        viewModel.requestDownload(NetworkCondition.METERED_OR_ROAMING)
        val token = (viewModel.uiState.first() as HomeUiState.DownloadConsentNeeded).consent.token
        viewModel.confirmConsentedDownload(token)
        advanceUntilIdle()

        verify(exactly = 1) { modelDownloadManager.startDownload(currentModel, NetworkPolicy.UNRESTRICTED) }
    }

    @Test
    fun `AC3 replayed confirm cannot authorize a second download`() = runTest(dispatcher) {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        stubMissing(currentModel)
        every { modelDownloadManager.startDownload(any(), any()) } returns 42L
        every { modelDownloadManager.trackProgress(42L, currentModel, any()) } returns flowOf(DownloadStatus.Success)

        val viewModel = buildViewModel(currentModel)
        advanceUntilIdle()

        viewModel.requestDownload(NetworkCondition.METERED_OR_ROAMING)
        val token = (viewModel.uiState.first() as HomeUiState.DownloadConsentNeeded).consent.token
        viewModel.confirmConsentedDownload(token)
        advanceUntilIdle()

        // Consent was consumed; a replayed confirm with the same token is a no-op.
        viewModel.confirmConsentedDownload(token)
        advanceUntilIdle()

        verify(exactly = 1) { modelDownloadManager.startDownload(any(), any()) }
    }

    @Test
    fun `AC3 stale or mismatched consent token is rejected and keeps consent pending`() = runTest(dispatcher) {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        stubMissing(currentModel)
        every { modelDownloadManager.startDownload(any(), any()) } returns 42L
        every { modelDownloadManager.trackProgress(42L, currentModel, any()) } returns flowOf(DownloadStatus.Success)

        val viewModel = buildViewModel(currentModel)
        advanceUntilIdle()

        viewModel.requestDownload(NetworkCondition.METERED_OR_ROAMING)
        val actualToken = (viewModel.uiState.first() as HomeUiState.DownloadConsentNeeded).consent.token
        val staleToken = actualToken + 999L

        viewModel.confirmConsentedDownload(staleToken)
        // No download started and the consent is still pending.
        verify(exactly = 0) { modelDownloadManager.startDownload(any(), any()) }
        assertTrue(viewModel.uiState.first() is HomeUiState.DownloadConsentNeeded)

        // The correct token still works (consent was not consumed by the stale attempt).
        viewModel.confirmConsentedDownload(actualToken)
        advanceUntilIdle()
        verify(exactly = 1) { modelDownloadManager.startDownload(currentModel, NetworkPolicy.UNRESTRICTED) }
    }

    @Test
    fun `AC3 confirm with no pending consent is a no-op`() = runTest(dispatcher) {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        stubMissing(currentModel)
        every { modelDownloadManager.startDownload(any(), any()) } returns 42L

        val viewModel = buildViewModel(currentModel)
        advanceUntilIdle()

        // No requestDownload() was called, so there is nothing to confirm.
        viewModel.confirmConsentedDownload(1L)
        advanceUntilIdle()
        verify(exactly = 0) { modelDownloadManager.startDownload(any(), any()) }
    }

    // ----- R2 / AC2: recoverable refresh -----

    @Test
    fun `AC2 refresh exposes retryable error when scheduler lookup is inconclusive and recovers on retry`() = runTest(dispatcher) {
        val model = defaultModel()
        every { modelDownloadManager.getSelectedModel() } returns model
        // A1: the production scheduler does NOT throw on a WorkManager lookup failure or
        // deadline — it returns a non-throwing status with reconcileSucceeded=false. Refresh
        // must still reach the retryable RefreshFailed state from that status.
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false,
            reconcileSucceeded = false
        )

        val viewModel = buildViewModel(model)
        advanceUntilIdle()

        val failed = viewModel.uiState.first()
        assertTrue("expected RefreshFailed, was $failed", failed is HomeUiState.RefreshFailed)
        assertFalse(viewModel.isModelReady.first())

        // Later refresh succeeds once the scheduler is healthy again.
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false
        )
        every { modelDownloadManager.isModelDownloaded(model) } returns true
        viewModel.refreshModelState()
        advanceUntilIdle()

        assertTrue(viewModel.isModelReady.first())
        assertEquals(HomeUiState.Idle, viewModel.uiState.first())
    }

    @Test
    fun `A2 refresh failure drops readiness so the error stays visible`() = runTest(dispatcher) {
        val model = defaultModel()
        every { modelDownloadManager.getSelectedModel() } returns model
        every { modelDownloadManager.isModelDownloaded(model) } returns true
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false
        )

        val viewModel = buildViewModel(model)
        advanceUntilIdle()
        assertTrue("precondition: model ready", viewModel.isModelReady.first())

        // A later refresh fails after readiness was established (e.g. storage lookup throws).
        every { modelDownloadManager.isModelDownloaded(model) } throws java.io.IOException("sha stream failed")
        viewModel.refreshModelState()
        advanceUntilIdle()

        // Readiness must drop, otherwise CalendarHomeScreen renders only the ready-state UI
        // and the RefreshFailed message + Retry button (in its !isModelReady branch) never show.
        assertFalse(viewModel.isModelReady.first())
        assertTrue(viewModel.uiState.first() is HomeUiState.RefreshFailed)
    }

    @Test
    fun `AC2 refresh exposes retryable error when storage lookup throws`() = runTest(dispatcher) {
        val model = defaultModel()
        every { modelDownloadManager.getSelectedModel() } returns model
        coEvery { backgroundAnalysisScheduler.reconcilePendingWork() } returns PendingWorkStatus(
            hasPendingWork = false,
            clearedStaleWork = false
        )
        every { modelDownloadManager.isModelDownloaded(model) } throws java.io.IOException("sha stream failed")

        val viewModel = buildViewModel(model)
        advanceUntilIdle()

        val failed = viewModel.uiState.first()
        assertTrue("expected RefreshFailed, was $failed", failed is HomeUiState.RefreshFailed)
        assertNotNull((failed as HomeUiState.RefreshFailed).message)
        assertFalse(viewModel.isModelReady.first())
    }
}
