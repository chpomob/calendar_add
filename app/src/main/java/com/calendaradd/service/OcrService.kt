package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.util.AppLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Service for on-device OCR using ML Kit Text Recognition.
 */
class OcrService {
    companion object {
        private const val TAG = "OcrService"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extracts text from the given bitmap.
     */
    suspend fun extractText(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val result = recognizer.process(image).await()
            result.text
        } catch (e: Exception) {
            AppLog.w(TAG, "OCR extraction failed", e)
            null
        }
    }
}
