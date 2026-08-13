package com.calendaradd.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.scale
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads images into a model-friendly size to avoid process death on large photos.
 *
 * R3: bounds are enforced at the earliest ingress, before any pixel allocation:
 * supported scheme/type, independent compressed-length ceiling, safe source dimensions,
 * overflow-safe sampling, contained OOM, recycled intermediates, and EXIF orientation
 * normalized within the [MAX_DIMENSION] / pixel-budget ceiling.
 */
object ModelImageLoader {
    private const val TAG = "ModelImageLoader"

    /**
     * R1/R3: decoded/queued image ceiling. Both the inference path (here) and the worker's
     * queued-image decode target 1,280 px per side.
     */
    internal const val MAX_DIMENSION = 1280

    /**
     * R3: reject sources whose own dimensions exceed the platform's hard per-side bitmap
     * limit (65,535 on every shipping Android version). Anything larger is lying metadata or
     * an adversarial file that would either OOM the bounds probe or defeat sampling.
     */
    internal const val MAX_SOURCE_DIMENSION = 65_535

    /** R3: compressed-image byte ceiling, shared with the worker/import paths. */
    internal val MAX_COMPRESSED_IMAGE_BYTES = FileImportHandler.MAX_COMPRESSED_IMAGE_BYTES

    private const val COUNT_BUFFER_BYTES = 64 * 1024

    fun loadForInference(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int = MAX_DIMENSION
    ): Bitmap? {
        AppLog.i(TAG, "Loading image for inference uri=$uri maxDimension=$maxDimension sdk=${Build.VERSION.SDK_INT}")

        // R3: reject unsupported scheme/type before touching the decoder. These are pure
        // predicates over the scheme string and declared mime so the logic is testable.
        if (!isSupportedImageScheme(uri.scheme)) {
            AppLog.w(TAG, "Rejecting image uri=$uri unsupported scheme=${uri.scheme}")
            return null
        }
        val declaredMime = runCatching { contentResolver.getType(uri) }.getOrNull()
        if (!isSupportedImageType(declaredMime)) {
            AppLog.w(TAG, "Rejecting image uri=$uri unsupported type=$declaredMime")
            return null
        }

        // R3: read the compressed source into a single bounded in-memory buffer, independent of
        // any provider-declared size (declared sizes can be absent or dishonest). readBoundedBytes
        // returns null when the stream is unreadable OR the running total exceeds the ceiling.
        // Decoding and EXIF inspection below both operate on this exact buffer rather than
        // reopening the URI, so a mutable/adversarial ContentProvider cannot return different
        // content for the size check than for the decode (TOCTOU).
        val imageBytes = readBoundedBytes(contentResolver, uri, MAX_COMPRESSED_IMAGE_BYTES)
        if (imageBytes == null) {
            AppLog.w(TAG, "Rejecting image uri=$uri compressed length unknown or over $MAX_COMPRESSED_IMAGE_BYTES")
            return null
        }

        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                loadWithImageDecoder(imageBytes, maxDimension)
            } else {
                loadWithBitmapFactory(imageBytes, maxDimension)
            }
            if (bitmap != null) {
                AppLog.i(
                    TAG,
                    "Loaded image uri=$uri size=${bitmap.width}x${bitmap.height} config=${bitmap.config ?: "null"} bytes=${bitmap.allocationByteCount}"
                )
            } else {
                AppLog.w(TAG, "Decoder returned null image for uri=$uri")
            }
            bitmap
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to decode image uri=$uri", e)
            null
        } catch (e: OutOfMemoryError) {
            // R3: contain OOM so a single oversized decode never crashes the process.
            AppLog.e(TAG, "Out of memory while decoding image uri=$uri", e)
            null
        }
    }

    private fun loadWithBitmapFactory(imageBytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)

        // R3: reject impossible or platform-impossible dimensions before allocating any pixels.
        if (!isSafeDimensions(bounds.outWidth, bounds.outHeight)) {
            AppLog.w(TAG, "Rejecting image unsafe dimensions=${bounds.outWidth}x${bounds.outHeight}")
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        AppLog.i(
            TAG,
            "BitmapFactory bounds original=${bounds.outWidth}x${bounds.outHeight} sampleSize=${decodeOptions.inSampleSize}"
        )

        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
            ?: return null

        // R3: normalize EXIF orientation, then clamp to the ceiling. Both stay within
        // maxDimension per side (rotation only swaps sides) and recycle intermediates.
        val oriented = applyExifOrientation(imageBytes, decoded)
        return oriented.scaleDownIfNeeded(maxDimension)
    }

    private fun applyExifOrientation(imageBytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(imageBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
            else -> return bitmap
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun loadWithImageDecoder(imageBytes: ByteArray, maxDimension: Int): Bitmap? {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(imageBytes))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            // R3: the header callback runs before pixel allocation. Reject platform-impossible
            // dimensions by aborting the decode (the thrown exception is contained by
            // loadForInference and yields null).
            if (!isSafeDimensions(size.width, size.height)) {
                throw IllegalArgumentException("unsafe dimensions ${size.width}x${size.height}")
            }
            val sampleSize = calculateInSampleSize(size.width, size.height, maxDimension)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.isMutableRequired = false
            AppLog.i(
                TAG,
                "ImageDecoder source original=${size.width}x${size.height} sampleSize=$sampleSize mime=${info.mimeType}"
            )
            if (sampleSize > 1) {
                decoder.setTargetSampleSize(sampleSize)
            }
        }

        // R3: unlike BitmapFactory, ImageDecoder already honors EXIF orientation while
        // decoding, so re-applying it here would rotate/mirror the image a second time. Just
        // clamp to the ceiling (rotation only swaps sides, already <= maxDimension).
        return bitmap.scaleDownIfNeeded(maxDimension)
    }

    /**
     * R3: overflow-safe power-of-two sample size. Inputs are validated by [isSafeDimensions]
     * before this runs in production (width/height <= [MAX_SOURCE_DIMENSION]), so halving a
     * positive value converges in <= ~16 iterations and the running [sampleSize] never
     * approaches Int overflow. The early-out guards against pathological direct callers.
     */
    internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
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

    /** R3: only content:// and file:// sources are supported by the inference path. */
    internal fun isSupportedImageScheme(scheme: String?): Boolean {
        val normalized = scheme?.lowercase()
        return normalized == ContentResolver.SCHEME_CONTENT || normalized == ContentResolver.SCHEME_FILE
    }

    /**
     * R3: accept the "image/" prefix (case-insensitive) and a null/unknown type (the decoder sniffs the
     * actual format). Reject anything explicitly declared non-image (e.g. text shared as image).
     */
    internal fun isSupportedImageType(mime: String?): Boolean {
        return mime == null || mime.startsWith("image/", ignoreCase = true)
    }

    /**
     * R3: reject impossible (<=0) and platform-impossible (> [MAX_SOURCE_DIMENSION]) source
     * dimensions. Catches the integer-overflow / adversarial-megapixel cases before allocation
     * (Int.MAX_VALUE > MAX_SOURCE_DIMENSION is rejected here).
     */
    internal fun isSafeDimensions(width: Int, height: Int): Boolean {
        return width > 0 && height > 0 &&
            width <= MAX_SOURCE_DIMENSION && height <= MAX_SOURCE_DIMENSION
    }

    /** R3: inclusive compressed-image ceiling predicate (boundary: exactly the max is allowed). */
    internal fun isWithinCompressedImageLimit(bytes: Long): Boolean {
        return bytes in 1..MAX_COMPRESSED_IMAGE_BYTES
    }

    /**
     * R3: reads the compressed source into a single in-memory buffer, capped at [maxBytes] and
     * measured independently of provider metadata. Returns null when the stream cannot be
     * opened/read OR the running total exceeds [maxBytes] (so callers reject "unknown or
     * over-limit" uniformly). Decoding this exact buffer afterward (rather than reopening the
     * URI) closes the TOCTOU gap where a mutable ContentProvider could serve different bytes to
     * the size check than to the decode.
     */
    private fun readBoundedBytes(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Long
    ): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(COUNT_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    total += read.toLong()
                    if (total > maxBytes) return@use null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not read image bytes uri=$uri", e)
            null
        }
    }

    private fun Bitmap.scaleDownIfNeeded(maxDimension: Int): Bitmap {
        val largestSide = max(width, height)
        if (largestSide <= maxDimension) return this

        val scale = maxDimension.toFloat() / largestSide.toFloat()
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val scaled = scale(scaledWidth, scaledHeight)

        if (scaled !== this) {
            recycle()
        }

        return scaled
    }
}
