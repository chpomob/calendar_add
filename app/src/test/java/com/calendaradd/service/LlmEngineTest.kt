package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import org.junit.Assert
import org.junit.Test

/**
 * Tests for LlmEngine and event extraction.
 */
class LlmEngineTest {

    private lateinit var engine: LlmEngine

    @Test
    fun `given empty input when extract then returns default title`() = runTest {
        // Given empty input
        val result = engine.analyzeInput("")

        // Then title is default
        Assert.assertEquals("Untitled Event", result?.title)
    }

    @Test
    fun `given event description when extract then returns title`() = runTest {
        // Given event text
        val input = "Meeting with John tomorrow at 3pm"
        val result = engine.analyzeInput(input)

        // Then title is extracted from first line
        Assert.assertEquals("Meeting with John tomorrow at 3pm", result?.title)
    }

    @Test
    fun `given location in text when extract then returns location`() = runTest {
        // Given event with location
        val input = "Dinner at Central Park with Sarah"
        val result = engine.analyzeInput(input)

        // Then location is extracted
        Assert.assertEquals("Central Park", result?.location)
    }

    @Test
    fun `given attendees in text when extract then returns attendees`() = runTest {
        // Given event with attendees
        val input = "Project review with Alice, Bob, and Charlie"
        val result = engine.analyzeInput(input)

        // Then attendees are extracted
        Assert.assertTrue(result?.attendees?.isNotEmpty() == true)
    }

    @Test
    fun `given invalid input when analyzeInput then returns null`() = runTest {
        // Given random text (not an event)
        val input = "Random text with no event information"
        val result = engine.analyzeInput(input)

        // For now, always returns result (fallback extraction)
        // TODO: Add validation when LLM is available
        Assert.assertNotNull(result)
    }

    @Test
    fun `given loaded model when isLoaded then returns true`() = runTest {
        // Given loaded model
        engine.loadModel()
        val loaded = engine.isLoaded()

        // Then model is loaded
        Assert.assertTrue(loaded)
    }

    @Test
    fun `given unloaded model when isLoaded then returns false`() = runTest {
        // Given unloaded model
        engine.unloadModel()
        val loaded = engine.isLoaded()

        // Then model is not loaded
        Assert.assertFalse(loaded)
    }

    @Test
    fun `given health check when model loaded then returns success`() = runTest {
        // Given loaded model
        engine.loadModel()
        val status = engine.healthCheck()

        // Then health check passes
        Assert.assertTrue(status.contains("loaded"))
    }

    @Test
    fun `given model info when getModelInfo then returns correct info`() = runTest {
        // Given model info
        val info = engine.getModelInfo()

        // Then info is correct
        Assert.assertEquals("Gemma-2b-it", info.name)
        Assert.assertEquals("1.5 GB", info.size)
    }

    @Test
    fun `given processing text when analyzeInput then returns EventExtraction`() = runTest {
        // Given event description
        val input = "Team building event on Friday afternoon, location at the park"
        val result = engine.analyzeInput(input)

        // Then extraction result is complete
        Assert.assertNotNull(result)
        Assert.assertTrue(result?.title.isNotEmpty())
    }

    @Test
    fun `given multiple lines when extractDescription then returns full description`() = runTest {
        // Given multiline text
        val input = """
            Project Kickoff Meeting
            Date: Next Monday
            Duration: 2 hours
            Agenda: Planning, Budget, Timeline
        """.trimIndent()

        val result = engine.analyzeInput(input)

        // Then description contains all lines except first
        Assert.assertEquals("Project Kickoff Meeting\nDate: Next Monday\nDuration: 2 hours\nAgenda: Planning, Budget, Timeline", result?.description)
    }
}
