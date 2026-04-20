package com.calendaradd.service

enum class ModelCapability {
    TEXT,
    IMAGE,
    AUDIO
}

enum class ModelExecutionProfile {
    ACCELERATED_GEMMA,
    CPU_ONLY_MULTIMODAL
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
    val capabilities: Set<ModelCapability>,
    val executionProfile: ModelExecutionProfile,
    val maxNumTokens: Int? = null
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
        get() = (sizeBytes * MIN_DOWNLOAD_FRACTION).toLong()

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
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = decimalGb(2.58),
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA
        ),
        LiteRtModelConfig(
            id = "gemma-4-e4b",
            displayName = "Gemma 4 E4B",
            shortName = "Gemma 4 E4B",
            description = "Higher-quality Gemma 4 variant. Official LiteRT-LM release with text, image, and audio input.",
            source = "LiteRT Community / Google",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = decimalGb(3.65),
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA
        ),
        LiteRtModelConfig(
            id = "gemma-3n-e2b",
            displayName = "Gemma 3n E2B",
            shortName = "Gemma 3n E2B",
            description = "Gemma 3n is the best all-around multimodal candidate here: text, image, and audio.",
            source = "Google / LiteRT-LM",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            sizeBytes = decimalGb(3.66),
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA
        ),
        LiteRtModelConfig(
            id = "gemma-3n-e4b",
            displayName = "Gemma 3n E4B",
            shortName = "Gemma 3n E4B",
            description = "Larger Gemma 3n variant for better quality across text, image, and audio.",
            source = "Google / LiteRT-LM",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            sizeBytes = decimalGb(4.92),
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA
        ),
        LiteRtModelConfig(
            id = "qwen-3_5-0_8b",
            displayName = "Qwen 3.5 0.8B LiteRT",
            shortName = "Qwen 3.5 0.8B",
            description = "Small multimodal Qwen bundle designed for LiteRT-LM apps. Supports text and image, but not audio. Experimental in this app.",
            source = "g-ntovas / LiteRT-LM Android-oriented conversion",
            downloadUrl = "https://huggingface.co/g-ntovas/Qwen3.5-0.8B-4K-LiteRT/resolve/main/qwen35_mm_q8_ekv4096.litertlm",
            fileName = "qwen35_mm_q8_ekv4096.litertlm",
            sizeBytes = decimalGb(1.14),
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE),
            executionProfile = ModelExecutionProfile.CPU_ONLY_MULTIMODAL,
            maxNumTokens = 1024
        ),
        LiteRtModelConfig(
            id = "qwen-3_5-4b",
            displayName = "Qwen 3.5 4B LiteRT",
            shortName = "Qwen 3.5 4B",
            description = "Larger multimodal community conversion. Supports text and image, but not audio. Experimental in this app.",
            source = "Yoursmiling / community conversion",
            downloadUrl = "https://huggingface.co/Yoursmiling/Qwen3.5-4B-LiteRT/resolve/main/model_multimodal.litertlm",
            fileName = "qwen3.5-4b-20260404-model_multimodal.litertlm",
            sizeBytes = decimalMb(5019.62),
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE),
            executionProfile = ModelExecutionProfile.CPU_ONLY_MULTIMODAL,
            maxNumTokens = 512
        )
    )

    fun find(modelId: String?): LiteRtModelConfig {
        return models.firstOrNull { it.id == modelId } ?: models.first { it.id == DEFAULT_MODEL_ID }
    }
}

private const val MIN_DOWNLOAD_FRACTION = 0.95
private const val DOWNLOAD_BUFFER_BYTES = 500_000_000L

private fun decimalGb(value: Double): Long = (value * 1_000_000_000L).toLong()

private fun decimalMb(value: Double): Long = (value * 1_000_000L).toLong()

private fun formatDecimalBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) {
        String.format("%.2f GB", gb)
    } else {
        String.format("%.0f MB", bytes / 1_000_000.0)
    }
}
