package com.calendaradd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventExtractionModelsTest {

    @Test
    fun `hasMeaningfulContent should reject fully empty extraction`() {
        val extraction = EventExtraction(
            title = "",
            description = "",
            startTime = "",
            endTime = "",
            location = "",
            attendees = emptyList()
        )

        assertFalse(extraction.hasMeaningfulContent())
    }

    @Test
    fun `mergeRelatedEventExtractions should trim fields and dedupe attendees`() {
        val result = mergeRelatedEventExtractions(
            listOf(
                EventExtraction(
                    title = "  Project Kickoff  ",
                    description = "Agenda review",
                    startTime = " 2026-05-03T10:00:00+02:00 ",
                    endTime = "",
                    location = "",
                    attendees = listOf(" Alice ", "Bob")
                ),
                EventExtraction(
                    title = "project kickoff",
                    description = "Agenda review",
                    startTime = "",
                    endTime = "2026-05-03T11:00:00+02:00",
                    location = " HQ ",
                    attendees = listOf("Alice", " Chloé ")
                )
            )
        )

        assertEquals(1, result.size)
        assertEquals("Project Kickoff", result.single().title)
        assertEquals("2026-05-03T10:00:00+02:00", result.single().startTime)
        assertEquals("2026-05-03T11:00:00+02:00", result.single().endTime)
        assertEquals("HQ", result.single().location)
        assertEquals(listOf("Alice", "Bob", "Chloé"), result.single().attendees)
    }

    @Test
    fun `mergeRelatedEventExtractions should keep conflicting same-title events separate`() {
        val result = mergeRelatedEventExtractions(
            listOf(
                EventExtraction(
                    title = "Yoga",
                    description = "",
                    startTime = "2026-05-03T10:00:00+02:00",
                    endTime = "",
                    location = "Studio A",
                    attendees = emptyList()
                ),
                EventExtraction(
                    title = "Yoga",
                    description = "",
                    startTime = "2026-05-04T10:00:00+02:00",
                    endTime = "",
                    location = "Studio A",
                    attendees = emptyList()
                )
            )
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.startTime == "2026-05-03T10:00:00+02:00" })
        assertTrue(result.any { it.startTime == "2026-05-04T10:00:00+02:00" })
    }

    @Test
    fun `combineHeavyModeResponses should preserve missing stage markers`() {
        val result = combineHeavyModeResponses(
            observations = "observed title",
            temporalResolution = null,
            finalJson = "final event"
        )

        assertTrue(result.contains("Heavy mode observation stage:\nobserved title"))
        assertTrue(result.contains("Heavy mode temporal stage:\n<no response>"))
        assertTrue(result.contains("Heavy mode final stage:\nfinal event"))
    }
}
