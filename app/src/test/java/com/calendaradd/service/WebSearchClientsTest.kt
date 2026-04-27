package com.calendaradd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchClientsTest {

    @Test
    fun `normalizeSearchTokens should preserve French terms and drop short noise`() {
        val tokens = "SMASH INTO PIECES à l'Élysée Montmartre, soirée 2026!".normalizeSearchTokens()

        assertTrue("Expected accented venue token", tokens.contains("élysée"))
        assertTrue("Expected French event token", tokens.contains("soirée"))
        assertTrue("Expected year token", tokens.contains("2026"))
        assertTrue("Short preposition should be dropped", "à" !in tokens)
        assertTrue("Short article should be dropped", "l" !in tokens)
    }

    @Test
    fun `BraveSearchApiClient should prefer event page over generic high ranked page`() {
        val client = BraveSearchApiClient("test-key")
        val responseBody = """
            {
              "web": {
                "results": [
                  {
                    "title": "Elysée Montmartre - Home",
                    "url": "https://www.elyseemontmartre.com/",
                    "description": "Official venue website."
                  },
                  {
                    "title": "SMASH INTO PIECES - Elysée Montmartre",
                    "url": "https://www.elyseemontmartre.com/fr/programmation/smash-into-pieces/",
                    "description": "Jeudi 07 mai 2026, à 19h30."
                  }
                ]
              }
            }
        """.trimIndent()

        val result = client.selectBestResultUrl(
            query = "SMASH INTO PIECES Elysée Montmartre 2026",
            responseBody = responseBody
        )

        assertEquals("https://www.elyseemontmartre.com/fr/programmation/smash-into-pieces/", result)
    }

    @Test
    fun `BraveSearchApiClient should return null when response has no web results`() {
        val client = BraveSearchApiClient("test-key")

        val result = client.selectBestResultUrl(
            query = "unknown event",
            responseBody = """{"query":{"original":"unknown event"}}"""
        )

        assertEquals(null, result)
    }
}
