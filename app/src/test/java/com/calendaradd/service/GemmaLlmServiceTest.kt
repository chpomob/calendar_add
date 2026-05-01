package com.calendaradd.service

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns null
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
    fun `initialize should prefer GPU for Gemma text and vision with CPU audio`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e2b")
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.GPU::class.java.name, config.backend::class.java.name)
        assertEquals(Backend.GPU::class.java.name, requireNotNull(config.visionBackend)::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(config.audioBackend)::class.java.name)
        assertEquals(null, config.cacheDir)
    }

    @Test
    fun `initialize should disable audio backend for image-only Gemma work`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e4b"),
            enableImage = true,
            enableAudio = false
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.GPU::class.java.name, config.backend::class.java.name)
        assertEquals(Backend.GPU::class.java.name, requireNotNull(config.visionBackend)::class.java.name)
        assertEquals(null, config.audioBackend)
        assertEquals("GPU(text)+GPU(vision)", service.lastBackendUsed)
    }

    @Test
    fun `initialize should avoid Gemma 4 E4B GPU text backend for multimodal work on 12GB devices`() = runBlocking {
        val activityManager = mockk<ActivityManager>()
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { activityManager.getMemoryInfo(any()) } answers {
            firstArg<ActivityManager.MemoryInfo>().totalMem = 12L * 1024L * 1024L * 1024L
            Unit
        }
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e4b"),
            enableImage = true,
            enableAudio = false
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.CPU::class.java.name, config.backend::class.java.name)
        assertEquals(Backend.GPU::class.java.name, requireNotNull(config.visionBackend)::class.java.name)
        assertEquals(null, config.audioBackend)
        assertEquals(768, config.maxNumTokens)
        assertEquals("CPU(text)+GPU(vision)", service.lastBackendUsed)
    }

    @Test
    fun `initialize should disable vision backend for audio-only Gemma work`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e4b"),
            enableImage = false,
            enableAudio = true
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.GPU::class.java.name, config.backend::class.java.name)
        assertEquals(null, config.visionBackend)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(config.audioBackend)::class.java.name)
        assertEquals("GPU(text)+CPU(audio)", service.lastBackendUsed)
    }

    @Test
    fun `initialize should disable multimodal backends for text-only Gemma work`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e4b"),
            enableImage = false,
            enableAudio = false
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.GPU::class.java.name, config.backend::class.java.name)
        assertEquals(null, config.visionBackend)
        assertEquals(null, config.audioBackend)
        assertEquals("GPU(text)", service.lastBackendUsed)
    }

    @Test
    fun `initialize should use Gallery backend order for Gemma 3n`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-gemma3n-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-3n-e2b")
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.CPU::class.java.name, config.backend::class.java.name)
        assertEquals(Backend.GPU::class.java.name, requireNotNull(config.visionBackend)::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(config.audioBackend)::class.java.name)
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
    fun `initialize should rebuild engine when modality changes for the same model path`() = runBlocking {
        val firstEngine = mockk<Engine>(relaxed = true)
        val secondEngine = mockk<Engine>(relaxed = true)
        val capturedConfigs = mutableListOf<EngineConfig>()
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfigs += config
                return if (capturedConfigs.size == 1) firstEngine else secondEngine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e4b"),
            enableImage = false,
            enableAudio = false
        )
        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e4b"),
            enableImage = true,
            enableAudio = false
        )

        assertEquals(2, capturedConfigs.size)
        assertEquals(null, capturedConfigs[0].visionBackend)
        assertEquals(Backend.GPU::class.java.name, requireNotNull(capturedConfigs[1].visionBackend)::class.java.name)
        verify(exactly = 1) { firstEngine.close() }
        verify(exactly = 1) { secondEngine.initialize() }
    }

    @Test
    fun `initialize should fall back to CPU vision when GPU vision fails`() = runBlocking {
        val capturedConfigs = mutableListOf<EngineConfig>()
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfigs += config
                if (capturedConfigs.size == 1) {
                    throw IllegalStateException("synthetic multimodal GPU failure")
                }
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e2b")
        )

        assertEquals(2, capturedConfigs.size)
        assertEquals(Backend.GPU::class.java.name, capturedConfigs[0].backend::class.java.name)
        assertEquals(Backend.GPU::class.java.name, requireNotNull(capturedConfigs[0].visionBackend)::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(capturedConfigs[0].audioBackend)::class.java.name)
        assertEquals(Backend.GPU::class.java.name, capturedConfigs[1].backend::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(capturedConfigs[1].visionBackend)::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(capturedConfigs[1].audioBackend)::class.java.name)
        assertEquals("GPU(text)+CPU(vision)+CPU(audio)", service.lastBackendUsed)
    }

    @Test
    fun `initialize should fall back to CPU when GPU loading fails`() = runBlocking {
        val capturedConfigs = mutableListOf<EngineConfig>()
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfigs += config
                if (config.backend is Backend.GPU || config.visionBackend is Backend.GPU) {
                    throw UnsatisfiedLinkError("synthetic GPU load failure")
                }
                return engine
            }
        }
        servicesToClose += service

        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e2b")
        )

        assertEquals(4, capturedConfigs.size)
        assertEquals(Backend.GPU::class.java.name, capturedConfigs[0].backend::class.java.name)
        assertEquals(Backend.GPU::class.java.name, capturedConfigs[1].backend::class.java.name)
        assertEquals(Backend.CPU::class.java.name, capturedConfigs[2].backend::class.java.name)
        assertEquals(Backend.GPU::class.java.name, requireNotNull(capturedConfigs[2].visionBackend)::class.java.name)
        assertEquals(Backend.CPU::class.java.name, capturedConfigs[3].backend::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(capturedConfigs[3].visionBackend)::class.java.name)
        assertEquals("CPU(text)+CPU(vision)+CPU(audio)", service.lastBackendUsed)
    }

    @Test
    fun `initialize should reject models above Gallery minimum memory before engine creation`() = runBlocking {
        val activityManager = mockk<ActivityManager>()
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { activityManager.getMemoryInfo(any()) } answers {
            firstArg<ActivityManager.MemoryInfo>().totalMem = 7L * 1024L * 1024L * 1024L
            Unit
        }
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                fail("Engine should not be created when device RAM is below the model minimum")
                return engine
            }
        }
        servicesToClose += service

        try {
            service.initialize(
                modelPath = "/tmp/fake-model.litertlm",
                modelConfig = LiteRtModelCatalog.find("gemma-4-e2b")
            )
            fail("Expected low-memory initialization failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("requires at least 8GB RAM"))
            assertEquals(e.message, service.lastInitializationFailure)
        }
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

    @Test
    fun `extractEventJson should collect async callback response`() = runBlocking {
        val conversation = mockk<Conversation>(relaxed = true)
        val message = mockk<Message> {
            every { contents } returns Contents.of(Content.Text("""{"events":[]}"""))
        }
        every { conversation.sendMessageAsync(any<Contents>(), any(), any()) } answers {
            val callback = secondArg<MessageCallback>()
            callback.onMessage(message)
            callback.onDone()
        }
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine = engine
            override fun createConversation(engine: Engine): Conversation = conversation
        }
        servicesToClose += service
        service.initialize("/tmp/fake-model.litertlm")

        val result = service.extractEventJson("extract an event")

        assertEquals("""{"events":[]}""", result)
        verify(exactly = 1) { conversation.sendMessageAsync(any<Contents>(), any(), any()) }
    }

    @Test
    fun `extractEventJson should create conversations with Gallery sampler config`() = runBlocking {
        val conversation = mockk<Conversation>(relaxed = true)
        val conversationConfig = io.mockk.slot<ConversationConfig>()
        val message = mockk<Message> {
            every { contents } returns Contents.of(Content.Text("""{"events":[]}"""))
        }
        every { engine.createConversation(capture(conversationConfig)) } returns conversation
        every { conversation.sendMessageAsync(any<Contents>(), any(), any()) } answers {
            val callback = secondArg<MessageCallback>()
            callback.onMessage(message)
            callback.onDone()
        }
        service.initialize(
            modelPath = "/tmp/fake-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("gemma-4-e2b")
        )

        val result = service.extractEventJson("extract an event")

        assertEquals("""{"events":[]}""", result)
        val samplerConfig = requireNotNull(conversationConfig.captured.samplerConfig)
        assertEquals(64, samplerConfig.topK)
        assertEquals(0.95, samplerConfig.topP, 0.0)
        assertEquals(1.0, samplerConfig.temperature, 0.0)
    }
}
