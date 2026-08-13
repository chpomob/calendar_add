package com.calendaradd.service

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GemmaBackendConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var internalCacheDir: File
    private lateinit var filesDir: File
    private lateinit var externalDownloadsDir: File

    @Before
    fun setup() {
        internalCacheDir = tempFolder.newFolder("cache")
        filesDir = tempFolder.newFolder("files")
        externalDownloadsDir = tempFolder.newFolder("external-downloads")

        context = mockk(relaxed = true)
        every { context.cacheDir } returns internalCacheDir
        every { context.filesDir } returns filesDir
    }

    // AC1: every catalog model resolves a non-null cache dir that exists after creation.
    @Test
    fun `every catalog model resolves an existing non-null cache dir`() {
        LiteRtModelCatalog.models.forEach { model ->
            val modelPath = File(filesDir, model.fileName).absolutePath

            val cacheDirPath = liteRtCacheDir(context, modelPath)

            assertNotNull(cacheDirPath)
            val cacheDir = File(cacheDirPath)
            assertTrue("cache dir should exist for ${model.id}", cacheDir.exists())
            assertTrue("cache dir should be a directory for ${model.id}", cacheDir.isDirectory)
        }
    }

    @Test
    fun `cache dir is nested under app-specific internal cache storage`() {
        val modelPath = File(filesDir, "some-model.litertlm").absolutePath

        val cacheDirPath = liteRtCacheDir(context, modelPath)

        assertTrue(
            "cache dir must live under context.cacheDir",
            File(cacheDirPath).canonicalPath.startsWith(internalCacheDir.canonicalPath)
        )
    }

    // R2: compile cache lives entirely outside the roots ModelDownloadManager scans for
    // quarantine / keep-set cleanup (getExternalFilesDir(DOWNLOADS) and filesDir), so it can
    // never be treated as a managed model file.
    @Test
    fun `cache dir is excluded from the roots model cleanup scans`() {
        val modelPath = File(filesDir, "some-model.litertlm").absolutePath

        val cacheDirPath = liteRtCacheDir(context, modelPath)
        val cacheDirCanonical = File(cacheDirPath).canonicalPath

        assertFalse(cacheDirCanonical.startsWith(filesDir.canonicalPath))
        assertFalse(cacheDirCanonical.startsWith(externalDownloadsDir.canonicalPath))
    }

    @Test
    fun `same model path resolves to the same cache dir across calls`() {
        val modelPath = File(filesDir, "gemma-4-E2B-it.litertlm").absolutePath

        val first = liteRtCacheDir(context, modelPath)
        val second = liteRtCacheDir(context, modelPath)

        assertEquals(first, second)
    }

    @Test
    fun `different model paths resolve to different cache dirs`() {
        val pathA = File(filesDir, "model-a.litertlm").absolutePath
        val pathB = File(filesDir, "model-b.litertlm").absolutePath

        val dirA = liteRtCacheDir(context, pathA)
        val dirB = liteRtCacheDir(context, pathB)

        assertTrue(dirA != dirB)
    }

    // R2 / AC2: bounded size with oldest-entry eviction.
    @Test
    fun `pruning evicts oldest entries first to stay under the size bound`() {
        val root = tempFolder.newFolder("compile-cache-root")
        val oldest = writeDirWithFile(root, "oldest", sizeBytes = 40, lastModified = 1_000L)
        val middle = writeDirWithFile(root, "middle", sizeBytes = 40, lastModified = 2_000L)
        val newest = writeDirWithFile(root, "newest", sizeBytes = 40, lastModified = 3_000L)

        pruneLiteRtCacheRoot(root, maxBytes = 80, protectedDirName = null)

        assertFalse("oldest entry should have been evicted", oldest.exists())
        assertTrue("newest entry should survive", newest.exists())
        assertTrue("middle entry should survive", middle.exists())
        assertTrue(totalBytesUnder(root) <= 80)
    }

    // AC2: startup pruning does not delete a file currently in use, even if it is the oldest.
    @Test
    fun `pruning never deletes the protected in-use directory`() {
        val root = tempFolder.newFolder("compile-cache-root-protected")
        val inUse = writeDirWithFile(root, "in-use", sizeBytes = 200, lastModified = 1L)
        writeDirWithFile(root, "stale-a", sizeBytes = 40, lastModified = 2_000L)
        writeDirWithFile(root, "stale-b", sizeBytes = 40, lastModified = 3_000L)

        pruneLiteRtCacheRoot(root, maxBytes = 80, protectedDirName = "in-use")

        assertTrue("protected in-use dir must never be deleted", inUse.exists())
    }

    // AC2: after N eviction-triggering writes, cache size stays under the bound.
    @Test
    fun `repeated writes past the bound keep total cache size bounded`() {
        val root = tempFolder.newFolder("compile-cache-root-repeated")
        val maxBytes = 100L

        repeat(10) { index ->
            writeDirWithFile(root, "entry-$index", sizeBytes = 30, lastModified = index.toLong() * 1_000L)
            pruneLiteRtCacheRoot(root, maxBytes = maxBytes, protectedDirName = "entry-$index")
        }

        assertTrue(totalBytesUnder(root) <= maxBytes)
    }

    @Test
    fun `pruning is a no-op when the root is already under the bound`() {
        val root = tempFolder.newFolder("compile-cache-root-under-bound")
        val small = writeDirWithFile(root, "small", sizeBytes = 10, lastModified = 1_000L)

        pruneLiteRtCacheRoot(root, maxBytes = LITERT_CACHE_MAX_BYTES, protectedDirName = null)

        assertTrue(small.exists())
    }

    private fun writeDirWithFile(root: File, name: String, sizeBytes: Int, lastModified: Long): File {
        val dir = File(root, name).apply { mkdirs() }
        val file = File(dir, "artifact.bin")
        file.writeBytes(ByteArray(sizeBytes))
        file.setLastModified(lastModified)
        dir.setLastModified(lastModified)
        return dir
    }

    private fun totalBytesUnder(root: File): Long {
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
