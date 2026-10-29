package com.calendaradd.service

import java.util.Locale

enum class ModelCapability {
    TEXT,
    IMAGE,
    AUDIO
}

enum class ModelExecutionProfile {
    ACCELERATED_GEMMA,
    CPU_ONLY_MULTIMODAL
}

enum class ModelBackendKind {
    CPU,
    GPU
}

data class LiteRtModelConfig(
    val id: String,
    val displayName: String,
    val shortName: String,
    val description: String,
    val source: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val capabilities: Set<ModelCapability>,
    val executionProfile: ModelExecutionProfile,
    val maxNumTokens: Int? = null,
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
    val mainBackendOrder: List<ModelBackendKind> = listOf(ModelBackendKind.CPU),
    val visionBackend: ModelBackendKind? = null,
    val minimumDeviceMemoryGb: Int? = null
) {
    val supportsText: Boolean get() = capabilities.contains(ModelCapability.TEXT)
    val supportsImage: Boolean get() = capabilities.contains(ModelCapability.IMAGE)
    val supportsAudio: Boolean get() = capabilities.contains(ModelCapability.AUDIO)

    val sizeLabel: String
        get() = formatDecimalBytes(sizeBytes)

    val requiredFreeSpaceBytes: Long
        get() = sizeBytes + DOWNLOAD_BUFFER_BYTES

    val requiredFreeSpaceLabel: String
        get() = formatDecimalBytes(requiredFreeSpaceBytes)

    val minimumExpectedBytes: Long
        get() = sizeBytes

    val capabilitySummary: String
        get() = buildList {
            if (supportsText) add("Text")
            if (supportsImage) add("Image")
            if (supportsAudio) add("Audio")
        }.joinToString(", ")
}

object LiteRtModelCatalog {
    const val DEFAULT_MODEL_ID = "gemma-4-e4b"

    val models: List<LiteRtModelConfig> = listOf(
        LiteRtModelConfig(
            id = "gemma-4-e2b",
            displayName = "Gemma 4 E2B",
            shortName = "Gemma 4 E2B",
            description = "Balanced option for 8 GB devices. Official LiteRT-LM release with text, image, and audio input.",
            source = "LiteRT Community / Google",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm?download=true",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_583_085_056L,
            sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA,
            maxNumTokens = 4000,
            mainBackendOrder = listOf(ModelBackendKind.GPU, ModelBackendKind.CPU),
            visionBackend = ModelBackendKind.GPU,
            minimumDeviceMemoryGb = 8
        ),
        LiteRtModelConfig(
            id = "gemma-4-e2b-compact",
            displayName = "Gemma 4 E2B Compact",
            shortName = "Gemma 4 E2B Compact",
            description = "Conservative Gemma 4 E2B profile for constrained devices. Reuses the E2B model file with CPU-only text/image and a smaller token window. No audio.",
            source = "LiteRT Community / Google",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm?download=true",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_583_085_056L,
            sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA,
            maxNumTokens = 1024,
            mainBackendOrder = listOf(ModelBackendKind.CPU),
            visionBackend = ModelBackendKind.CPU,
            minimumDeviceMemoryGb = 5
        ),
        LiteRtModelConfig(
            id = "gemma-4-e4b",
            displayName = "Gemma 4 E4B",
            shortName = "Gemma 4 E4B",
            description = "Higher-quality Gemma 4 variant. Official LiteRT-LM release with text, image, and audio input.",
            source = "LiteRT Community / Google",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm?download=true",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_654_467_584L,
            sha256 = "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA,
            maxNumTokens = 4000,
            mainBackendOrder = listOf(ModelBackendKind.CPU, ModelBackendKind.GPU),
            visionBackend = ModelBackendKind.GPU,
            minimumDeviceMemoryGb = 12
        ),
        LiteRtModelConfig(
            id = "qwen-3_5-0_8b",
            displayName = "Qwen 3.5 0.8B LiteRT",
            shortName = "Qwen 3.5 0.8B",
            description = "Small multimodal Qwen bundle designed for LiteRT-LM apps. Supports text and image, but not audio. Experimental in this app.",
            source = "GabrieleConte / LiteRT-LM Android-oriented conversion",
            downloadUrl = "https://huggingface.co/GabrieleConte/Qwen3.5-0.8B-LiteRT/resolve/766ec4afaaeeda64da162c458bac1efc0d27cf9e/qwen35_mm_q8_ekv2048.litertlm?download=true",
            fileName = "qwen35_mm_q8_ekv2048.litertlm",
            sizeBytes = 1_159_757_824L,
            sha256 = "92999fe4a9242c983e99892d6e57f368e8cd7a4534bc9a383a9551155b7f70a5",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE),
            executionProfile = ModelExecutionProfile.CPU_ONLY_MULTIMODAL,
            maxNumTokens = 1024,
            mainBackendOrder = listOf(ModelBackendKind.CPU),
            visionBackend = ModelBackendKind.CPU
        )
    )

    fun find(modelId: String?): LiteRtModelConfig {
        return models.firstOrNull { it.id == modelId } ?: models.first { it.id == DEFAULT_MODEL_ID }
    }

    /**
     * First model to select for a device reporting [deviceMemoryGb] of RAM.
     * Only ever returns Gemma 4 E4B or Gemma 4 E2B; unknown memory falls back to E2B.
     */
    fun initialModelId(deviceMemoryGb: Double?): String {
        return if (deviceMemoryGb != null && deviceMemoryGb >= E4B_MINIMUM_MEMORY_GB) {
            "gemma-4-e4b"
        } else {
            "gemma-4-e2b"
        }
    }

    /**
     * Resolves the model selection given the raw [storedId] from preferences.
     * - A stored id naming a catalogue entry is returned unchanged (R5).
     * - With nothing stored, an existing Gemma 4 E2B file means an upgrading install that
     *   implicitly used the old E2B default: keep it rather than re-download E4B (R5).
     * - Otherwise (nothing stored, or a removed id such as a Gemma 3n one) fall back to the
     *   memory-aware [initialModelId] (R4).
     */
    fun resolveSelectedModelId(
        storedId: String?,
        deviceMemoryGb: () -> Double?,
        e2bFilePresent: () -> Boolean
    ): String {
        if (storedId != null && models.any { it.id == storedId }) return storedId
        if (storedId == null && e2bFilePresent()) return E2B_MODEL_ID
        return initialModelId(deviceMemoryGb())
    }

    const val E2B_MODEL_ID = "gemma-4-e2b"

    private const val E4B_MINIMUM_MEMORY_GB = 12.0
}

private const val DOWNLOAD_BUFFER_BYTES = 500_000_000L

private fun formatDecimalBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) {
        String.format(Locale.ROOT, "%.2f GB", gb)
    } else {
        String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0)
    }
}
