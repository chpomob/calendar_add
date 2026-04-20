package com.calendaradd.service

import android.content.Context
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

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.cacheDir } returns File("build/tmp/gemma-service-test-cache")
        engine = mockk(relaxed = true)

        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine = engine
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initialize should not create a conversation eagerly`() = runBlocking {
        service.initialize("/tmp/fake-model.litertlm")

        verify(exactly = 0) { engine.createConversation(any()) }
    }

    @Test
    fun `initialize should keep vision and audio on CPU when text uses NPU`() = runBlocking {
        var capturedConfig: EngineConfig? = null
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                capturedConfig = config
                return engine
            }
        }

        service.initialize("/tmp/fake-model.litertlm")

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.NPU::class.java.name, config.backend::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(config.visionBackend)::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(config.audioBackend)::class.java.name)
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

        service.initialize(
            modelPath = "/tmp/fake-qwen-model.litertlm",
            modelConfig = LiteRtModelCatalog.find("qwen-3_5-4b")
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(Backend.CPU::class.java.name, config.backend::class.java.name)
        assertEquals(Backend.CPU::class.java.name, requireNotNull(config.visionBackend)::class.java.name)
        assertEquals(null, config.audioBackend)
        assertEquals(512, config.maxNumTokens)
    }

    @Test
    fun `initialize should record attempted backend summary on failure`() = runBlocking {
        service = object : GemmaLlmService(context) {
            override fun createEngine(config: EngineConfig): Engine {
                throw IllegalStateException("synthetic failure")
            }
        }

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
}
