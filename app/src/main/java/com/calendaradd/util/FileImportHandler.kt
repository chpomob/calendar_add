package com.calendaradd.util

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Handles file import from share intents.
 */
object FileImportHandler {

    const val FILE_PICKER_REQUEST_CODE = 1001
    const val FILE_TYPE_ALL = "*/*"
    const val FILE_TYPE_TEXT = "text/*"
    const val FILE_TYPE_IMAGE = "image/*"
    const val FILE_TYPE_AUDIO = "audio/*"
    const val MAX_AUDIO_BYTES = 50L * 1024L * 1024L
    const val MAX_COMPRESSED_IMAGE_BYTES = 20L * 1024L * 1024L

    /**
     * Creates intent for picking files from Files app.
     */
    fun createFilePickerIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = FILE_TYPE_ALL
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        return intent
    }

    /**
     * Creates intent for picking audio files.
     */
    fun createAudioPickerIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = FILE_TYPE_AUDIO
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        return intent
    }

    /**
     * Creates intent for picking image files.
     */
    fun createImagePickerIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = FILE_TYPE_IMAGE
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        return intent
    }

    /**
     * Creates intent for picking text files.
     */
    fun createTextPickerIntent(): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = FILE_TYPE_TEXT
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        return intent
    }

    /**
     * Creates intent for importing from a URL (clipboard, email, etc.).
     */
    fun createUrlImportIntent(): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse("about:blank"), "text/plain")
        return intent
    }

    /**
     * Handles the result from file picker.
     */
    fun handleFileResult(
        resultCode: Int,
        data: Intent?,
        uriResolver: UriResolver
    ): FileImportResult? {
        return if (resultCode == Activity.RESULT_OK && data != null) {
            val uri = data.data ?: return null
            val type = data.type ?: FILE_TYPE_ALL
            FileImportResult.Success(uri, type, uriResolver.getPath(uri))
        } else {
            FileImportResult.Failure(resultCode)
        }
    }

    fun isWithinSizeLimit(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Long
    ): Boolean {
        val declaredSize = querySizeBytes(contentResolver, uri)
        return declaredSize == null || declaredSize <= maxBytes
    }

    @Throws(IOException::class)
    fun readBytesWithLimit(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Long,
        label: String
    ): ByteArray {
        querySizeBytes(contentResolver, uri)?.let { declaredSize ->
            if (declaredSize > maxBytes) {
                throw FileTooLargeException(label, declaredSize, maxBytes)
            }
        }

        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open $label input stream.")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read.toLong()
                if (total > maxBytes) {
                    throw FileTooLargeException(label, total, maxBytes)
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun querySizeBytes(contentResolver: ContentResolver, uri: Uri): Long? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn == -1 || !cursor.moveToFirst() || cursor.isNull(sizeColumn)) {
                    null
                } else {
                    cursor.getLong(sizeColumn).takeIf { it >= 0L }
                }
            }
        }.getOrNull()
    }

    class FileTooLargeException(
        label: String,
        actualBytes: Long,
        maxBytes: Long
    ) : IOException("$label is too large (${actualBytes.toMbLabel()} > ${maxBytes.toMbLabel()}).")

    private fun Long.toMbLabel(): String {
        return "${this / (1024L * 1024L)}MB"
    }
}

sealed class FileImportResult {
    data class Success(
        val uri: Uri,
        val mimeType: String,
        val path: String?
    ) : FileImportResult()
    data class Failure(val errorCode: Int) : FileImportResult()
}

data class FileImportOptions(
    val allowAudio: Boolean = true,
    val allowImage: Boolean = true,
    val allowText: Boolean = true,
    val defaultType: String = FileImportHandler.FILE_TYPE_ALL
)
