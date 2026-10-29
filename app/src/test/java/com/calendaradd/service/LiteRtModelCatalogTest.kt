package com.calendaradd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtModelCatalogTest {

    @Test
    fun `default model is Gemma 4 E4B`() {
        assertEquals("gemma-4-e4b", LiteRtModelCatalog.DEFAULT_MODEL_ID)
        assertEquals("Gemma 4 E4B", LiteRtModelCatalog.find(null).displayName)
    }

    @Test
    fun `find falls back to Gemma 4 E4B for null and unknown ids`() {
        assertEquals("gemma-4-e4b", LiteRtModelCatalog.find(null).id)
        assertEquals("gemma-4-e4b", LiteRtModelCatalog.find("unknown").id)
    }

    @Test
    fun `catalog lists exactly four models in order`() {
        assertEquals(
            listOf("gemma-4-e2b", "gemma-4-e2b-compact", "gemma-4-e4b", "qwen-3_5-0_8b"),
            LiteRtModelCatalog.models.map { it.id }
        )
    }

    @Test
    fun `catalog contains no Gemma 3n entry`() {
        LiteRtModelCatalog.models.forEach { model ->
            assertFalse("${model.id} id", model.id.contains("gemma-3n"))
            assertFalse("${model.id} url", model.downloadUrl.contains("huggingface.co/google/gemma-3n"))
        }
    }

    @Test
    fun `E2B description no longer claims to be the default`() {
        assertFalse(LiteRtModelCatalog.find("gemma-4-e2b").description.contains("default", ignoreCase = true))
    }

    @Test
    fun `initialModelId picks E4B from 12 GB and E2B below or when unknown`() {
        assertEquals("gemma-4-e4b", LiteRtModelCatalog.initialModelId(12.0))
        assertEquals("gemma-4-e4b", LiteRtModelCatalog.initialModelId(16.0))
        assertEquals("gemma-4-e2b", LiteRtModelCatalog.initialModelId(11.99))
        assertEquals("gemma-4-e2b", LiteRtModelCatalog.initialModelId(8.0))
        assertEquals("gemma-4-e2b", LiteRtModelCatalog.initialModelId(6.0))
        assertEquals("gemma-4-e2b", LiteRtModelCatalog.initialModelId(4.0))
        assertEquals("gemma-4-e2b", LiteRtModelCatalog.initialModelId(null))
    }

    @Test
    fun `initialModelId never selects Compact or Qwen`() {
        listOf(null, 0.0, 4.0, 5.0, 6.0, 8.0, 11.99, 12.0, 16.0, 64.0).forEach { memoryGb ->
            val id = LiteRtModelCatalog.initialModelId(memoryGb)
            assertTrue("$memoryGb -> $id", id == "gemma-4-e2b" || id == "gemma-4-e4b")
        }
    }

    @Test
    fun `Gemma 4 E2B and E4B support text image and audio`() {
        val e2b = LiteRtModelCatalog.find("gemma-4-e2b")
        val e4b = LiteRtModelCatalog.find("gemma-4-e4b")

        assertTrue(e2b.supportsText)
        assertTrue(e2b.supportsImage)
        assertTrue(e2b.supportsAudio)
        assertEquals("Gemma 4 E2B", e2b.displayName)
        assertTrue(e4b.supportsText)
        assertTrue(e4b.supportsImage)
        assertTrue(e4b.supportsAudio)
        assertEquals("Gemma 4 E4B", e4b.displayName)
    }

    @Test
    fun `Qwen models do not advertise audio support`() {
        val qwen = LiteRtModelCatalog.find("qwen-3_5-0_8b")

        assertTrue(qwen.supportsText)
        assertTrue(qwen.supportsImage)
        assertFalse(qwen.supportsAudio)
    }

    @Test
    fun `Gemma compact profile reuses E2B file with text and image only`() {
        val normal = LiteRtModelCatalog.find("gemma-4-e2b")
        val compact = LiteRtModelCatalog.find("gemma-4-e2b-compact")

        assertEquals("Gemma 4 E2B Compact", compact.displayName)
        assertEquals(normal.downloadUrl, compact.downloadUrl)
        assertEquals(normal.fileName, compact.fileName)
        assertEquals(normal.sizeBytes, compact.sizeBytes)
        assertTrue(compact.supportsText)
        assertTrue(compact.supportsImage)
        assertFalse(compact.supportsAudio)
        assertEquals(1024, compact.maxNumTokens)
        assertEquals(listOf(ModelBackendKind.CPU), compact.mainBackendOrder)
        assertEquals(ModelBackendKind.CPU, compact.visionBackend)
        assertEquals(5, compact.minimumDeviceMemoryGb)
    }

    @Test
    fun `Qwen model uses conservative token cap`() {
        val qwen = LiteRtModelCatalog.find("qwen-3_5-0_8b")

        assertEquals(1024, qwen.maxNumTokens)
    }

    @Test
    fun `all model downloads are commit pinned and checksum pinned`() {
        LiteRtModelCatalog.models.forEach { model ->
            assertTrue("${model.id} URL should be commit pinned", model.downloadUrl.contains("/resolve/"))
            assertTrue("${model.id} sha256 should be pinned", model.sha256.matches(Regex("[a-f0-9]{64}")))
            assertEquals("${model.id} should require exact downloaded size", model.sizeBytes, model.minimumExpectedBytes)
        }
    }

    @Test
    fun `Gemma models use AI Edge Gallery token windows`() {
        assertEquals(4000, LiteRtModelCatalog.find("gemma-4-e2b").maxNumTokens)
        assertEquals(1024, LiteRtModelCatalog.find("gemma-4-e2b-compact").maxNumTokens)
        assertEquals(4000, LiteRtModelCatalog.find("gemma-4-e4b").maxNumTokens)
    }

    @Test
    fun `Gemma models use AI Edge Gallery backend orders and memory minimums`() {
        val gemma4E2b = LiteRtModelCatalog.find("gemma-4-e2b")
        val gemma4E4b = LiteRtModelCatalog.find("gemma-4-e4b")
        val compact = LiteRtModelCatalog.find("gemma-4-e2b-compact")
        val qwen = LiteRtModelCatalog.find("qwen-3_5-0_8b")

        assertEquals(listOf(ModelBackendKind.GPU, ModelBackendKind.CPU), gemma4E2b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma4E2b.visionBackend)
        assertEquals(8, gemma4E2b.minimumDeviceMemoryGb)
        assertEquals(2_583_085_056L, gemma4E2b.sizeBytes)
        assertTrue(gemma4E2b.downloadUrl.contains("/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/"))
        assertEquals(listOf(ModelBackendKind.CPU, ModelBackendKind.GPU), gemma4E4b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma4E4b.visionBackend)
        assertEquals(12, gemma4E4b.minimumDeviceMemoryGb)
        assertEquals(3_654_467_584L, gemma4E4b.sizeBytes)
        assertTrue(gemma4E4b.downloadUrl.contains("/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/"))

        assertEquals(listOf(ModelBackendKind.CPU), compact.mainBackendOrder)
        assertEquals(ModelBackendKind.CPU, compact.visionBackend)
        assertEquals(5, compact.minimumDeviceMemoryGb)
        assertEquals(listOf(ModelBackendKind.CPU), qwen.mainBackendOrder)
        assertEquals(ModelBackendKind.CPU, qwen.visionBackend)
        assertEquals(null, qwen.minimumDeviceMemoryGb)
        assertEquals(ModelExecutionProfile.CPU_ONLY_MULTIMODAL, qwen.executionProfile)
    }

    @Test
    fun `every catalog entry is pinned to its exact HuggingFace asset`() {
        // Each model is pinned to an immutable HuggingFace commit plus its LFS
        // sha256 and byte size. Editing any of repo/revision/file/size/sha256
        // without updating this table fails the build. The literal digests are
        // re-verified live against the HF API by scripts/check_model_catalog_hashes.sh.
        val expected = mapOf(
            "gemma-4-e2b" to Asset(
                "litert-community/gemma-4-E2B-it-litert-lm",
                "7fa1d78473894f7e736a21d920c3aa80f950c0db",
                "gemma-4-E2B-it.litertlm",
                2_583_085_056L,
                "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"
            ),
            "gemma-4-e2b-compact" to Asset(
                "litert-community/gemma-4-E2B-it-litert-lm",
                "7fa1d78473894f7e736a21d920c3aa80f950c0db",
                "gemma-4-E2B-it.litertlm",
                2_583_085_056L,
                "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"
            ),
            "gemma-4-e4b" to Asset(
                "litert-community/gemma-4-E4B-it-litert-lm",
                "9695417f248178c63a9f318c6e0c56cb917cb837",
                "gemma-4-E4B-it.litertlm",
                3_654_467_584L,
                "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc"
            ),
            "qwen-3_5-0_8b" to Asset(
                "GabrieleConte/Qwen3.5-0.8B-LiteRT",
                "766ec4afaaeeda64da162c458bac1efc0d27cf9e",
                "qwen35_mm_q8_ekv2048.litertlm",
                1_159_757_824L,
                "92999fe4a9242c983e99892d6e57f368e8cd7a4534bc9a383a9551155b7f70a5"
            )
        )

        assertEquals(
            "catalog model count drift — update the expected asset table",
            expected.size,
            LiteRtModelCatalog.models.size
        )

        LiteRtModelCatalog.models.forEach { model ->
            val asset = expected[model.id]
                ?: error("unexpected model '${model.id}'; add it to the expected asset table")
            val expectedUrl =
                "https://huggingface.co/${asset.repo}/resolve/${asset.revision}/${asset.file}?download=true"
            assertEquals("${model.id} downloadUrl", expectedUrl, model.downloadUrl)
            assertEquals("${model.id} fileName", asset.file, model.fileName)
            assertEquals("${model.id} sizeBytes", asset.sizeBytes, model.sizeBytes)
            assertEquals("${model.id} sha256", asset.sha256, model.sha256)
        }

        val e2b = LiteRtModelCatalog.find("gemma-4-e2b")
        val compact = LiteRtModelCatalog.find("gemma-4-e2b-compact")
        assertEquals(e2b.downloadUrl, compact.downloadUrl)
        assertEquals(e2b.fileName, compact.fileName)
        assertEquals(e2b.sha256, compact.sha256)
    }

    private data class Asset(
        val repo: String,
        val revision: String,
        val file: String,
        val sizeBytes: Long,
        val sha256: String
    )
}
