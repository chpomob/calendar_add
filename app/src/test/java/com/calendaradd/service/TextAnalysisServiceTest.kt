package com.calendaradd.service

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextAnalysisServiceTest {

    private lateinit var gemmaLlmService: EventJsonExtractor
    private lateinit var imageTextExtractor: ImageTextExtractor
    private lateinit var textAnalysisService: TextAnalysisService

    @Before
    fun setup() {
        gemmaLlmService = mockk()
        imageTextExtractor = mockk()
        textAnalysisService = TextAnalysisService(gemmaLlmService, imageTextExtractor)
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
        
        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeText(input)

        // Then
        assertEquals("Meeting", result.title)
        assertEquals("2026-04-19T10:00:00", result.startTime)
        assertEquals(2, result.attendees.size)
    }

    @Test
    fun `analyzeImage should extract OCR text and call LLM with text only`() = runBlocking {
        // Given
        val bitmap = mockk<android.graphics.Bitmap>()
        val ocrText = "Board meeting tomorrow at 9am"
        val jsonResponse = "{\"title\": \"Event from image\"}"

        coEvery { imageTextExtractor.extractText(bitmap) } returns ocrText
        coEvery { gemmaLlmService.extractEventJson(any(), null, null) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeImage(bitmap)

        // Then
        assertEquals("Event from image", result.title)
        coVerify(exactly = 1) { imageTextExtractor.extractText(bitmap) }
        coVerify(exactly = 0) { gemmaLlmService.extractEventJson(any(), bitmap, null) }
    }

    @Test
    fun `analyzeImage should fail clearly when OCR finds no text`() = runBlocking {
        val bitmap = mockk<android.graphics.Bitmap>()

        coEvery { imageTextExtractor.extractText(bitmap) } returns "   "

        try {
            textAnalysisService.analyzeImage(bitmap)
            assertTrue("Expected analyzeImage to throw when OCR text is missing", false)
        } catch (e: IllegalArgumentException) {
            assertEquals("No readable text was found in the selected image.", e.message)
        }
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
        assertEquals("Event from audio", result.title)
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

        assertEquals(listOf("Alice", "Bob"), result.attendees)
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

        assertEquals("Dinner", result.title)
        assertEquals("With Sam", result.description)
    }
}
