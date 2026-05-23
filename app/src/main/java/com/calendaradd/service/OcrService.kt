package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.util.AppLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/**
 * Service for on-device OCR using ML Kit Text Recognition.
 * Closeable — call close() to release native ML Kit resources.
 */
class OcrService : Closeable {
    companion object {
        private const val TAG = "OcrService"
    }

    private var recognizer: TextRecognizer? = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extracts text from the given bitmap.
     */
    suspend fun extractText(bitmap: Bitmap): String? {
        val r = recognizer ?: return null
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val result = r.process(image).await()
            result.text
        } catch (e: Exception) {
            AppLog.w(TAG, "OCR extraction failed", e)
            null
        }
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }
}
