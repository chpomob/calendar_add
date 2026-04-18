package com.calendaradd.service

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TextAnalysisServiceTest {

    private lateinit var gemmaLlmService: EventJsonExtractor
    private lateinit var textAnalysisService: TextAnalysisService

    @Before
    fun setup() {
        gemmaLlmService = mockk()
        textAnalysisService = TextAnalysisService(gemmaLlmService)
    }

    @Test
    fun `analyzeText should return EventExtraction when LLM returns valid JSON`() = runBlocking {
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
        
        coEvery { gemmaLlmService.extractEventJson(input, null, null) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeText(input)

        // Then
        assertEquals("Meeting", result.title)
        assertEquals("2026-04-19T10:00:00", result.startTime)
        assertEquals(2, result.attendees.size)
    }

    @Test
    fun `analyzeImage should call LLM with image`() = runBlocking {
        // Given
        val bitmap = mockk<android.graphics.Bitmap>()
        val jsonResponse = "{\"title\": \"Event from image\"}"
        
        coEvery { gemmaLlmService.extractEventJson("Extract event from this image.", bitmap, null) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeImage(bitmap)

        // Then
        assertEquals("Event from image", result.title)
    }

    @Test
    fun `analyzeAudio should call LLM with audio data`() = runBlocking {
        // Given
        val audioData = byteArrayOf(1, 2, 3)
        val jsonResponse = "{\"title\": \"Event from audio\"}"
        
        coEvery { gemmaLlmService.extractEventJson("Extract event from this audio recording.", null, audioData) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeAudio(audioData)

        // Then
        assertEquals("Event from audio", result.title)
    }
}
