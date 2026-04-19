package com.calendaradd.service

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Service for on-device OCR using ML Kit Text Recognition.
 */
interface ImageTextExtractor {
    suspend fun extractText(bitmap: Bitmap): String?
}

class OcrService : ImageTextExtractor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extracts text from the given bitmap.
     */
    override suspend fun extractText(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val result = recognizer.process(image).await()
            result.text
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
