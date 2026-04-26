package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.usecase.InputContext
import com.calendaradd.util.hasWavHeader
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

class AudioFixtureSuiteTest {

    private val gson = Gson()

    @Test
    fun `audio fixture suite should load and exercise the prompt contract`() = runBlocking {
        val manifest = loadManifest()

        assertTrue("Expected at least one audio fixture", manifest.cases.isNotEmpty())

        manifest.cases.forEach { fixture ->
            val audioBytes = readResourceBytes("audio-fixtures/${fixture.audioFile}")
            assertTrue("Fixture ${fixture.id} must be a wav file", audioBytes.hasWavHeader())
            assertTrue("Fixture ${fixture.id} transcript must not be empty", fixture.transcript.isNotBlank())
            assertTrue("Fixture ${fixture.id} source title must not be empty", fixture.sourceTitle.isNotBlank())
            assertTrue("Fixture ${fixture.id} source url must look valid", fixture.sourceUrl.startsWith("http"))

            val expectedEvents = fixture.expectedEvents?.map { it.toEventExtraction() }
                ?: listOfNotNull(fixture.expectedEvent?.toEventExtraction())
            val extractor = CapturingExtractor(
                response = gson.toJson(mapOf("events" to expectedEvents))
            )
            val service = TextAnalysisService(extractor)
            val context = fixture.context.toInputContext()

            val result = service.analyzeAudio(audioBytes, context)

            assertEquals("Fixture ${fixture.id} should produce the expected number of events", expectedEvents.size, result.size)
            expectedEvents.zip(result).forEachIndexed { index, (expected, actual) ->
                assertEventMatchesExpected("${fixture.id}[$index]", expected, actual)
            }
            if (expectedEvents.isEmpty()) {
                assertTrue("Fixture ${fixture.id} should return no events", result.isEmpty())
            }
            assertTrue("Fixture ${fixture.id} prompt should be sent to the model", extractor.lastPrompt?.contains("Input type: audio") == true)
            assertTrue(
                "Fixture ${fixture.id} prompt should keep the noisy-audio guardrails",
                extractor.lastPrompt?.contains("filler words, background noise, repeated fragments, and ASR mistakes") == true
            )
            assertTrue(
                "Fixture ${fixture.id} prompt should avoid incidental time mentions",
                extractor.lastPrompt?.contains("Ignore incidental mentions of time, generic future statements, or status updates") == true
            )
            assertTrue(
                "Fixture ${fixture.id} prompt should avoid inventing generic meeting titles",
                extractor.lastPrompt?.contains("do not invent a generic title like Meeting") == true
            )
            assertTrue(
                "Fixture ${fixture.id} prompt should include the reference datetime",
                extractor.lastPrompt?.contains(fixture.context.datetime) == true
            )
            assertTrue("Fixture ${fixture.id} should pass the actual WAV bytes to the extractor", extractor.lastAudio?.contentEquals(audioBytes) == true)
        }
    }

    private fun loadManifest(): AudioFixtureManifest {
        val json = readResourceText("audio-fixtures/manifest.json")
        return gson.fromJson(json, AudioFixtureManifest::class.java)
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

    private fun AudioFixtureContext.toInputContext(): InputContext {
        val zoned = ZonedDateTime.parse(datetime)
        return InputContext(
            timestamp = zoned.toInstant().toEpochMilli(),
            timezone = timezone,
            language = language
        )
    }

    private fun AudioFixtureEvent.toEventExtraction(): EventExtraction {
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
        var lastAudio: ByteArray? = null
            private set

        override suspend fun extractEventJson(
            text: String,
            image: Bitmap?,
            audio: ByteArray?
        ): String? {
            lastPrompt = text
            lastAudio = audio
            return response
        }
    }
}

data class AudioFixtureManifest(
    val cases: List<AudioFixtureCase>
)

data class AudioFixtureCase(
    val id: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val context: AudioFixtureContext,
    val transcript: String,
    val audioFile: String,
    @SerializedName("expectedEvent")
    val expectedEvent: AudioFixtureEvent? = null,
    @SerializedName("expectedEvents")
    val expectedEvents: List<AudioFixtureEvent>? = null
)

data class AudioFixtureContext(
    val datetime: String,
    val timezone: String,
    val language: String
)

data class AudioFixtureEvent(
    val title: String,
    val description: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val attendees: List<String>
)
