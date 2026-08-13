package com.calendaradd.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelImageLoaderTest {

    // R3: sampling stays untouched below the ceiling and downsamples by powers of two above it.

    @Test
    fun `calculateInSampleSize keeps smaller images untouched`() {
        assertEquals(1, ModelImageLoader.calculateInSampleSize(1200, 800, 1536))
    }

    @Test
    fun `calculateInSampleSize downsamples large square images`() {
        assertEquals(2, ModelImageLoader.calculateInSampleSize(3000, 3000, 1536))
    }

    @Test
    fun `calculateInSampleSize downsamples very large images repeatedly`() {
        assertEquals(8, ModelImageLoader.calculateInSampleSize(8000, 6000, 1536))
    }

    // AC1: exact 1,280px boundary behavior against the shared queued/inference ceiling.

    @Test
    fun `calculateInSampleSize leaves exactly-1280px images at sample size 1`() {
        assertEquals(1, ModelImageLoader.calculateInSampleSize(1280, 1280, ModelImageLoader.MAX_DIMENSION))
    }

    @Test
    fun `calculateInSampleSize downsamples a single pixel over the 1280px boundary`() {
        assertEquals(2, ModelImageLoader.calculateInSampleSize(1281, 1280, ModelImageLoader.MAX_DIMENSION))
    }

    // AC1: overflow-safe sampling for very large / integer-overflow-shaped dimensions.

    @Test
    fun `calculateInSampleSize handles very large dimensions without overflow or hang`() {
        val sampleSize = ModelImageLoader.calculateInSampleSize(
            ModelImageLoader.MAX_SOURCE_DIMENSION,
            ModelImageLoader.MAX_SOURCE_DIMENSION,
            ModelImageLoader.MAX_DIMENSION
        )
        assertTrue(sampleSize > 1)
        assertTrue(sampleSize <= ModelImageLoader.MAX_SOURCE_DIMENSION)
    }

    @Test
    fun `calculateInSampleSize handles Int MAX_VALUE dimensions without overflow or hang`() {
        val sampleSize = ModelImageLoader.calculateInSampleSize(
            Int.MAX_VALUE,
            Int.MAX_VALUE,
            ModelImageLoader.MAX_DIMENSION
        )
        assertTrue(sampleSize > 1)
    }

    @Test
    fun `calculateInSampleSize treats non-positive dimensions as untouched`() {
        assertEquals(1, ModelImageLoader.calculateInSampleSize(0, 800, 1536))
        assertEquals(1, ModelImageLoader.calculateInSampleSize(1200, -1, 1536))
        assertEquals(1, ModelImageLoader.calculateInSampleSize(Int.MIN_VALUE, 800, 1536))
    }

    // AC1: safe-dimensions predicate rejects impossible / platform-impossible source sizes.

    @Test
    fun `isSafeDimensions accepts ordinary dimensions`() {
        assertTrue(ModelImageLoader.isSafeDimensions(1920, 1080))
    }

    @Test
    fun `isSafeDimensions accepts exactly the platform max per side`() {
        assertTrue(
            ModelImageLoader.isSafeDimensions(
                ModelImageLoader.MAX_SOURCE_DIMENSION,
                ModelImageLoader.MAX_SOURCE_DIMENSION
            )
        )
    }

    @Test
    fun `isSafeDimensions rejects one pixel over the platform max`() {
        assertFalse(
            ModelImageLoader.isSafeDimensions(
                ModelImageLoader.MAX_SOURCE_DIMENSION + 1,
                100
            )
        )
    }

    @Test
    fun `isSafeDimensions rejects zero and negative dimensions`() {
        assertFalse(ModelImageLoader.isSafeDimensions(0, 100))
        assertFalse(ModelImageLoader.isSafeDimensions(100, 0))
        assertFalse(ModelImageLoader.isSafeDimensions(-1, 100))
    }

    @Test
    fun `isSafeDimensions rejects integer-overflow-shaped dimensions`() {
        assertFalse(ModelImageLoader.isSafeDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(ModelImageLoader.isSafeDimensions(Int.MIN_VALUE, 100))
    }

    // AC1: compressed-image byte ceiling, inclusive at exactly 20 MiB.

    @Test
    fun `isWithinCompressedImageLimit accepts exactly the 20 MiB ceiling`() {
        assertTrue(ModelImageLoader.isWithinCompressedImageLimit(ModelImageLoader.MAX_COMPRESSED_IMAGE_BYTES))
    }

    @Test
    fun `isWithinCompressedImageLimit rejects one byte over the 20 MiB ceiling`() {
        assertFalse(ModelImageLoader.isWithinCompressedImageLimit(ModelImageLoader.MAX_COMPRESSED_IMAGE_BYTES + 1))
    }

    @Test
    fun `isWithinCompressedImageLimit rejects zero and negative byte counts`() {
        assertFalse(ModelImageLoader.isWithinCompressedImageLimit(0))
        assertFalse(ModelImageLoader.isWithinCompressedImageLimit(-1))
    }

    // AC1: unsupported scheme/type rejected before any decode is attempted.

    @Test
    fun `isSupportedImageScheme accepts content and file schemes case-insensitively`() {
        assertTrue(ModelImageLoader.isSupportedImageScheme("content"))
        assertTrue(ModelImageLoader.isSupportedImageScheme("CONTENT"))
        assertTrue(ModelImageLoader.isSupportedImageScheme("file"))
    }

    @Test
    fun `isSupportedImageScheme rejects network and null schemes`() {
        assertFalse(ModelImageLoader.isSupportedImageScheme("http"))
        assertFalse(ModelImageLoader.isSupportedImageScheme("https"))
        assertFalse(ModelImageLoader.isSupportedImageScheme(null))
    }

    @Test
    fun `isSupportedImageType accepts the image prefix case-insensitively and null`() {
        assertTrue(ModelImageLoader.isSupportedImageType("image/png"))
        assertTrue(ModelImageLoader.isSupportedImageType("IMAGE/JPEG"))
        assertTrue(ModelImageLoader.isSupportedImageType(null))
    }

    @Test
    fun `isSupportedImageType rejects an explicitly non-image declared type`() {
        assertFalse(ModelImageLoader.isSupportedImageType("text/plain"))
        assertFalse(ModelImageLoader.isSupportedImageType("application/pdf"))
    }
}
