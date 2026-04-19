package com.calendaradd.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads images into a model-friendly size to avoid process death on large photos.
 */
object ModelImageLoader {
    private const val MAX_DIMENSION = 1536

    fun loadForInference(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int = MAX_DIMENSION
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        return decoded.scaleDownIfNeeded(maxDimension)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        var currentWidth = width
        var currentHeight = height

        while (currentWidth > maxDimension || currentHeight > maxDimension) {
            currentWidth /= 2
            currentHeight /= 2
            sampleSize *= 2
        }

        return sampleSize.coerceAtLeast(1)
    }

    private fun Bitmap.scaleDownIfNeeded(maxDimension: Int): Bitmap {
        val largestSide = max(width, height)
        if (largestSide <= maxDimension) return this

        val scale = maxDimension.toFloat() / largestSide.toFloat()
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)

        if (scaled !== this) {
            recycle()
        }

        return scaled
    }
}
