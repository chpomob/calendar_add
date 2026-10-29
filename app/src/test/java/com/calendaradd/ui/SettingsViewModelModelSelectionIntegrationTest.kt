package com.calendaradd.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import com.calendaradd.service.ApkDownloadManager
import com.calendaradd.service.LiteRtModelCatalog
import com.calendaradd.service.UpdateCheckerService
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.ApkInstaller
import com.calendaradd.util.InstallStatusReceiver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * R5/R7, AC16 (reinforcing AC13): every explicit Settings choice survives the StateFlow, the
 * backing preference storage, a later getter read and a reconstructed [PreferencesManager] —
 * including Compact, which the memory-aware first-selection rule must never overwrite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelModelSelectionIntegrationTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var context: Context
    private lateinit var modelDir: File
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var updateCheckerService: UpdateCheckerService
    private lateinit var apkDownloadManager: ApkDownloadManager
    private lateinit var apkInstaller: ApkInstaller
    private val viewModels = mutableListOf<SettingsViewModel>()

    private val catalogueIds = listOf("gemma-4-e2b", "gemma-4-e2b-compact", "gemma-4-e4b", "qwen-3_5-0_8b")

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        InstallStatusReceiver.reset()
        prefs = FakeSharedPreferences()
        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        modelDir = File("build/tmp/settings-model-selection-test").apply {
            deleteRecursively()
            mkdirs()
        }
        calendarUseCase = mockk(relaxed = true)
        coEvery { calendarUseCase.getAvailableCalendars() } returns emptyList()
        coEvery { calendarUseCase.retryPendingMigration() } returns
            CalendarUseCase.MigrationResult(movedCount = 0, failedCount = 0)
        updateCheckerService = mockk(relaxed = true)
        apkDownloadManager = mockk(relaxed = true)
        apkInstaller = mockk(relaxed = true)
        every { apkInstaller.consumeLastInstallOutcome() } returns null
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        InstallStatusReceiver.reset()
        Dispatchers.resetMain()
        modelDir.deleteRecursively()
    }

    /** Simulated 16 GB device: the memory rule alone would resolve to Gemma 4 E4B. */
    private fun manager(memoryGb: Double? = 16.0) = PreferencesManager(
        context,
        deviceMemoryGbProvider = { memoryGb },
        modelStorageRootProvider = { modelDir }
    )

    private fun buildViewModel(preferencesManager: PreferencesManager) = SettingsViewModel(
        calendarUseCase = calendarUseCase,
        preferencesManager = preferencesManager,
        updateCheckerService = updateCheckerService,
        apkDownloadManager = apkDownloadManager,
        apkInstaller = apkInstaller
    ).also { viewModels += it }

    @Test
    fun `availableModels exposes exactly the four catalogue ids in order`() = runTest(dispatcher) {
        val viewModel = buildViewModel(manager())
        advanceUntilIdle()

        assertEquals(catalogueIds, viewModel.availableModels.value.map { it.id })
    }

    @Test
    fun `every explicit selection persists through flow, storage, getter and a new manager`() = runTest(dispatcher) {
        val preferencesManager = manager()
        val viewModel = buildViewModel(preferencesManager)
        advanceUntilIdle()
        // No stored choice and no E2B file on a 16 GB device: the first read resolves E4B.
        assertEquals("gemma-4-e4b", viewModel.selectedModelId.value)

        for (id in catalogueIds) {
            viewModel.selectModel(id)
            advanceUntilIdle()

            assertEquals(id, viewModel.selectedModelId.value)
            assertEquals(id, prefs.values["selected_model_id"])
            assertEquals(id, preferencesManager.selectedModelId)
            assertEquals(id, manager().selectedModelId)
            // Reads must not rewrite the explicit choice.
            assertEquals(id, prefs.values["selected_model_id"])
        }
    }

    @Test
    fun `Compact survives a later high-memory read and a rebuilt Settings screen`() = runTest(dispatcher) {
        val viewModel = buildViewModel(manager())
        advanceUntilIdle()

        viewModel.selectModel("gemma-4-e2b-compact")
        advanceUntilIdle()

        // An E2B file on disk would also steer an unset selection; it must not override Compact.
        File(modelDir, LiteRtModelCatalog.find(LiteRtModelCatalog.E2B_MODEL_ID).fileName).writeText("model")
        for (memoryGb in listOf(16.0, 32.0)) {
            val highMemoryManager = manager(memoryGb)
            assertEquals("gemma-4-e2b-compact", highMemoryManager.selectedModelId)
            assertEquals("gemma-4-e2b-compact", prefs.values["selected_model_id"])

            val rebuilt = buildViewModel(highMemoryManager)
            advanceUntilIdle()
            assertEquals("gemma-4-e2b-compact", rebuilt.selectedModelId.value)
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? =
            if (values.containsKey(key)) values[key] as String? else defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            if (values.containsKey(key)) values[key] as MutableSet<String>? else defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { values[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) = apply { values[key] = value }
            override fun putInt(key: String, value: Int) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putFloat(key: String, value: Float) = apply { values[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
