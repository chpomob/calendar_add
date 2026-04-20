package com.calendaradd.service

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
}
