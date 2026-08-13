package com.calendaradd.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.security.MessageDigest
import java.util.Locale

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
    isGpuBackendAvailable: Boolean = true
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
        else -> acceleratedBackendProfiles(modelConfig, enableImage, enableAudio, isGpuBackendAvailable)
    }
}

private fun acceleratedBackendProfiles(
    modelConfig: LiteRtModelConfig?,
    enableImage: Boolean,
    enableAudio: Boolean,
    isGpuBackendAvailable: Boolean
): List<BackendProfile> {
    val mainBackends = modelConfig?.mainBackendOrder
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(ModelBackendKind.GPU, ModelBackendKind.CPU)
    val availableMainBackends = mainBackends
        .filter { it != ModelBackendKind.GPU || isGpuBackendAvailable }
        .ifEmpty { listOf(ModelBackendKind.CPU) }
    val visionBackends = if (enableImage && modelConfig?.supportsImage != false) {
        buildList {
            modelConfig?.visionBackend
                ?.takeIf { it != ModelBackendKind.GPU || isGpuBackendAvailable }
                ?.let { add(it) }
            add(ModelBackendKind.CPU)
        }.distinct()
    } else {
        listOf(null)
    }
    val audioBackend = if (enableAudio && modelConfig?.supportsAudio != false) Backend.CPU() else null

    return availableMainBackends.flatMap { mainBackend ->
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

internal fun LiteRtModelConfig.deviceMemoryValidationFailure(deviceMemoryGb: Double?): String? {
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

internal fun Context.hasOpenClLibrary(): Boolean {
    return OPENCL_LIBRARY_PATHS.any { path -> java.io.File(path).exists() }
}

/**
 * Persistent LiteRT-LM compile cache, one subdirectory per model under app-specific internal
 * cache storage. Reusing the compiled graph across process launches avoids a multi-second
 * recompile before the first analysis. The directory is created lazily and never returned as
 * null; it lives under [Context.getCacheDir] so it is never treated as a managed model file by
 * [ModelDownloadManager]'s quarantine/keep-set cleanup, which only inspects the external
 * Downloads / internal files roots.
 */
internal fun liteRtCacheDir(context: Context, modelPath: String): String {
    val root = File(context.cacheDir, LITERT_CACHE_ROOT_DIR_NAME)
    val modelCacheDir = File(root, cacheKeyFor(modelPath))
    if (!modelCacheDir.exists()) {
        modelCacheDir.mkdirs()
    }
    touchLiteRtCacheAccessMarker(modelCacheDir)
    pruneLiteRtCacheRoot(root, LITERT_CACHE_MAX_BYTES, protectedDirName = modelCacheDir.name)
    return modelCacheDir.absolutePath
}

/**
 * Bounds compile-cache size again after [modelPath]'s subdirectory has been populated by
 * `Engine.initialize()`. [liteRtCacheDir] prunes before the engine writes its compiled
 * artifacts, so a fresh compile is invisible to that first size check; calling this afterward
 * ensures a successful initialization cannot leave the cache root over [LITERT_CACHE_MAX_BYTES].
 */
internal fun pruneLiteRtCacheAfterInit(context: Context, modelPath: String) {
    val root = File(context.cacheDir, LITERT_CACHE_ROOT_DIR_NAME)
    val modelCacheDir = File(root, cacheKeyFor(modelPath))
    pruneLiteRtCacheRoot(root, LITERT_CACHE_MAX_BYTES, protectedDirName = modelCacheDir.name)
}

/**
 * Marks [modelCacheDir] as just-used by updating a marker file's mtime, since reading the
 * compiled artifacts LiteRT-LM already wrote does not itself change their mtime.
 * [lastModifiedRecursively] picks up this marker like any other file, so a frequently reused
 * cache is no longer mistaken for the oldest (least recently used) entry during eviction.
 */
private fun touchLiteRtCacheAccessMarker(modelCacheDir: File) {
    val marker = File(modelCacheDir, LITERT_CACHE_ACCESS_MARKER_NAME)
    if (!marker.exists()) {
        marker.createNewFile()
    }
    marker.setLastModified(System.currentTimeMillis())
}

internal fun cacheKeyFor(modelPath: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(modelPath.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * Bounds total compile-cache size by evicting the least-recently-touched model subdirectories
 * first, skipping [protectedDirName] (the model about to be initialized) so a directory that is
 * about to be used is never removed.
 */
internal fun pruneLiteRtCacheRoot(root: File, maxBytes: Long, protectedDirName: String?) {
    val entries = root.listFiles()?.filter { it.name != protectedDirName }.orEmpty()
    if (entries.isEmpty()) return
    val protectedSize = protectedDirName?.let { File(root, it).sizeRecursively() } ?: 0L
    val sizedEntries = entries.map { entry -> entry to entry.sizeRecursively() }
    var totalBytes = protectedSize + sizedEntries.sumOf { it.second }
    if (totalBytes <= maxBytes) return
    val oldestFirst = sizedEntries.sortedBy { (entry, _) -> entry.lastModifiedRecursively() }
    for ((entry, entrySize) in oldestFirst) {
        if (totalBytes <= maxBytes) break
        if (entry.deleteRecursively()) {
            totalBytes -= entrySize
        }
    }
}

private fun File.sizeRecursively(): Long = when {
    isFile -> length()
    isDirectory -> listFiles()?.sumOf { it.sizeRecursively() } ?: 0L
    else -> 0L
}

private fun File.lastModifiedRecursively(): Long = when {
    isFile -> lastModified()
    isDirectory -> listFiles()?.maxOfOrNull { it.lastModifiedRecursively() } ?: lastModified()
    else -> lastModified()
}

private const val LITERT_CACHE_ROOT_DIR_NAME = "litert_compile_cache"
private const val LITERT_CACHE_ACCESS_MARKER_NAME = ".last_used"
internal const val LITERT_CACHE_MAX_BYTES = 1_073_741_824L // 1 GB

internal fun conversationConfigFor(modelConfig: LiteRtModelConfig?): ConversationConfig {
    return ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = modelConfig?.topK ?: 64,
            topP = modelConfig?.topP ?: 0.95,
            temperature = modelConfig?.temperature ?: 1.0
        )
    )
}

private fun formatMemoryGb(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private const val BYTES_IN_GB = 1024.0 * 1024.0 * 1024.0

private val OPENCL_LIBRARY_PATHS = listOf(
    "/vendor/lib64/libOpenCL.so",
    "/vendor/lib/libOpenCL.so",
    "/system/vendor/lib64/libOpenCL.so",
    "/system/vendor/lib/libOpenCL.so",
    "/system/lib64/libOpenCL.so",
    "/system/lib/libOpenCL.so"
)
