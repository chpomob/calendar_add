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

    @Test
    fun `refineImageEvents should enrich French venue-only location with searched address`() = runBlocking {
        val linkPreviewService = mockk<LinkPreviewService>()
        val searchClient = mockk<WebSearchClient>()
        val service = WebVerificationService(linkPreviewService, searchClient)
        val eventPreview = LinkPreview(
            url = "https://lekilowatt.fr/agenda/instrumental-3/",
            title = "INSTRUMENTAL #3 - Le Kilowatt - Vitry-sur-Seine",
            description = "",
            imageUrl = null,
            faviconUrl = null,
            textSnippet = "INSTRUMENTAL #3 Vendredi 29 mai 2026 de 19h à 02h. De l'Electro à la Trance."
        )
        val venuePreview = LinkPreview(
            url = "https://lekilowatt.fr/infos-pratiques/",
            title = "Infos pratiques - Le Kilowatt - Vitry-sur-Seine",
            description = "",
            imageUrl = null,
            faviconUrl = null,
            textSnippet = "ACCÈS LE KILOWATT : ESPACE MARCEL PAUL - 18 rue des Fusillés 94400 Vitry-sur-Seine. RER C les Ardoines."
        )
        coEvery {
            searchClient.findFirstResultUrl(match { it.contains("INSTRUMENTAL #3") && it.contains("Le Kilowatt") })
        } returns "https://lekilowatt.fr/agenda/instrumental-3/"
        coEvery {
            searchClient.findFirstResultUrl(match { it.contains("Le Kilowatt") && it.contains("Vitry-sur-Seine") && it.contains("adresse") })
        } returns "https://lekilowatt.fr/infos-pratiques/"
        coEvery { linkPreviewService.getLinkPreview("https://lekilowatt.fr/agenda/instrumental-3/") } returns eventPreview
        coEvery { linkPreviewService.getLinkPreview("https://lekilowatt.fr/infos-pratiques/") } returns venuePreview

        val result = service.refineImageEvents(
            currentEvents = listOf(
                EventExtraction(
                    title = "INSTRUMENTAL #3",
                    description = "",
                    startTime = "2026-05-29T19:00:00+02:00",
                    endTime = "2026-05-30T02:00:00+02:00",
                    location = "Le Kilowatt",
                    attendees = emptyList()
                )
            ),
            ocrText = "INSTRUMENTAL #3\nVendredi 29 mai 2026\nLe Kilowatt\nVitry-sur-Seine",
            context = InputContext()
        )

        assertEquals("Le Kilowatt, 18 rue des Fusillés 94400 Vitry-sur-Seine", result.single().location)
        coVerify(exactly = 1) {
            searchClient.findFirstResultUrl(match { it.contains("Le Kilowatt") && it.contains("adresse") })
        }
    }

    @Test
    fun `refineImageEvents should keep already specific street location`() = runBlocking {
        val linkPreviewService = mockk<LinkPreviewService>()
        val searchClient = mockk<WebSearchClient>()
        val service = WebVerificationService(linkPreviewService, searchClient)
        val preview = LinkPreview(
            url = "https://lekilowatt.fr/infos-pratiques/",
            title = "Infos pratiques - Le Kilowatt - Vitry-sur-Seine",
            description = "LE KILOWATT : ESPACE MARCEL PAUL - 18 rue des Fusillés 94400 Vitry-sur-Seine",
            imageUrl = null,
            faviconUrl = null
        )
        coEvery { linkPreviewService.getLinkPreview("https://lekilowatt.fr/infos-pratiques/") } returns preview

        val result = service.refineImageEvents(
            currentEvents = listOf(
                EventExtraction(
                    title = "Concert",
                    description = "",
                    startTime = "2026-05-29T19:00:00+02:00",
                    endTime = "2026-05-30T02:00:00+02:00",
                    location = "18 rue des Fusillés 94400 Vitry-sur-Seine",
                    attendees = emptyList()
                )
            ),
            ocrText = "https://lekilowatt.fr/infos-pratiques/",
            context = InputContext()
        )

        assertEquals("18 rue des Fusillés 94400 Vitry-sur-Seine", result.single().location)
        coVerify(exactly = 0) { searchClient.findFirstResultUrl(any()) }
    }

    @Test
    fun `refineImageEvents should enrich Paris venue-only location with boulevard address`() = runBlocking {
        val linkPreviewService = mockk<LinkPreviewService>()
        val searchClient = mockk<WebSearchClient>()
        val service = WebVerificationService(linkPreviewService, searchClient)
        val eventPreview = LinkPreview(
            url = "https://www.elyseemontmartre.com/fr/programmation/smash-into-pieces/",
            title = "SMASH INTO PIECES - Elysée Montmartre",
            description = "",
            imageUrl = null,
            faviconUrl = null,
            textSnippet = "SMASH INTO PIECES Jeudi 07 mai 2026, à 19h30. 72 Boulevard de Rochechouart 75018 PARIS"
        )
        coEvery {
            searchClient.findFirstResultUrl(match { it.contains("SMASH INTO PIECES") && it.contains("Élysée Montmartre") })
        } returns "https://www.elyseemontmartre.com/fr/programmation/smash-into-pieces/"
        coEvery { linkPreviewService.getLinkPreview("https://www.elyseemontmartre.com/fr/programmation/smash-into-pieces/") } returns eventPreview

        val result = service.refineImageEvents(
            currentEvents = listOf(
                EventExtraction(
                    title = "SMASH INTO PIECES",
                    description = "",
                    startTime = "2026-05-07T19:30:00+02:00",
                    endTime = "",
                    location = "Élysée Montmartre",
                    attendees = emptyList()
                )
            ),
            ocrText = "SMASH INTO PIECES\nJeudi 07 mai 2026\nÉlysée Montmartre",
            context = InputContext()
        )

        assertEquals("Élysée Montmartre, 72 Boulevard de Rochechouart 75018 PARIS", result.single().location)
        coVerify(exactly = 1) {
            searchClient.findFirstResultUrl(match { it.contains("SMASH INTO PIECES") && it.contains("Élysée Montmartre") })
        }
    }

    @Test
    fun `BraveSearchApiClient should rank structured results by flyer evidence`() {
        val client = BraveSearchApiClient("test-key")
        val responseBody = """
            {
              "web": {
                "results": [
                  {
                    "title": "Generic ticket marketplace",
                    "url": "https://tickets.example.com/search?q=smash",
                    "description": "Buy and sell concert tickets."
                  },
                  {
                    "title": "SMASH INTO PIECES - Elysée Montmartre",
                    "url": "https://www.venue.example/fr/programmation/smash-into-pieces/",
                    "description": "Jeudi 07 mai 2026, à 19h30. 72 Boulevard de Rochechouart 75018 PARIS."
                  }
                ]
              }
            }
        """.trimIndent()

        val result = client.selectBestResultUrl(
            query = "SMASH INTO PIECES Élysée Montmartre 2026",
            responseBody = responseBody
        )

        assertEquals("https://www.venue.example/fr/programmation/smash-into-pieces/", result)
    }
}
