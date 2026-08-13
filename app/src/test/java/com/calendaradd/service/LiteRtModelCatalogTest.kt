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
        assertEquals(4096, LiteRtModelCatalog.find("gemma-3n-e2b").maxNumTokens)
        assertEquals(4096, LiteRtModelCatalog.find("gemma-3n-e4b").maxNumTokens)
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
        assertTrue(gemma4E2b.downloadUrl.contains("/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/"))
        assertEquals(listOf(ModelBackendKind.CPU, ModelBackendKind.GPU), gemma4E4b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma4E4b.visionBackend)
        assertEquals(12, gemma4E4b.minimumDeviceMemoryGb)
        assertEquals(3_654_467_584L, gemma4E4b.sizeBytes)
        assertTrue(gemma4E4b.downloadUrl.contains("/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/"))

        assertEquals(listOf(ModelBackendKind.CPU, ModelBackendKind.GPU), gemma3nE2b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma3nE2b.visionBackend)
        assertEquals(8, gemma3nE2b.minimumDeviceMemoryGb)
        assertEquals(3_655_827_456L, gemma3nE2b.sizeBytes)
        assertEquals(listOf(ModelBackendKind.CPU, ModelBackendKind.GPU), gemma3nE4b.mainBackendOrder)
        assertEquals(ModelBackendKind.GPU, gemma3nE4b.visionBackend)
        assertEquals(12, gemma3nE4b.minimumDeviceMemoryGb)
        assertEquals(4_919_541_760L, gemma3nE4b.sizeBytes)
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
            "gemma-3n-e2b" to Asset(
                "google/gemma-3n-E2B-it-litert-lm",
                "ba9ca88da013b537b6ed38108be609b8db1c3a16",
                "gemma-3n-E2B-it-int4.litertlm",
                3_655_827_456L,
                "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6"
            ),
            "gemma-3n-e4b" to Asset(
                "google/gemma-3n-E4B-it-litert-lm",
                "297ed75955702dec3503e00c2c2ecbbf475300bc",
                "gemma-3n-E4B-it-int4.litertlm",
                4_919_541_760L,
                "2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4"
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
    }

    private data class Asset(
        val repo: String,
        val revision: String,
        val file: String,
        val sizeBytes: Long,
        val sha256: String
    )
}
