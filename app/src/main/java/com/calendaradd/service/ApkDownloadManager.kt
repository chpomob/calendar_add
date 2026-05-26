package com.calendaradd.service

import android.content.Context
import com.calendaradd.BuildConfig
import com.calendaradd.util.AppLog
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ApkDownloadManager(context: Context) {
    companion object {
        private const val TAG = "ApkDownloadManager"
        private const val DOWNLOAD_DIR = "apk-downloads"
        private const val MAX_CACHE_AGE_MS = 24L * 60L * 60L * 1000L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    private val appContext = context.applicationContext
    private val downloadMutex = Mutex()

    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val downloadUrl = updateInfo.downloadUrl ?: return@withContext null
        downloadMutex.withLock {
            cleanupOldDownloads()
            val targetFile = File(downloadDir(), apkFileName(downloadUrl, updateInfo.latestVersion))
            val checksum = resolveChecksum(updateInfo, targetFile.name)
            if (targetFile.exists() && targetFile.length() > 0L && checksumMatches(targetFile, checksum)) {
                onProgress(100)
                return@withLock targetFile
            }

            val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
            tempFile.delete()

            try {
                download(downloadUrl, tempFile, onProgress)
                if (!checksumMatches(tempFile, checksum)) {
                    AppLog.w(TAG, "Downloaded APK checksum mismatch for ${targetFile.name}")
                    tempFile.delete()
                    return@withLock null
                }
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                onProgress(100)
                targetFile
            } catch (e: Exception) {
                AppLog.w(TAG, "APK download failed", e)
                tempFile.delete()
                null
            }
        }
    }

    fun cleanupOldDownloads() {
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS
        downloadDir().listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { file ->
                if (file.delete()) {
                    AppLog.i(TAG, "Removed old APK download ${file.name}")
                } else {
                    AppLog.w(TAG, "Failed to remove old APK download ${file.name}")
                }
            }
    }

    private fun download(url: String, targetFile: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CalendarAdd/${BuildConfig.VERSION_NAME}")
        }

        connection.use {
            val code = it.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("APK download failed with HTTP $code")
            }

            val totalBytes = it.contentLengthLong
            var downloadedBytes = 0L
            var lastProgress = -1
            targetFile.outputStream().use { output ->
                it.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0L) {
                            val progress = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolveChecksum(updateInfo: UpdateInfo, apkName: String): String? {
        updateInfo.checksumSha256?.let { return it }
        val checksumUrl = updateInfo.checksumUrl ?: return null
        return try {
            val connection = (URL(checksumUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CalendarAdd/${BuildConfig.VERSION_NAME}")
            }
            connection.use {
                if (it.responseCode !in 200..299) return null
                parseChecksum(it.inputStream.bufferedReader().use { reader -> reader.readText() }, apkName)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Unable to read checksum asset", e)
            null
        }
    }

    private fun parseChecksum(content: String, apkName: String): String? {
        val checksumPattern = Regex("""\b[a-fA-F0-9]{64}\b""")
        content.lineSequence()
            .firstOrNull { it.contains(apkName, ignoreCase = true) && checksumPattern.containsMatchIn(it) }
            ?.let { return checksumPattern.find(it)?.value?.lowercase(Locale.ROOT) }
        return checksumPattern.find(content)?.value?.lowercase(Locale.ROOT)
    }

    private fun checksumMatches(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank()) return true
        return file.exists() && sha256(file).equals(expectedSha256, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadDir(): File {
        return File(appContext.cacheDir, DOWNLOAD_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun apkFileName(downloadUrl: String, latestVersion: String): String {
        val urlName = runCatching { URL(downloadUrl).path.substringAfterLast("/") }.getOrNull()
        val baseName = urlName
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "CalendarAdd-${latestVersion.removePrefix("v")}.apk"
        return baseName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    }
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}
