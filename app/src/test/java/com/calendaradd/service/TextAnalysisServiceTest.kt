package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import com.calendaradd.usecase.PreferencesManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TextAnalysisServiceTest {

    private lateinit var gemmaLlmService: EventJsonExtractor
    private lateinit var textAnalysisService: TextAnalysisService

    @Before
    fun setup() {
        gemmaLlmService = mockk()
        textAnalysisService = TextAnalysisService(gemmaLlmService)
    }

    @Test
    fun `analyzeText should return single extraction when LLM returns valid JSON`() = runBlocking {
        // Given
        val input = "Meeting at 10am tomorrow"
        val jsonResponse = """
            {
                "title": "Meeting",
                "description": "Team sync",
                "startTime": "2026-04-19T10:00:00",
                "endTime": "2026-04-19T11:00:00",
                "location": "Office",
                "attendees": ["Alice", "Bob"]
            }
        """.trimIndent()
        
        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeText(input)

        // Then
        assertEquals(1, result.size)
        assertEquals("Meeting", result.first().title)
        assertEquals("2026-04-19T10:00:00", result.first().startTime)
        assertEquals(2, result.first().attendees.size)
    }

    @Test
    fun `analyzeImage should call LLM with image`() = runBlocking {
        // Given
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        val jsonResponse = "{\"title\": \"Event from image\"}"

        coEvery { gemmaLlmService.extractEventJson(any(), bitmap, null) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeImage(bitmap)

        // Then
        assertEquals("Event from image", result.first().title)
    }

    @Test
    fun `analyzeAudio should call LLM with audio data`() = runBlocking {
        // Given
        val audioData = byteArrayOf(1, 2, 3)
        val jsonResponse = "{\"title\": \"Event from audio\"}"
        
        coEvery { gemmaLlmService.extractEventJson(any(), null, audioData) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeAudio(audioData)

        // Then
        assertEquals("Event from audio", result.first().title)
    }

    @Test
    fun `analyzeText should accept attendees as comma separated string`() = runBlocking {
        val input = "Meet Alice and Bob tomorrow"
        val jsonResponse = """
            {
                "title": "Meeting",
                "attendees": "Alice, Bob, Alice"
            }
        """.trimIndent()

        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns jsonResponse

        val result = textAnalysisService.analyzeText(input)

        assertEquals(listOf("Alice", "Bob"), result.first().attendees)
    }

    @Test
    fun `analyzeText should parse JSON wrapped in extra prose`() = runBlocking {
        val input = "Dinner with Sam at 7pm"
        val jsonResponse = """
            Here is the extracted event:
            {
                "title": "Dinner",
                "description": "With Sam"
            }
        """.trimIndent()

        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns jsonResponse

        val result = textAnalysisService.analyzeText(input)

        assertEquals("Dinner", result.first().title)
        assertEquals("With Sam", result.first().description)
    }

    @Test
    fun `analyzeText should parse multiple events from events wrapper`() = runBlocking {
        val input = "Lunch tomorrow and dentist on Friday"
        val jsonResponse = """
            {
              "events": [
                { "title": "Lunch", "startTime": "2026-04-20T12:00:00" },
                { "title": "Dentist", "startTime": "2026-04-24T09:00:00" }
              ]
            }
        """.trimIndent()

        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns jsonResponse

        val result = textAnalysisService.analyzeText(input)

        assertEquals(2, result.size)
        assertEquals("Lunch", result[0].title)
        assertEquals("Dentist", result[1].title)
    }

    @Test
    fun `analyzeText should include explicit relative date instructions in prompt`() = runBlocking {
        val capturedPrompt = slot<String>()
        val referenceTimestamp = ZonedDateTime.of(2026, 4, 21, 9, 30, 0, 0, ZoneId.of("Europe/Paris"))
            .toInstant()
            .toEpochMilli()

        coEvery { gemmaLlmService.extractEventJson(capture(capturedPrompt), null, null) } returns """{"events": []}"""

        textAnalysisService.analyzeText(
            input = "Meeting tomorrow at 10",
            context = InputContext(
                timestamp = referenceTimestamp,
                timezone = "Europe/Paris",
                language = "en"
            )
        )

        assertTrue(capturedPrompt.captured.contains("Reference local datetime: 2026-04-21T09:30:00+02:00"))
        assertTrue(capturedPrompt.captured.contains("Resolve relative date and time phrases such as today, tomorrow"))
        assertTrue(capturedPrompt.captured.contains("Return absolute ISO-8601 values with timezone offsets in startTime and endTime"))
        assertTrue(capturedPrompt.captured.contains("\"startTime\": \"ISO-8601 with timezone offset\""))
    }

    @Test
    fun `analyzeAudio should tell the model to resolve spoken relative dates`() = runBlocking {
        val capturedPrompt = slot<String>()
        val audioData = byteArrayOf(1, 2, 3)

        coEvery { gemmaLlmService.extractEventJson(capture(capturedPrompt), null, audioData) } returns """{"events": []}"""

        textAnalysisService.analyzeAudio(audioData)

        assertTrue(capturedPrompt.captured.contains("Input type: audio"))
        assertTrue(capturedPrompt.captured.contains("filler words, background noise, repeated fragments, and ASR mistakes"))
        assertTrue(capturedPrompt.captured.contains("Extract the intended calendar event from this audio only when the speaker clearly proposes, confirms, schedules, or reschedules a concrete calendar item."))
        assertTrue(capturedPrompt.captured.contains("If the speaker says relative dates or times, resolve them using the reference local datetime above."))
        assertTrue(capturedPrompt.captured.contains("Return ONLY valid JSON"))
    }

    @Test
    fun `analyzeText should preserve debug snapshot when json parsing fails`() = runBlocking {
        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns "{"

        val result = textAnalysisService.analyzeText("Tomorrow at 10")
        val debugSnapshot = textAnalysisService.consumeLastDebugSnapshot()

        assertTrue(result.isEmpty())
        assertNotNull(debugSnapshot)
        assertEquals("{", debugSnapshot?.rawResponse)
        assertTrue(debugSnapshot?.issue?.contains("Failed to parse") == true)
        assertNull(textAnalysisService.consumeLastDebugSnapshot())
    }

    @Test
    fun `analyzeText should preserve debug snapshot when model returns no events`() = runBlocking {
        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns """{"events": []}"""

        val result = textAnalysisService.analyzeText("Nothing here")
        val debugSnapshot = textAnalysisService.consumeLastDebugSnapshot()

        assertTrue(result.isEmpty())
        assertEquals("""{"events": []}""", debugSnapshot?.cleanedResponse)
        assertEquals("The model response did not contain any usable events.", debugSnapshot?.issue)
    }

    @Test
    fun `analyzeImage should use heavy staged extraction when enabled`() = runBlocking {
        val heavyPreferences = mockk<PreferencesManager>()
        val heavyService = TextAnalysisService(gemmaLlmService, heavyPreferences)
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        val observationPrompt = slot<String>()
        every { heavyPreferences.isHeavyAnalysisEnabled } returns true

        coEvery {
            gemmaLlmService.extractEventJson(capture(observationPrompt), bitmap, null)
        } returns """{"events":[{"titleCandidates":["Town hall"],"dateCandidates":["tomorrow"],"timeCandidates":["7pm"]}]}"""
        coEvery {
            gemmaLlmService.extractEventJson(match { it.contains("Heavy mode stage 2/3: temporal normalization") }, null, null)
        } returns """{"events":[{"resolvedStartTime":"2026-04-22T19:00:00+02:00","resolvedEndTime":"2026-04-22T20:00:00+02:00"}]}"""
        coEvery {
            gemmaLlmService.extractEventJson(match { it.contains("Heavy mode stage 3/3: final event composition") }, null, null)
        } returns """{"events":[{"title":"Town hall","startTime":"2026-04-22T19:00:00+02:00","endTime":"2026-04-22T20:00:00+02:00","location":"Community Center","description":"Neighborhood update","attendees":[]}]}"""

        val result = heavyService.analyzeImage(bitmap)

        assertEquals(1, result.size)
        assertEquals("Town hall", result.first().title)
        assertEquals("Community Center", result.first().location)
        assertTrue(observationPrompt.captured.contains("Heavy mode stage 1/3: multimodal image observations"))
        assertTrue(observationPrompt.captured.contains("Treat the image as a flyer, poster, screenshot, or event notice."))
        assertTrue(observationPrompt.captured.contains("Prefer exact visible event title, date, time, and location text."))
        assertTrue(!observationPrompt.captured.contains("\"startTime\": \"ISO-8601 with timezone offset\""))
        verify(atLeast = 1) { heavyPreferences.isHeavyAnalysisEnabled }
    }

    @Test
    fun `analyzeAudio should use heavy staged extraction when enabled`() = runBlocking {
        val heavyPreferences = mockk<PreferencesManager>()
        val heavyService = TextAnalysisService(gemmaLlmService, heavyPreferences)
        val audioData = byteArrayOf(1, 2, 3)
        val observationPrompt = slot<String>()
        every { heavyPreferences.isHeavyAnalysisEnabled } returns true

        coEvery {
            gemmaLlmService.extractEventJson(capture(observationPrompt), null, audioData)
        } returns """{"events":[{"titleCandidates":["Dentist"],"dateCandidates":["next friday"],"timeCandidates":["9 in the morning"]}]}"""
        coEvery {
            gemmaLlmService.extractEventJson(match { it.contains("Heavy mode stage 2/3: temporal normalization") }, null, null)
        } returns """{"events":[{"resolvedStartTime":"2026-04-24T09:00:00+02:00","resolvedEndTime":"2026-04-24T10:00:00+02:00"}]}"""
        coEvery {
            gemmaLlmService.extractEventJson(match { it.contains("Heavy mode stage 3/3: final event composition") }, null, null)
        } returns """{"events":[{"title":"Dentist","startTime":"2026-04-24T09:00:00+02:00","endTime":"2026-04-24T10:00:00+02:00","location":"","description":"","attendees":[]}]}"""

        val result = heavyService.analyzeAudio(audioData)

        assertEquals(1, result.size)
        assertEquals("Dentist", result.first().title)
        assertEquals("2026-04-24T09:00:00+02:00", result.first().startTime)
        assertTrue(observationPrompt.captured.contains("Heavy mode stage 1/3: multimodal audio observations"))
        assertTrue(observationPrompt.captured.contains("filler words, background noise, repeated fragments, and ASR mistakes"))
        assertTrue(observationPrompt.captured.contains("Keep the intended event, not the transcription artifacts."))
        verify(atLeast = 1) { heavyPreferences.isHeavyAnalysisEnabled }
    }

    @Test
    fun `analyzeText should merge fragmented copies of the same event`() = runBlocking {
        val input = "Project kickoff tomorrow 10am at HQ with Alice"
        val jsonResponse = """
            {
              "events": [
                { "title": "Project kickoff", "startTime": "2026-04-20T10:00:00" },
                { "title": "Project kickoff", "location": "HQ", "attendees": ["Alice"] }
              ]
            }
        """.trimIndent()

        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns jsonResponse

        val result = textAnalysisService.analyzeText(input)

        assertEquals(1, result.size)
        assertEquals("Project kickoff", result.first().title)
        assertEquals("HQ", result.first().location)
        assertTrue(result.first().attendees.contains("Alice"))
    }

    @Test
    fun `analyzeText should not merge untitled events without a shared anchor`() = runBlocking {
        val input = "Bring snacks. Bring slides."
        val jsonResponse = """
            {
              "events": [
                { "description": "Bring snacks" },
                { "description": "Bring slides" }
              ]
            }
        """.trimIndent()

        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns jsonResponse

        val result = textAnalysisService.analyzeText(input)

        assertEquals(2, result.size)
        assertEquals("Bring snacks", result[0].description)
        assertEquals("Bring slides", result[1].description)
    }
}
