package com.calendaradd.service

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TextAnalysisServiceTest {

    private lateinit var gemmaLlmService: GemmaLlmService
    private lateinit var ocrService: OcrService
    private lateinit var textAnalysisService: TextAnalysisService

    @Before
    fun setup() {
        gemmaLlmService = mockk()
        ocrService = mockk()
        textAnalysisService = TextAnalysisService(gemmaLlmService, ocrService)
    }

    @Test
    fun `analyzeInput should return EventExtraction when LLM returns valid JSON`() = runBlocking {
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
        
        coEvery { gemmaLlmService.extractEventJson(input) } returns jsonResponse

        // When
        val result = textAnalysisService.analyzeInput(input)

        // Then
        assertEquals("Meeting", result.title)
        assertEquals("2026-04-19T10:00:00", result.startTime)
        assertEquals(2, result.attendees.size)
        assertEquals("Alice", result.attendees[0])
    }

    @Test
    fun `analyzeInput should handle JSON with markdown code blocks`() = runBlocking {
        // Given
        val input = "Party tonight"
        val jsonWithMarkdown = """
            ```json
            {
                "title": "Party",
                "description": "Birthday celebration",
                "startTime": "2026-04-18T20:00:00",
                "endTime": "",
                "location": "Home",
                "attendees": []
            }
            ```
        """.trimIndent()
        
        coEvery { gemmaLlmService.extractEventJson(input) } returns jsonWithMarkdown

        // When
        val result = textAnalysisService.analyzeInput(input)

        // Then
        assertEquals("Party", result.title)
        assertEquals("Home", result.location)
    }

    @Test
    fun `analyzeInput should return empty extraction when LLM returns invalid JSON`() = runBlocking {
        // Given
        val input = "Some text"
        coEvery { gemmaLlmService.extractEventJson(input) } returns "Invalid JSON"

        // When
        val result = textAnalysisService.analyzeInput(input)

        // Then
        assertEquals("", result.title)
    }
}
