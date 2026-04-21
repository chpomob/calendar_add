package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        assertTrue(capturedPrompt.captured.contains("Return absolute ISO-8601 values in startTime and endTime"))
    }

    @Test
    fun `analyzeAudio should tell the model to resolve spoken relative dates`() = runBlocking {
        val capturedPrompt = slot<String>()
        val audioData = byteArrayOf(1, 2, 3)

        coEvery { gemmaLlmService.extractEventJson(capture(capturedPrompt), null, audioData) } returns """{"events": []}"""

        textAnalysisService.analyzeAudio(audioData)

        assertTrue(capturedPrompt.captured.contains("Input type: audio"))
        assertTrue(capturedPrompt.captured.contains("If the speaker says relative dates or times, resolve them using the reference local datetime above."))
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
