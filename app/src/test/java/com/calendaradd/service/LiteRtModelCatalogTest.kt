package com.calendaradd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtModelCatalogTest {

    @Test
    fun `default model is Gemma 4 E2B`() {
        assertEquals("gemma-4-e2b", LiteRtModelCatalog.DEFAULT_MODEL_ID)
        assertEquals("Gemma 4 E2B", LiteRtModelCatalog.find(null).displayName)
    }

    @Test
    fun `Gemma 3n supports text image and audio`() {
        val e2b = LiteRtModelCatalog.find("gemma-3n-e2b")
        val model = LiteRtModelCatalog.find("gemma-3n-e4b")

        assertTrue(e2b.supportsText)
        assertTrue(e2b.supportsImage)
        assertTrue(e2b.supportsAudio)
        assertEquals("Gemma 3n E2B", e2b.displayName)
        assertTrue(model.supportsText)
        assertTrue(model.supportsImage)
        assertTrue(model.supportsAudio)
        assertEquals("Gemma 3n E4B", model.displayName)
    }

    @Test
    fun `Qwen models do not advertise audio support`() {
        val qwen = LiteRtModelCatalog.find("qwen-3_5-4b")

        assertTrue(qwen.supportsText)
        assertTrue(qwen.supportsImage)
        assertFalse(qwen.supportsAudio)
    }

    @Test
    fun `Qwen models use conservative token caps`() {
        val qwenSmall = LiteRtModelCatalog.find("qwen-3_5-0_8b")
        val qwenLarge = LiteRtModelCatalog.find("qwen-3_5-4b")

        assertEquals(1024, qwenSmall.maxNumTokens)
        assertEquals(512, qwenLarge.maxNumTokens)
    }
}
