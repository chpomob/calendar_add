package com.calendaradd.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads images into a model-friendly size to avoid process death on large photos.
 */
object ModelImageLoader {
    private const val TAG = "ModelImageLoader"
    private const val MAX_DIMENSION = 1536

    fun loadForInference(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int = MAX_DIMENSION
    ): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                loadWithImageDecoder(contentResolver, uri, maxDimension)
            } else {
                loadWithBitmapFactory(contentResolver, uri, maxDimension)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image uri=$uri", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while decoding image uri=$uri", e)
            null
        }
    }

    private fun loadWithBitmapFactory(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int
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

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun loadWithImageDecoder(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        val source = ImageDecoder.createSource(contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            val sampleSize = calculateInSampleSize(size.width, size.height, maxDimension)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.isMutableRequired = false
            if (sampleSize > 1) {
                decoder.setTargetSampleSize(sampleSize)
            }
        }

        return bitmap.scaleDownIfNeeded(maxDimension)
    }

    internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
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
