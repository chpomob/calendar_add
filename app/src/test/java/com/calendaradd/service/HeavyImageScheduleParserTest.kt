package com.calendaradd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyImageScheduleParserTest {

    @Test
    fun `parseOcrScheduleRows should extract multiple dated workshop rows`() {
        val ocrText = """
            Wellness Workshop Series
            Counseling Center Main Office (203 Student Services I)
            Wednesday, January 7, 2026
            ABC's of Mindfulness
            1:00 PM - 2:00 PM
            Wednesday, January 14, 2026
            Building Self-Esteem
            1:00 PM - 2:00 PM
        """.trimIndent()

        val rows = parseOcrScheduleRows(ocrText, "America/Los_Angeles")

        assertEquals(2, rows.size)
        assertEquals("ABC's of Mindfulness", rows[0].title)
        assertEquals("2026-01-07T13:00:00-08:00", rows[0].startTime)
        assertEquals("2026-01-07T14:00:00-08:00", rows[0].endTime)
        assertEquals("Counseling Center Main Office (203 Student Services I)", rows[0].location)
        assertEquals("Building Self-Esteem", rows[1].title)
        assertEquals("2026-01-14T13:00:00-08:00", rows[1].startTime)
    }

    @Test
    fun `parseOcrScheduleRows should normalize curly punctuation and dash variants`() {
        val ocrText = """
            Café Series
            Room 2
            Thursday, February 5, 2026
            Writer’s Circle
            7 PM – 9 PM
        """.trimIndent()

        val rows = parseOcrScheduleRows(ocrText, "Europe/Paris")

        assertEquals(1, rows.size)
        assertEquals("Writer's Circle", rows.single().title)
        assertEquals("2026-02-05T19:00:00+01:00", rows.single().startTime)
        assertEquals("2026-02-05T21:00:00+01:00", rows.single().endTime)
    }

    @Test
    fun `parseOcrScheduleRows should ignore date rows without parseable time ranges`() {
        val ocrText = """
            Community Open House
            Friday, March 6, 2026
            Doors open in the evening
        """.trimIndent()

        val rows = parseOcrScheduleRows(ocrText, "Europe/Paris")

        assertTrue(rows.isEmpty())
    }
}
