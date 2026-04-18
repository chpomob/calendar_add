package com.calendaradd.util

import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * Utility for resolving URIs to file paths.
 */
object UriResolver {

    fun getPath(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> {
                // Check if it's a file type that can be converted to path
                when (uri.authority) {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> {
                        // Image from gallery
                        getMediaStorePath(uri, MediaStore.Images.Media.DATA)
                    }
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> {
                        // Video from gallery
                        getMediaStorePath(uri, MediaStore.Video.Media.DATA)
                    }
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI -> {
                        // Audio from gallery
                        getMediaStorePath(uri, MediaStore.Audio.Media.DATA)
                    }
                    else -> {
                        // Unknown authority - use content:// URI directly
                        uri.toString()
                    }
                }
            }
            "file" -> uri.path ?: null
            else -> uri.toString()
        }
    }

    private fun getMediaStorePath(uri: Uri, columnName: String): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(columnName))
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Checks if URI is a public file (external storage).
     */
    fun isPublicFile(uri: Uri): Boolean {
        return uri.authority?.contains(Environment.getExternalStorageDirectory().canonicalPath) == true
    }

    /**
     * Gets file extension from MIME type.
     */
    fun getExtension(mimeType: String): String {
        return when (mimeType) {
            "text/plain" -> ".txt"
            "text/markdown" -> ".md"
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "audio/mpeg" -> ".mp3"
            "audio/wav" -> ".wav"
            "application/pdf" -> ".pdf"
            else -> ".unknown"
        }
    }
}
