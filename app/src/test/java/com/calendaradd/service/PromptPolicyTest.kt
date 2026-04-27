package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import com.calendaradd.usecase.PreferencesManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPolicyTest {

    @Test
    fun `light prompts stay compact and generic`() = runBlocking {
        val extractor = mockk<EventJsonExtractor>()
        val service = TextAnalysisService(extractor)
        val prompt = slot<String>()
        val image = mockk<android.graphics.Bitmap>(relaxed = true)
        val audio = byteArrayOf(1, 2, 3)

        coEvery { extractor.extractEventJson(capture(prompt), null, null) } returns """{"events": []}"""
        service.analyzeText("Meet tomorrow at 10", InputContext())
        assertPromptBudget(prompt.captured, 2500)
        assertGenericPrompt(prompt.captured)

        coEvery { extractor.extractEventJson(capture(prompt), image, null) } returns """{"events": []}"""
        service.analyzeImage(image, InputContext())
        assertPromptBudget(prompt.captured, 2500)
        assertGenericPrompt(prompt.captured)

        coEvery { extractor.extractEventJson(capture(prompt), null, audio) } returns """{"events": []}"""
        service.analyzeAudio(audio, InputContext())
        assertPromptBudget(prompt.captured, 2500)
        assertGenericPrompt(prompt.captured)
    }

    @Test
    fun `heavy prompts stay stage based and within budget`() = runBlocking {
        val extractor = mockk<EventJsonExtractor>()
        val prefs = mockk<PreferencesManager>()
        val service = TextAnalysisService(extractor, prefs)
        val prompt = slot<String>()
        val image = mockk<android.graphics.Bitmap>(relaxed = true)
        val audio = byteArrayOf(1, 2, 3)

        every { prefs.isHeavyAnalysisEnabled } returns true
        every { prefs.isWebVerificationEnabled } returns false

        coEvery {
            extractor.extractEventJson(capture(prompt), image, null)
        } returns """{"events":[{"titleCandidates":["Town hall"],"dateCandidates":["tomorrow"],"timeCandidates":["7pm"]}]}"""
        coEvery {
            extractor.extractEventJson(match { it.contains("Heavy mode stage 2/3: temporal normalization") }, null, null)
        } returns """{"events":[{"resolvedStartTime":"2026-04-22T19:00:00+02:00","resolvedEndTime":"2026-04-22T20:00:00+02:00"}]}"""
        coEvery {
            extractor.extractEventJson(match { it.contains("Heavy mode stage 3/3: final event composition") }, null, null)
        } returns """{"events":[{"title":"Town hall","startTime":"2026-04-22T19:00:00+02:00","endTime":"2026-04-22T20:00:00+02:00","location":"Community Center","description":"Neighborhood update","attendees":[]}]}"""

        service.analyzeImage(image, InputContext())
        assertPromptBudget(prompt.captured, 3800)
        assertTrue(prompt.captured.contains("Heavy mode stage 1/3: multimodal image observations"))
        assertTrue(!prompt.captured.contains("source-specific"))

        coEvery {
            extractor.extractEventJson(capture(prompt), null, audio)
        } returns """{"events":[{"titleCandidates":["Dentist"],"dateCandidates":["next friday"],"timeCandidates":["9 in the morning"]}]}"""
        coEvery {
            extractor.extractEventJson(match { it.contains("Heavy mode stage 2/3: temporal normalization") }, null, null)
        } returns """{"events":[{"resolvedStartTime":"2026-04-24T09:00:00+02:00","resolvedEndTime":"2026-04-24T10:00:00+02:00"}]}"""
        coEvery {
            extractor.extractEventJson(match { it.contains("Heavy mode stage 3/3: final event composition") }, null, null)
        } returns """{"events":[{"title":"Dentist","startTime":"2026-04-24T09:00:00+02:00","endTime":"2026-04-24T10:00:00+02:00","location":"","description":"","attendees":[]}]}"""

        service.analyzeAudio(audio, InputContext())
        assertPromptBudget(prompt.captured, 3800)
        assertTrue(prompt.captured.contains("Heavy mode stage 1/3: multimodal audio observations"))
    }

    private fun assertPromptBudget(prompt: String, limit: Int) {
        assertTrue("Prompt exceeds budget of $limit chars: ${prompt.length}", prompt.length <= limit)
    }

    private fun assertGenericPrompt(prompt: String) {
        assertTrue(prompt.contains("Reference local datetime:"))
        assertTrue(prompt.contains("Return ONLY valid JSON"))
        assertTrue(!prompt.contains("wellness_workshop_series"))
        assertTrue(!prompt.contains("conference_sophie_montreuil_fr"))
        assertTrue(!prompt.contains("casablanca_prefect_office"))
    }
}
