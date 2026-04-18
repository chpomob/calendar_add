package com.calendaradd.util

import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * Handles file import from share intents.
 */
object FileImportHandler {

    const val FILE_PICKER_REQUEST_CODE = 1001
    const val FILE_TYPE_ALL = "*/*"
    const val FILE_TYPE_TEXT = "text/*"
    const val FILE_TYPE_IMAGE = "image/*"
    const val FILE_TYPE_AUDIO = "audio/*"

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
