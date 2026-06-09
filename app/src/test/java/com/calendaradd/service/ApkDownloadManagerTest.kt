package com.calendaradd.service

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApkDownloadManagerTest {
    private lateinit var manager: ApkDownloadManager
    private lateinit var apkFile: File

    @Before
    fun setup() {
        val cacheDir = File("build/tmp/apk-download-manager-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.cacheDir } returns cacheDir
        manager = ApkDownloadManager(context)
        apkFile = File(cacheDir, "test.apk").apply { writeText("apk") }
    }

    @Test
    fun `checksumMatches should reject missing expected sha256`() {
        assertFalse(manager.invokeChecksumMatches(apkFile, null))
        assertFalse(manager.invokeChecksumMatches(apkFile, " "))
    }

    @Test
    fun `checksumMatches should accept matching sha256`() {
        assertTrue(manager.invokeChecksumMatches(apkFile, apkFile.sha256()))
    }

    @Test
    fun `downloadApkResult should delete part file and report mismatch`() = runBlocking {
        val cacheDir = File("build/tmp/apk-download-manager-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val context = contextWithCache(cacheDir)
        val downloadBytes = "tampered".toByteArray()
        val manager = ApkDownloadManager(context) {
            FakeHttpURLConnection(it, downloadBytes)
        }

        val result = manager.downloadApkResult(
            UpdateInfo(
                available = true,
                latestVersion = "v9.9.9",
                downloadUrl = "https://example.com/CalendarAdd.apk",
                releaseNotes = null,
                apkSizeBytes = downloadBytes.size.toLong(),
                checksumSha256 = "0".repeat(64)
            )
        )

        assertTrue(result is ApkDownloadResult.ChecksumMismatch)
        assertFalse(File(cacheDir, "apk-downloads/CalendarAdd.apk.part").exists())
        assertFalse(File(cacheDir, "apk-downloads/CalendarAdd.apk").exists())
    }

    @Test
    fun `downloadApkResult should rename verified download`() = runBlocking {
        val cacheDir = File("build/tmp/apk-download-manager-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val context = contextWithCache(cacheDir)
        val downloadBytes = "verified apk".toByteArray()
        val manager = ApkDownloadManager(context) {
            FakeHttpURLConnection(it, downloadBytes)
        }

        val result = manager.downloadApkResult(
            UpdateInfo(
                available = true,
                latestVersion = "v9.9.9",
                downloadUrl = "https://example.com/CalendarAdd.apk",
                releaseNotes = null,
                apkSizeBytes = downloadBytes.size.toLong(),
                checksumSha256 = downloadBytes.sha256()
            )
        )

        assertTrue(result is ApkDownloadResult.Success)
        assertTrue(File(cacheDir, "apk-downloads/CalendarAdd.apk").exists())
        assertFalse(File(cacheDir, "apk-downloads/CalendarAdd.apk.part").exists())
    }

    @Test
    fun `downloadApkResult should reuse valid cached apk`() = runBlocking {
        val cacheDir = File("build/tmp/apk-download-manager-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val cachedFile = File(cacheDir, "apk-downloads/CalendarAdd.apk").apply {
            parentFile?.mkdirs()
            writeText("cached apk")
        }
        val context = contextWithCache(cacheDir)
        var connectionsOpened = 0
        val manager = ApkDownloadManager(context) {
            connectionsOpened += 1
            FakeHttpURLConnection(it, "network apk".toByteArray())
        }

        val result = manager.downloadApkResult(
            UpdateInfo(
                available = true,
                latestVersion = "v9.9.9",
                downloadUrl = "https://example.com/CalendarAdd.apk",
                releaseNotes = null,
                apkSizeBytes = cachedFile.length(),
                checksumSha256 = cachedFile.sha256()
            )
        )

        assertTrue(result is ApkDownloadResult.Success)
        assertEquals(0, connectionsOpened)
    }

    @Test
    fun `downloadApkResult should report missing checksum before download`() = runBlocking {
        val cacheDir = File("build/tmp/apk-download-manager-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val context = contextWithCache(cacheDir)
        var connectionsOpened = 0
        val manager = ApkDownloadManager(context) {
            connectionsOpened += 1
            FakeHttpURLConnection(it, "network apk".toByteArray())
        }

        val result = manager.downloadApkResult(
            UpdateInfo(
                available = true,
                latestVersion = "v9.9.9",
                downloadUrl = "https://example.com/CalendarAdd.apk",
                releaseNotes = null,
                apkSizeBytes = 0L
            )
        )

        assertTrue(result is ApkDownloadResult.ChecksumUnavailable)
        assertEquals(0, connectionsOpened)
    }

    @Test
    fun `parseChecksum should not use ambiguous global hash fallback`() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)

        assertNull(manager.invokeParseChecksum("$first\n$second", "CalendarAdd.apk"))
        assertEquals(first, manager.invokeParseChecksum("CalendarAdd.apk $first", "CalendarAdd.apk"))
    }
}

private fun contextWithCache(cacheDir: File): Context {
    val context = mockk<Context>(relaxed = true)
    every { context.applicationContext } returns context
    every { context.cacheDir } returns cacheDir
    return context
}

private fun ApkDownloadManager.invokeChecksumMatches(file: File, expectedSha256: String?): Boolean {
    val method = ApkDownloadManager::class.java.getDeclaredMethod(
        "checksumMatches",
        File::class.java,
        String::class.java
    )
    method.isAccessible = true
    return method.invoke(this, file, expectedSha256) as Boolean
}

private fun ApkDownloadManager.invokeParseChecksum(content: String, apkName: String): String? {
    val method = ApkDownloadManager::class.java.getDeclaredMethod(
        "parseChecksum",
        String::class.java,
        String::class.java
    )
    method.isAccessible = true
    return method.invoke(this, content, apkName) as String?
}

private fun File.sha256(): String {
    return readBytes().sha256()
}

private fun ByteArray.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { "%02x".format(it) }
}

private class FakeHttpURLConnection(
    url: String,
    private val body: ByteArray,
    private val code: Int = HTTP_OK
) : HttpURLConnection(URL(url)) {
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
    override fun getResponseCode(): Int = code
    override fun getContentLengthLong(): Long = body.size.toLong()
    override fun getInputStream() = ByteArrayInputStream(body)
}
