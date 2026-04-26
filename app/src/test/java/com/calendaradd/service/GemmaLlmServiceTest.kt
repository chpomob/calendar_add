package com.calendaradd.service

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Engine
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class GemmaLlmServiceTest {

    private lateinit var context: Context
    private lateinit var service: GemmaLlmService
    private lateinit var engine: Engine
    private val servicesToClose = mutableListOf<GemmaLlmService>()

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.cacheDir } returns File("build/tmp/gemma-service-test-cache")
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/tmp/calendar-add-native-libs"
        }
        engine = mockk(relaxed = true)

        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine = engine
        }
        servicesToClose += service
    }

    @After
    fun tearDown() {
        servicesToClose.asReversed().forEach { it.close() }
        servicesToClose.clear()
        unmockkAll()
    }

    @Test
    fun `initialize should not create a conversation eagerly`() = runBlocking {
        service.initialize("/tmp/fake-model.litertlm")

        verify(exactly = 0) { engine.createConversation(any()) }
    }

    @Test
    fun `initialize should prefer NPU for Gemma text image and audio`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize("/tmp/fake-model.litertlm")

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.NPU::class.java.name, config.backend::class.java.name)
        assertEquals(Backend.NPU::class.java.name, requireNotNull(config.visionBackend)::class.java.name)
        assertEquals(Backend.NPU::class.java.name, requireNotNull(config.audioBackend)::class.java.name)
        assertEquals("/tmp/calendar-add-native-libs", (config.backend as Backend.NPU).nativeLibraryDir)
        assertEquals(
            "/tmp/calendar-add-native-libs",
            (requireNotNull(config.visionBackend) as Backend.NPU).nativeLibraryDir
        )
        assertEquals(
            "/tmp/calendar-add-native-libs",
            (requireNotNull(config.audioBackend) as Backend.NPU).nativeLibraryDir
        )
    }

    @Test
    fun `initialize should apply Gemma extraction token cap`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-gemma-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e2b")
        )

        assertEquals(768, requireNotNull(capturedConfig).maxNumTokens)
    }

    @Test
    fun `initialize should fall back to mixed NPU and CPU when multimodal NPU fails`() = runBlocking {
        val capturedConfigs = mutableListOf<EngineConfig>()
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfigs += config
                if (capturedConfigs.size == 1) {
                    throw IllegalStateException("synthetic multimodal NPU failure")
                }
                return engine
            }
        }
        servicesToClose += service

        service.initialize("/tmp/fake-model.litertlm")

        assertEquals(2, capturedConfigs.size)
        assertEquals(Backend.NPU::class.java.name, capturedConfigs[0].backend::class.java.name)
        assertEquals(Backend.NPU::class.java.name, requireNotNull(capturedConfigs[0].visionBackend)::class.java.name)
        assertEquals(Backend.NPU::class.java.name, requireNotNull(capturedConfigs[0].audioBackend)::class.java.name)
        assertEquals("/tmp/calendar-add-native-libs", (capturedConfigs[0].backend as Backend.NPU).nativeLibraryDir)
        assertEquals(Backend.NPU::class.java.name, capturedConfigs[1].backend::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(capturedConfigs[1].visionBackend)::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(capturedConfigs[1].audioBackend)::class.java.name)
        assertEquals("/tmp/calendar-add-native-libs", (capturedConfigs[1].backend as Backend.NPU).nativeLibraryDir)
        assertEquals("NPU(text)+CPU(vision/audio)", service.lastBackendUsed)
    }

    @Test
    fun `initialize should fall back to CPU when NPU native loading fails`() = runBlocking {
        val capturedConfigs = mutableListOf<EngineConfig>()
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfigs += config
                if (config.backend is Backend.NPU) {
                    throw UnsatisfiedLinkError("synthetic NPU native load failure")
                }
                return engine
            }
        }
        servicesToClose += service

        service.initialize("/tmp/fake-model.litertlm")

        assertEquals(3, capturedConfigs.size)
        assertEquals(Backend.NPU::class.java.name, capturedConfigs[0].backend::class.java.name)
        assertEquals(Backend.NPU::class.java.name, capturedConfigs[1].backend::class.java.name)
        assertEquals(Backend.CPU::class.java.name, capturedConfigs[2].backend::class.java.name)
        assertEquals("CPU", service.lastBackendUsed)
    }

    @Test
    fun `initialize should keep Qwen on CPU-only and omit audio backend`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-qwen-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("qwen-3_5-0_8b")
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.CPU::class.java.name, config.backend::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(config.visionBackend)::class.java.name)
        assertEquals(null, config.audioBackend)
        assertEquals(1024, config.maxNumTokens)
    }

    @Test
    fun `initialize should record attempted backend summary on failure`() = runBlocking {
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                throw IllegalStateException("synthetic failure")
            }
        }
        servicesToClose += service

        try {
            service.initialize(
                modelPath = "/tmp/fake-qwen-model.litertlm",
                modelConfig = LiteRtModelCatalog.find("qwen-3_5-0_8b")
            )
        } catch (_: IllegalStateException) {
            // expected
        }

        val failure = service.lastInitializationFailure
        assertEquals(
            "attempted=CPU-only multimodal error=IllegalStateException: synthetic failure",
            failure
        )
    }

    @Test
    fun `initialize should close a previously active service instance in the same process`() = runBlocking {
        val firstEngine = mockk<Engine>(relaxed = true)
        val secondEngine = mockk<Engine>(relaxed = true)
        val firstService = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine = firstEngine
        }
        val secondService = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine = secondEngine
        }
        servicesToClose += firstService
        servicesToClose += secondService

        firstService.initialize("/tmp/fake-model-a.litertlm")
        secondService.initialize("/tmp/fake-model-b.litertlm")

        verify(exactly = 1) { firstEngine.close() }
        verify(exactly = 1) { secondEngine.initialize() }
    }
}
