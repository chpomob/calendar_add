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
        get() = if (requireExactSize) sizeBytes else (sizeBytes * MIN_DOWNLOAD_FRACTION).toLong()

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
            capabilities = setOf(ModelCapability.TEXT, ModelCapability.IMAGE, ModelCapability.AUDIO),
            executionProfile = ModelExecutionProfile.ACCELERATED_GEMMA,
            maxNumTokens = 4000,
            mainBackendOrder = listOf(ModelBackendKind.GPU, ModelBackendKind.CPU),
            visionBackend = ModelBackendKind.GPU,
            minimumDeviceMemoryGb = 8,
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
            source = "g-ntovas / LiteRT-LM Android-oriented conversion",
            downloadUrl = "https://huggingface.co/g-ntovas/Qwen3.5-0.8B-4K-LiteRT/resolve/main/qwen35_mm_q8_ekv4096.litertlm",
            fileName = "qwen35_mm_q8_ekv4096.litertlm",
            sizeBytes = decimalGb(1.14),
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
