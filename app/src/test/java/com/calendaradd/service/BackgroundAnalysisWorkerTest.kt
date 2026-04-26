package com.calendaradd.service

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BackgroundAnalysisWorkerTest {

    @Test
    fun `resultNotificationIdFor should generate stable positive ids per work`() {
        val firstWorkId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val secondWorkId = UUID.fromString("7b08db6b-a122-4c7f-a7f0-46781c8c97c8")

        val firstNotificationId = resultNotificationIdFor(firstWorkId)
        val secondNotificationId = resultNotificationIdFor(secondWorkId)

        assertTrue(firstNotificationId > 0)
        assertTrue(secondNotificationId > 0)
        assertEquals(firstNotificationId, resultNotificationIdFor(firstWorkId))
        assertNotEquals(firstNotificationId, secondNotificationId)
    }

    @Test
    fun `hasExceededBackgroundAttemptLimit should stop after two retries`() {
        assertFalse(hasExceededBackgroundAttemptLimit(0))
        assertFalse(hasExceededBackgroundAttemptLimit(1))
        assertTrue(hasExceededBackgroundAttemptLimit(2))
    }

    @Test
    fun `buildProgressMessage should label retry attempts`() {
        assertEquals("Initializing Gemma...", buildProgressMessage("Initializing Gemma...", 0))
        assertEquals("Retry 2: Initializing Gemma...", buildProgressMessage("Initializing Gemma...", 1))
    }
}
