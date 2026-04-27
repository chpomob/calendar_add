package com.calendaradd.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig

internal data class ActiveModelSignature(
    val modelPath: String,
    val modelId: String?,
    val enableImage: Boolean,
    val enableAudio: Boolean,
    val maxNumTokens: Int?
)

internal fun Throwable.isRecoverableBackendInitializationFailure(): Boolean {
    return this is Exception || this is LinkageError || this is OutOfMemoryError
}

internal data class BackendProfile(
    val label: String,
    val textBackend: Backend,
    val visionBackend: Backend?,
    val audioBackend: Backend?
)

internal fun backendProfilesFor(
    modelConfig: LiteRtModelConfig?,
    enableImage: Boolean,
    enableAudio: Boolean,
    deviceMemoryGb: Double?
): List<BackendProfile> {
    return when (modelConfig?.executionProfile) {
        ModelExecutionProfile.CPU_ONLY_MULTIMODAL -> listOf(
            BackendProfile(
                label = "CPU-only multimodal",
                textBackend = Backend.CPU(),
                visionBackend = if (enableImage && modelConfig.supportsImage) Backend.CPU() else null,
                audioBackend = if (enableAudio && modelConfig.supportsAudio) Backend.CPU() else null
            )
        )
        else -> acceleratedBackendProfiles(modelConfig, enableImage, enableAudio, deviceMemoryGb)
    }
}

private fun acceleratedBackendProfiles(
    modelConfig: LiteRtModelConfig?,
    enableImage: Boolean,
    enableAudio: Boolean,
    deviceMemoryGb: Double?
): List<BackendProfile> {
    val mainBackends = mainBackendOrderFor(modelConfig, enableImage, enableAudio, deviceMemoryGb)
    val visionBackends = if (enableImage && modelConfig?.supportsImage != false) {
        buildList {
            modelConfig?.visionBackend?.let { add(it) }
            add(ModelBackendKind.CPU)
        }.distinct()
    } else {
        listOf(null)
    }
    val audioBackend = if (enableAudio && modelConfig?.supportsAudio != false) Backend.CPU() else null

    return mainBackends.flatMap { mainBackend ->
        visionBackends.map { visionBackend ->
            BackendProfile(
                label = backendProfileLabel(mainBackend, visionBackend, audioBackend != null),
                textBackend = mainBackend.toLiteRtBackend(),
                visionBackend = visionBackend?.toLiteRtBackend(),
                audioBackend = audioBackend
            )
        }
    }.distinctBy { profile ->
        listOf(
            profile.textBackend::class.java.name,
            profile.visionBackend?.let { it::class.java.name }.orEmpty(),
            profile.audioBackend?.let { it::class.java.name }.orEmpty()
        )
    }
}

private fun mainBackendOrderFor(
    modelConfig: LiteRtModelConfig?,
    enableImage: Boolean,
    enableAudio: Boolean,
    deviceMemoryGb: Double?
): List<ModelBackendKind> {
    val configuredBackends = modelConfig?.mainBackendOrder
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(ModelBackendKind.GPU, ModelBackendKind.CPU)
    val multimodalGpuMainMinimumMemoryGb = modelConfig?.multimodalGpuMainMinimumMemoryGb
    val isMultimodalJob = enableImage || enableAudio
    val hasEnoughMemoryForMultimodalGpuMain = deviceMemoryGb == null ||
        multimodalGpuMainMinimumMemoryGb == null ||
        deviceMemoryGb >= multimodalGpuMainMinimumMemoryGb

    return if (
        isMultimodalJob &&
        !hasEnoughMemoryForMultimodalGpuMain &&
        configuredBackends.firstOrNull() == ModelBackendKind.GPU
    ) {
        listOf(ModelBackendKind.CPU, ModelBackendKind.GPU) + configuredBackends.drop(1)
    } else {
        configuredBackends
    }.distinct()
}

private fun backendProfileLabel(
    mainBackend: ModelBackendKind,
    visionBackend: ModelBackendKind?,
    hasAudio: Boolean
): String {
    val parts = mutableListOf("${mainBackend.label}(text)")
    if (visionBackend != null) {
        parts += "${visionBackend.label}(vision)"
    }
    if (hasAudio) {
        parts += "CPU(audio)"
    }
    return parts.joinToString("+")
}

private fun ModelBackendKind.toLiteRtBackend(): Backend {
    return when (this) {
        ModelBackendKind.CPU -> Backend.CPU()
        ModelBackendKind.GPU -> Backend.GPU()
    }
}

private val ModelBackendKind.label: String
    get() = when (this) {
        ModelBackendKind.CPU -> "CPU"
        ModelBackendKind.GPU -> "GPU"
    }

internal fun LiteRtModelConfig.validateDeviceMemoryOrThrow(deviceMemoryGb: Double?): String? {
    val requiredMemoryGb = minimumDeviceMemoryGb ?: return null
    deviceMemoryGb ?: return null
    return if (deviceMemoryGb < requiredMemoryGb) {
        "${shortName} requires at least ${requiredMemoryGb}GB RAM; this device reports ${formatMemoryGb(deviceMemoryGb)}GB."
    } else {
        null
    }
}

internal fun Context.deviceMemoryGb(): Double? {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val totalBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        memoryInfo.advertisedMem.takeIf { it > 0L } ?: memoryInfo.totalMem
    } else {
        memoryInfo.totalMem
    }
    if (totalBytes <= 0L) return null
    return totalBytes / BYTES_IN_GB
}

internal fun liteRtCacheDir(context: Context, modelPath: String): String? {
    return if (modelPath.startsWith("/data/local/tmp")) {
        context.getExternalFilesDir(null)?.absolutePath
    } else {
        null
    }
}

internal fun conversationConfigFor(modelConfig: LiteRtModelConfig?): ConversationConfig {
    return ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = modelConfig?.topK ?: 64,
            topP = modelConfig?.topP ?: 0.95,
            temperature = modelConfig?.temperature ?: 1.0
        )
    )
}

private fun formatMemoryGb(value: Double): String = String.format("%.1f", value)

private const val BYTES_IN_GB = 1024.0 * 1024.0 * 1024.0
