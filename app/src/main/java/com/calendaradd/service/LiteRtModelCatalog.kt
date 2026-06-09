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
    val minimumDeviceMemoryGb: Int? = null,
    val requireExactSize: Boolean = false
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
    const val DEFAULT_MODEL_ID = "gemma-4-e2b"

    val models: List<LiteRtModelConfig> = listOf(
        LiteRtModelConfig(
            id = "gemma-4-e2b",
            displayName = "Gemma 4 E2B",
            shortName = "Gemma 4 E2B",
            description = "Balanced default. Official LiteRT-LM release with text, image, and audio input.",
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
            minimumDeviceMemoryGb = 8,
            requireExactSize = true
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
            minimumDeviceMemoryGb = 5,
            requireExactSize = true
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
            minimumDeviceMemoryGb = 12,
            requireExactSize = true
        ),
        LiteRtModelConfig(
            id = "gemma-3n-e2b",
            displayName = "Gemma 3n E2B",
            shortName = "Gemma 3n E2B",
            description = "Gemma 3n is the best all-around multimodal candidate here: text, image, and audio.",
            source = "Google / LiteRT-LM",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/ba9ca88da013b537b6ed38108be609b8db1c3a16/gemma-3n-E2B-it-int4.litertlm?download=true",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            sizeBytes = 3_655_827_456L,
            sha256 = "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA,
            maxNumTokens = 4096,
            mainBackendOrder = listOf(ModelBackendKind.CPU, ModelBackendKind.GPU),
            visionBackend = ModelBackendKind.GPU,
            minimumDeviceMemoryGb = 8,
            requireExactSize = true
        ),
        LiteRtModelConfig(
            id = "gemma-3n-e4b",
            displayName = "Gemma 3n E4B",
            shortName = "Gemma 3n E4B",
            description = "Larger Gemma 3n variant for better quality across text, image, and audio.",
            source = "Google / LiteRT-LM",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/297ed75955702dec3503e00c2c2ecbbf475300bc/gemma-3n-E4B-it-int4.litertlm?download=true",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            sizeBytes = 4_919_541_760L,
            sha256 = "2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4",
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA,
            maxNumTokens = 4096,
            mainBackendOrder = listOf(ModelBackendKind.CPU, ModelBackendKind.GPU),
            visionBackend = ModelBackendKind.GPU,
            minimumDeviceMemoryGb = 12,
            requireExactSize = true
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
