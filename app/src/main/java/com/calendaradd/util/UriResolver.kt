package com.calendaradd.util

import android.net.Uri

/**
 * Utility for resolving URIs to file paths.
 */
object UriResolver {

    fun getPath(uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            else -> uri.toString()
        }
    }

    /**
     * Checks if URI is a public file (external storage).
     */
    fun isPublicFile(uri: Uri): Boolean {
        return uri.scheme == "file"
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
