package com.calendaradd.service

import android.content.Context
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Engine
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
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
}
