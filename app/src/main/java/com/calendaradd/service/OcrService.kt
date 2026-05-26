package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.util.AppLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Service for on-device OCR using ML Kit Text Recognition.
 * Closeable — call close() to release native ML Kit resources.
 */
class OcrService : Closeable {
    companion object {
        private const val TAG = "OcrService"
        private const val MAX_OCR_BITMAP_DIMENSION = 2048
    }

    private var recognizer: TextRecognizer? = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extracts text from the given bitmap.
     */
    suspend fun extractText(bitmap: Bitmap): String? {
        val r = recognizer ?: return null
        val ocrBitmap = bitmap.scaleDownForOcr()
        val image = InputImage.fromBitmap(ocrBitmap, 0)
        return try {
            val result = r.process(image).await()
            result.text
        } catch (e: Exception) {
            AppLog.w(TAG, "OCR extraction failed", e)
            null
        } finally {
            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
        }
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }

    private fun Bitmap.scaleDownForOcr(): Bitmap {
        val largestSide = max(width, height)
        if (largestSide <= MAX_OCR_BITMAP_DIMENSION) return this

        val scale = MAX_OCR_BITMAP_DIMENSION.toFloat() / largestSide.toFloat()
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    }
}
