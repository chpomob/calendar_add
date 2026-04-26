package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.usecase.InputContext
import com.calendaradd.util.hasPngHeader
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

class ImageFixtureSuiteTest {

    private val gson = Gson()

    @Test
    fun `image fixture suite should load and exercise the prompt contract`() = runBlocking {
        val manifest = loadManifest()

        assertTrue("Expected at least one image fixture", manifest.cases.isNotEmpty())

        manifest.cases.forEach { fixture ->
            val imageBytes = readResourceBytes("image-fixtures/${fixture.imageFile}")
            assertTrue("Fixture ${fixture.id} must be a png file", imageBytes.hasPngHeader())
            assertTrue("Fixture ${fixture.id} title must not be empty", fixture.flyerText.title.isNotBlank())
            assertTrue("Fixture ${fixture.id} source title must not be empty", fixture.sourceTitle.isNotBlank())
            assertTrue("Fixture ${fixture.id} source url must look valid", fixture.sourceUrl.startsWith("http"))

            val expectedEvents = fixture.expectedEvents?.map { it.toEventExtraction() }
                ?: listOfNotNull(fixture.expectedEvent?.toEventExtraction())
            val extractor = CapturingExtractor(
                response = gson.toJson(mapOf("events" to expectedEvents))
            )
            val service = TextAnalysisService(extractor)
            val context = fixture.context.toInputContext()
            val bitmap = mockk<Bitmap>(relaxed = true)

            val result = service.analyzeImage(bitmap, context)

            assertEquals("Fixture ${fixture.id} should produce the expected number of events", expectedEvents.size, result.size)
            expectedEvents.zip(result).forEachIndexed { index, (expected, actual) ->
                assertEventMatchesExpected("${fixture.id}[$index]", expected, actual)
            }
            if (expectedEvents.isEmpty()) {
                assertTrue("Fixture ${fixture.id} should return no events", result.isEmpty())
            }
            assertTrue("Fixture ${fixture.id} prompt should mention flyers", extractor.lastPrompt?.contains("flyer, poster, screenshot, or event notice") == true)
            assertTrue("Fixture ${fixture.id} prompt should use exact visible event details", extractor.lastPrompt?.contains("Use the exact visible event title, date, time, and location when they are present.") == true)
            assertTrue(
                "Fixture ${fixture.id} prompt should split visible schedules into multiple events",
                extractor.lastPrompt?.contains("schedule table or a flyer series with multiple explicit date/time rows") == true
            )
            assertTrue("Fixture ${fixture.id} prompt should include the reference datetime", extractor.lastPrompt?.contains(fixture.context.datetime) == true)
            assertTrue("Fixture ${fixture.id} should pass the image bitmap to the extractor", extractor.lastImage != null)
        }
    }

    private fun loadManifest(): ImageFixtureManifest {
        val json = readResourceText("image-fixtures/manifest.json")
        return gson.fromJson(json, ImageFixtureManifest::class.java)
    }

    private fun readResourceText(path: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun readResourceBytes(path: String): ByteArray {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.use { it.readBytes() }
    }

    private fun ImageFixtureContext.toInputContext(): InputContext {
        val zoned = ZonedDateTime.parse(datetime)
        return InputContext(
            timestamp = zoned.toInstant().toEpochMilli(),
            timezone = timezone,
            language = language
        )
    }

    private fun ImageFixtureEvent.toEventExtraction(): EventExtraction {
        return EventExtraction(
            title = title,
            description = description,
            startTime = startTime,
            endTime = endTime,
            location = location,
            attendees = attendees
        )
    }

    private fun assertEventMatchesExpected(
        fixtureId: String,
        expected: EventExtraction,
        actual: EventExtraction
    ) {
        assertEquals("Fixture $fixtureId title mismatch", expected.title, actual.title)
        assertEquals("Fixture $fixtureId description mismatch", expected.description, actual.description)
        assertEquals("Fixture $fixtureId start time mismatch", expected.startTime, actual.startTime)
        assertEquals("Fixture $fixtureId end time mismatch", expected.endTime, actual.endTime)
        assertEquals("Fixture $fixtureId location mismatch", expected.location, actual.location)
        assertEquals("Fixture $fixtureId attendees mismatch", expected.attendees, actual.attendees)
    }

    private class CapturingExtractor(
        private val response: String
    ) : EventJsonExtractor {
        var lastPrompt: String? = null
            private set
        var lastImage: Bitmap? = null
            private set

        override suspend fun extractEventJson(
            text: String,
            image: Bitmap?,
            audio: ByteArray?
        ): String? {
            lastPrompt = text
            lastImage = image
            return response
        }
    }
}

data class ImageFixtureManifest(
    val cases: List<ImageFixtureCase>
)

data class ImageFixtureCase(
    val id: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val context: ImageFixtureContext,
    val flyerText: ImageFixtureFlyerText,
    val imageFile: String,
    @SerializedName("expectedEvent")
    val expectedEvent: ImageFixtureEvent? = null,
    @SerializedName("expectedEvents")
    val expectedEvents: List<ImageFixtureEvent>? = null
)

data class ImageFixtureContext(
    val datetime: String,
    val timezone: String,
    val language: String
)

data class ImageFixtureFlyerText(
    val title: String,
    val date: String,
    val time: String,
    val location: String,
    val description: String
)

data class ImageFixtureEvent(
    val title: String,
    val description: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val attendees: List<String>
)
