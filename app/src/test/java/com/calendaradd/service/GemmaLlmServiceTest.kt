package com.calendaradd.service

import android.content.Context
import com.google.ai.edge.litertlm.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class GemmaLlmServiceTest {

    private lateinit var context: Context
    private lateinit var service: GemmaLlmService
    private val modelPath = "/path/to/model"
    private val cacheDir = File("/tmp/cache")

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.cacheDir } returns cacheDir
        service = GemmaLlmService(context)

        mockkStatic(Backend::class)
        mockkConstructor(Engine::class)
        mockkConstructor(EngineConfig::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initialize should use NPU when available`() = runBlocking {
        // Given
        val mockNpu = mockk<Backend.NPU>()
        every { Backend.NPU() } returns mockNpu
        
        every { anyConstructed<Engine>().initialize() } just Runs
        every { anyConstructed<Engine>().createConversation(any()) } returns mockk(relaxed = true)

        // When
        service.initialize(modelPath)

        // Then
        assertEquals("NPU", service.lastBackendUsed)
        verify { Backend.NPU() }
    }

    @Test
    fun `initialize should fallback to CPU when NPU fails`() = runBlocking {
        // Given
        val mockNpu = mockk<Backend.NPU>()
        val mockCpu = mockk<Backend.CPU>()
        every { Backend.NPU() } returns mockNpu
        every { Backend.CPU() } returns mockCpu

        var initCount = 0
        every { anyConstructed<Engine>().initialize() } answers {
            initCount++
            if (initCount == 1) throw RuntimeException("NPU Fail")
            // second call succeeds
        }
        every { anyConstructed<Engine>().createConversation(any()) } returns mockk(relaxed = true)

        // When
        service.initialize(modelPath)

        // Then
        assertEquals("CPU", service.lastBackendUsed)
        verify { Backend.NPU() }
        verify { Backend.CPU() }
    }
}
