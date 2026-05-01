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
        val qwen = LiteRtModelCatalog.find("qwen-3_5-0_8b")

        assertTrue(qwen.supportsText)
        assertTrue(qwen.supportsImage)
        assertFalse(qwen.supportsAudio)
    }

    @Test
    fun `Qwen model uses conservative token cap`() {
        val qwen = LiteRtModelCatalog.find("qwen-3_5-0_8b")

        assertEquals(1024, qwen.maxNumTokens)
    }

    @Test
    fun `Gemma models use bounded generation for extraction`() {
        val gemmaModels = LiteRtModelCatalog.models.filter {
            it.executionProfile == ModelExecutionProfile.ACCELERATED_GEMMA
        }

        assertTrue(gemmaModels.isNotEmpty())
        gemmaModels.forEach { model ->
            assertEquals("${model.id} should use the extraction token cap", 768, model.maxNumTokens)
        }
    }

    @Test
    fun `Gemma models use AI Edge Gallery backend orders and memory minimums`() {
        val gemma4E2b = LiteRtModelCatalog.find("gemma-4-e2b")
        val gemma4E4b = LiteRtModelCatalog.find("gemma-4-e4b")
        val gemma3nE2b = LiteRtModelCatalog.find("gemma-3n-e2b")
        val gemma3nE4b = LiteRtModelCatalog.find("gemma-3n-e4b")

        assertEquals(listOf(ModelBackendKind.GPU, ModelBackendKind.CPU), gemma4E2b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma4E2b.visionBackend)
        assertEquals(8, gemma4E2b.minimumDeviceMemoryGb)
        assertEquals(2_583_085_056L, gemma4E2b.sizeBytes)
        assertTrue(gemma4E2b.requireExactSize)
        assertTrue(gemma4E2b.downloadUrl.contains("/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/"))
        assertEquals(listOf(ModelBackendKind.GPU, ModelBackendKind.CPU), gemma4E4b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma4E4b.visionBackend)
        assertEquals(12, gemma4E4b.minimumDeviceMemoryGb)
        assertEquals(16, gemma4E4b.multimodalGpuMainMinimumMemoryGb)
        assertEquals(3_654_467_584L, gemma4E4b.sizeBytes)
        assertTrue(gemma4E4b.requireExactSize)
        assertTrue(gemma4E4b.downloadUrl.contains("/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/"))

        assertEquals(listOf(ModelBackendKind.CPU, ModelBackendKind.GPU), gemma3nE2b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma3nE2b.visionBackend)
        assertEquals(8, gemma3nE2b.minimumDeviceMemoryGb)
        assertEquals(3_655_827_456L, gemma3nE2b.sizeBytes)
        assertTrue(gemma3nE2b.requireExactSize)
        assertEquals(listOf(ModelBackendKind.CPU, ModelBackendKind.GPU), gemma3nE4b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma3nE4b.visionBackend)
        assertEquals(12, gemma3nE4b.minimumDeviceMemoryGb)
        assertEquals(4_919_541_760L, gemma3nE4b.sizeBytes)
        assertTrue(gemma3nE4b.requireExactSize)
    }
}
