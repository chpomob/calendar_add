package com.calendaradd.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelImageLoaderTest {

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
}
