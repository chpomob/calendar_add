package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import com.calendaradd.util.LinkPreview
import com.calendaradd.util.LinkPreviewService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WebVerificationServiceTest {

    @Test
    fun `refineImageEvents should skip when no useful search hints are present`() = runBlocking {
        val linkPreviewService = mockk<LinkPreviewService>()
        val searchClient = mockk<WebSearchClient>()
        val service = WebVerificationService(linkPreviewService, searchClient)
        val events = listOf(
            EventExtraction(
                title = "",
                description = "",
                startTime = "",
                endTime = "",
                location = "",
                attendees = emptyList()
            )
        )

        val result = service.refineImageEvents(events, "Flyer\n1:00 PM - 2:00 PM", InputContext())

        assertEquals(events, result)
        coVerify(exactly = 0) { linkPreviewService.getLinkPreview(any()) }
        coVerify(exactly = 0) { searchClient.findFirstResultUrl(any()) }
    }

    @Test
    fun `refineImageEvents should search by extracted event evidence when no url is present`() = runBlocking {
        val linkPreviewService = mockk<LinkPreviewService>()
        val searchClient = mockk<WebSearchClient>()
        val service = WebVerificationService(linkPreviewService, searchClient)
        val ocrText = """
            Event flyer
            Spring Showcase 2026
            Main Hall
            Friday, May 1, 2026
        """.trimIndent()
        val preview = LinkPreview(
            url = "https://events.example.com/spring-showcase",
            title = "Spring Showcase 2026",
            description = "Location: Main Hall. Join us for the spring showcase event.",
            imageUrl = null,
            faviconUrl = null
        )
        coEvery {
            searchClient.findFirstResultUrl(match { it.contains("Spring Showcase 2026") && it.contains("Main Hall") })
        } returns "https://events.example.com/spring-showcase"
        coEvery { linkPreviewService.getLinkPreview("https://events.example.com/spring-showcase") } returns preview

        val result = service.refineImageEvents(
            currentEvents = listOf(
                EventExtraction(
                    title = "Event",
                    description = "",
                    startTime = "2026-05-01T18:00:00-07:00",
                    endTime = "2026-05-01T20:00:00-07:00",
                    location = "",
                    attendees = emptyList()
                )
            ),
            ocrText = ocrText,
            context = InputContext()
        )

        assertEquals("Spring Showcase 2026", result.single().title)
        assertEquals("Location: Main Hall. Join us for the spring showcase event.", result.single().description)
        assertEquals("Main Hall", result.single().location)
        coVerify(exactly = 1) {
            searchClient.findFirstResultUrl(match { it.contains("Spring Showcase 2026") && it.contains("Main Hall") })
        }
        coVerify(exactly = 1) { linkPreviewService.getLinkPreview("https://events.example.com/spring-showcase") }
    }
}
