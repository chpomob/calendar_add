package com.calendaradd.service

import android.content.Context
import com.calendaradd.BuildConfig
import com.calendaradd.util.AppLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

data class UpdateInfo(
    val available: Boolean,
    val latestVersion: String,
    val downloadUrl: String?,
    val releaseNotes: String?,
    val apkSizeBytes: Long,
    val checksumSha256: String? = null,
    val checksumUrl: String? = null
)

class UpdateCheckerService(context: Context) {
    companion object {
        private const val TAG = "UpdateCheckerService"
        private const val RELEASE_URL = "https://api.github.com/repos/chpomob/calendar_add/releases/latest"
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000

        private const val PREFS_NAME = "update_checker"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_AVAILABLE = "available"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_RELEASE_NOTES = "release_notes"
        private const val KEY_APK_SIZE = "apk_size"
        private const val KEY_CHECKSUM_SHA256 = "checksum_sha256"
        private const val KEY_CHECKSUM_URL = "checksum_url"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun checkForUpdates(forceRefresh: Boolean = false): UpdateInfo {
        if (!forceRefresh) {
            readCachedUpdate()?.let { return it }
        }

        return try {
            val json = fetchReleaseJson()
            val release = JSONObject(json)
            val tagName = release.optString("tag_name")
            val releaseNotes = release.optString("body").takeIf { it.isNotBlank() }
            val assets = release.optJSONArray("assets")

            var apkUrl: String? = null
            var apkSize = 0L
            var apkName: String? = null
            var checksumUrl: String? = null

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val downloadUrl = asset.optString("browser_download_url")
                    val lowerName = name.lowercase(Locale.ROOT)
                    if (lowerName.endsWith(".apk") && apkUrl == null) {
                        apkName = name
                        apkUrl = downloadUrl
                        apkSize = asset.optLong("size", 0L)
                    } else if (isChecksumAssetName(lowerName) && checksumUrl == null) {
                        checksumUrl = downloadUrl
                    }
                }
            }

            val available = apkUrl != null && isVersionNewer(tagName, BuildConfig.VERSION_NAME)
            UpdateInfo(
                available = available,
                latestVersion = tagName.ifBlank { "v${BuildConfig.VERSION_NAME}" },
                downloadUrl = apkUrl.takeIf { available },
                releaseNotes = releaseNotes,
                apkSizeBytes = apkSize,
                checksumSha256 = findChecksumInReleaseNotes(releaseNotes, apkName),
                checksumUrl = checksumUrl
            ).also { cacheUpdate(it) }
        } catch (e: Exception) {
            AppLog.w(TAG, "Update check failed", e)
            UpdateInfo(
                available = false,
                latestVersion = "v${BuildConfig.VERSION_NAME}",
                downloadUrl = null,
                releaseNotes = null,
                apkSizeBytes = 0L
            )
        }
    }

    private fun fetchReleaseJson(): String {
        val connection = (URL(RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CalendarAdd/${BuildConfig.VERSION_NAME}")
        }

        return connection.use {
            val code = it.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub release request failed with HTTP $code")
            }
            it.inputStream.bufferedReader().use { reader -> reader.readText() }
        }
    }

    private fun readCachedUpdate(): UpdateInfo? {
        val checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L)
        if (checkedAt <= 0L || System.currentTimeMillis() - checkedAt > CACHE_TTL_MS) {
            return null
        }
        val latestVersion = prefs.getString(KEY_LATEST_VERSION, null) ?: return null
        return UpdateInfo(
            available = prefs.getBoolean(KEY_AVAILABLE, false),
            latestVersion = latestVersion,
            downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, null),
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, null),
            apkSizeBytes = prefs.getLong(KEY_APK_SIZE, 0L),
            checksumSha256 = prefs.getString(KEY_CHECKSUM_SHA256, null),
            checksumUrl = prefs.getString(KEY_CHECKSUM_URL, null)
        )
    }

    private fun cacheUpdate(updateInfo: UpdateInfo) {
        prefs.edit()
            .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            .putBoolean(KEY_AVAILABLE, updateInfo.available)
            .putString(KEY_LATEST_VERSION, updateInfo.latestVersion)
            .putString(KEY_DOWNLOAD_URL, updateInfo.downloadUrl)
            .putString(KEY_RELEASE_NOTES, updateInfo.releaseNotes)
            .putLong(KEY_APK_SIZE, updateInfo.apkSizeBytes)
            .putString(KEY_CHECKSUM_SHA256, updateInfo.checksumSha256)
            .putString(KEY_CHECKSUM_URL, updateInfo.checksumUrl)
            .apply()
    }

    private fun isChecksumAssetName(lowerName: String): Boolean {
        return lowerName.endsWith(".sha256") ||
            lowerName.endsWith(".sha256sum") ||
            lowerName == "checksums.txt" ||
            lowerName == "sha256sums.txt"
    }

    private fun findChecksumInReleaseNotes(notes: String?, apkName: String?): String? {
        if (notes.isNullOrBlank()) return null
        val checksumPattern = Regex("""\b[a-fA-F0-9]{64}\b""")
        if (apkName.isNullOrBlank()) return null
        return notes.lineSequence()
            .firstOrNull { it.contains(apkName, ignoreCase = true) && checksumPattern.containsMatchIn(it) }
            ?.let { checksumPattern.find(it)?.value?.lowercase(Locale.ROOT) }
    }

    private fun isVersionNewer(candidateTag: String, currentVersionName: String): Boolean {
        val candidate = SemanticVersion.parse(candidateTag) ?: return false
        val current = SemanticVersion.parse(currentVersionName) ?: return false
        return candidate > current
    }

    internal data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<String>
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)
                .takeIf { it != 0 }
                ?.let { return it }

            if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
            if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1

            val maxSize = maxOf(preRelease.size, other.preRelease.size)
            for (i in 0 until maxSize) {
                val left = preRelease.getOrNull(i) ?: return -1
                val right = other.preRelease.getOrNull(i) ?: return 1
                val leftNumber = left.toIntOrNull()
                val rightNumber = right.toIntOrNull()
                val result = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right)
                }
                if (result != 0) return result
            }
            return 0
        }

        companion object {
            fun parse(value: String): SemanticVersion? {
                val normalized = value.trim().removePrefix("v").substringBefore("+")
                val versionAndPreRelease = normalized.split("-", limit = 2)
                val parts = versionAndPreRelease.firstOrNull()?.split(".") ?: return null
                if (parts.isEmpty() || parts.size > 3) return null
                return SemanticVersion(
                    major = parts[0].toIntOrNull() ?: return null,
                    minor = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    patch = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                    preRelease = versionAndPreRelease.getOrNull(1)?.split(".").orEmpty()
                )
            }
        }
    }
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}
