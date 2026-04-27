package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EventAnalysisPromptsTest {

    @Test
    fun `buildReferencePrompt should include deterministic timezone aware reference`() {
        val timestamp = ZonedDateTime.of(2026, 4, 27, 15, 45, 0, 0, ZoneId.of("Europe/Paris"))
            .toInstant()
            .toEpochMilli()

        val prompt = buildReferencePrompt(
            InputContext(
                timestamp = timestamp,
                timezone = "Europe/Paris",
                language = "fr"
            )
        )

        assertTrue(prompt.contains("Reference local datetime: 2026-04-27T15:45:00+02:00"))
        assertTrue(prompt.contains("Reference timezone: Europe/Paris"))
        assertTrue(prompt.contains("User language: fr"))
        assertTrue(prompt.contains("Resolve relative date and time phrases"))
    }

    @Test
    fun `buildTextPrompt should include generic anti hallucination guardrails`() {
        val prompt = buildTextPrompt("Maybe tomorrow around 8", InputContext())

        assertTrue(prompt.contains("Use only input evidence; leave unknown fields empty."))
        assertTrue(prompt.contains("return no event instead of generic Meeting/Event/Concert/Reminder"))
        assertTrue(prompt.contains("Fill endTime only with explicit end, duration, or range."))
        assertTrue(prompt.contains("Attendees must be explicitly named participants or invitees."))
        assertTrue(prompt.contains("Preserve proper nouns, accents, and input language."))
    }

    @Test
    fun `buildImagePrompt should reject flyer boilerplate as event evidence`() {
        val prompt = buildImagePrompt(InputContext())

        assertTrue(prompt.contains("Ignore sponsor logos, ticketing boilerplate, social handles, QR labels, and page chrome."))
        assertTrue(prompt.contains("return one event per row when the rows clearly describe separate occurrences"))
    }

    @Test
    fun `buildHeavyImageObservationPrompt should include normalized OCR evidence but not final schema`() {
        val prompt = buildHeavyImageObservationPrompt(
            context = InputContext(),
            ocrText = "Concert — tonight\nWriter’s Room"
        )

        assertTrue(prompt.contains("Heavy mode stage 1/3: multimodal image observations"))
        assertTrue(prompt.contains("OCR text extracted from the image:"))
        assertTrue(prompt.contains("Concert - tonight Writer's Room"))
        assertTrue(prompt.contains("Return ONLY JSON in this exact shape:"))
        assertFalse(prompt.contains("\"startTime\": \"ISO-8601 with timezone offset\""))
    }

    @Test
    fun `buildFinalCompositionPrompt should keep unresolved dates empty instead of guessing`() {
        val prompt = buildFinalCompositionPrompt(
            context = InputContext(),
            stageLabel = HEAVY_AUDIO_STAGE_3,
            sourceType = "audio",
            observations = """{"events":[]}""",
            temporalResolution = """{"events":[]}"""
        )

        assertTrue(prompt.contains("Use the temporal-resolution JSON for startTime and endTime when available."))
        assertTrue(prompt.contains("Match observation events to temporal-resolution events by array index"))
        assertTrue(prompt.contains("If a date cannot be resolved safely, leave startTime and endTime empty rather than inventing one."))
        assertTrue(prompt.contains("Observation JSON:"))
        assertTrue(prompt.contains("Temporal-resolution JSON:"))
    }
}
