package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.usecase.InputContext
import com.calendaradd.util.AppLog
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for orchestrating the AI analysis pipeline.
 */
class TextAnalysisService(
    private val gemmaLlmService: EventJsonExtractor
) {
    companion object {
        private const val TAG = "TextAnalysisService"
        private val promptDateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }

    /**
     * Analyzes text input and extracts event information.
     */
    suspend fun analyzeText(
        input: String,
        context: InputContext = InputContext()
    ): List<EventExtraction> = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "[${context.traceId}] Analyzing text chars=${input.length}")
        val promptText = buildString {
            append(buildReferencePrompt(context))
            appendLine("Input type: text")
            appendLine("Extract calendar events from this user text.")
            appendLine("User input: $input")
        }
        val jsonString = gemmaLlmService.extractEventJson(text = promptText)
        parseJsonToExtractions(jsonString, context.traceId)
    }

    /**
     * Analyzes image for event information.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        context: InputContext = InputContext()
    ): List<EventExtraction> = withContext(Dispatchers.IO) {
        AppLog.i(
            TAG,
            "[${context.traceId}] Analyzing image bitmap=${bitmap.width}x${bitmap.height} config=${bitmap.config ?: "null"}"
        )
        val promptText = buildString {
            append(buildReferencePrompt(context))
            appendLine("Input type: image")
            appendLine("Extract calendar events from this image.")
            appendLine("If the image contains relative date or time phrases, resolve them using the reference local datetime above.")
        }
        val jsonString = gemmaLlmService.extractEventJson(text = promptText, image = bitmap)
        parseJsonToExtractions(jsonString, context.traceId)
    }

    /**
     * Analyzes audio for event information.
     */
    suspend fun analyzeAudio(
        audioData: ByteArray,
        context: InputContext = InputContext()
    ): List<EventExtraction> = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "[${context.traceId}] Analyzing audio bytes=${audioData.size}")
        val promptText = buildString {
            append(buildReferencePrompt(context))
            appendLine("Input type: audio")
            appendLine("Extract calendar events from this audio recording.")
            appendLine("If the speaker says relative dates or times, resolve them using the reference local datetime above.")
        }
        val jsonString = gemmaLlmService.extractEventJson(text = promptText, audio = audioData)
        parseJsonToExtractions(jsonString, context.traceId)
    }

    private fun buildReferencePrompt(context: InputContext): String {
        val zonedReference = Instant.ofEpochMilli(context.timestamp).atZone(ZoneId.of(context.timezone))
        return buildString {
            appendLine("Reference local datetime: ${promptDateTimeFormatter.format(zonedReference)}")
            appendLine("Reference timezone: ${context.timezone}")
            appendLine("Reference day of week: ${zonedReference.dayOfWeek}")
            appendLine("User language: ${context.language}")
            appendLine(
                "Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, " +
                    "next Friday, this weekend, and in two days against the reference local datetime."
            )
            appendLine("Return absolute ISO-8601 values in startTime and endTime. Never leave relative words in the JSON output.")
        }
    }

    private fun parseJsonToExtractions(jsonString: String?, traceId: String): List<EventExtraction> {
        if (jsonString == null) {
            AppLog.w(TAG, "[$traceId] LLM returned no response")
            return emptyList()
        }
        
        return try {
            val cleaned = jsonString.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val jsonPayload = cleaned.extractJsonPayload()
            val element = JsonParser.parseString(jsonPayload)
            val rawEvents = element.asEventObjects()
                .map(::parseEventObject)
                .filter { it.hasMeaningfulContent() }

            val mergedEvents = mergeRelatedEvents(rawEvents)
            AppLog.i(
                TAG,
                "[$traceId] Parsed response chars=${jsonString.length} rawEvents=${rawEvents.size} mergedEvents=${mergedEvents.size}"
            )
            mergedEvents
        } catch (e: Exception) {
            AppLog.e(TAG, "[$traceId] Failed to parse LLM response chars=${jsonString.length}", e)
            emptyList()
        }
    }

    private fun JsonElement.asEventObjects(): List<JsonObject> {
        return when {
            isJsonObject -> {
                val obj = asJsonObject
                val eventsArray = obj.get("events")
                if (eventsArray?.isJsonArray == true) {
                    eventsArray.asJsonArray.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
                } else {
                    listOf(obj)
                }
            }
            isJsonArray -> asJsonArray.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            else -> emptyList()
        }
    }

    private fun parseEventObject(json: JsonObject): EventExtraction {
        return EventExtraction(
            title = json.stringValue("title"),
            description = json.stringValue("description"),
            startTime = json.stringValue("startTime"),
            endTime = json.stringValue("endTime"),
            location = json.stringValue("location"),
            attendees = json.attendeesValue("attendees")
        )
    }

    private fun mergeRelatedEvents(events: List<EventExtraction>): List<EventExtraction> {
        val merged = mutableListOf<EventExtraction>()

        for (event in events) {
            val existingIndex = merged.indexOfFirst { it.canMergeWith(event) }
            if (existingIndex >= 0) {
                merged[existingIndex] = merged[existingIndex].mergeWith(event)
            } else {
                merged += event.normalized()
            }
        }

        return merged
    }

    private fun JsonObject.stringValue(key: String): String {
        return get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    }

    private fun JsonObject.attendeesValue(key: String): List<String> {
        val rawValue = get(key) ?: return emptyList()
        return when {
            rawValue.isJsonArray -> rawValue.asJsonArray
                .mapNotNull { element -> element.takeIf { !it.isJsonNull }?.asString?.trim() }
            rawValue.isJsonPrimitive -> rawValue.asString
                .split(",", ";", "\n")
                .map { it.trim() }
            else -> emptyList()
        }.filter { it.isNotEmpty() }
            .distinct()
    }

    private fun String.extractJsonPayload(): String {
        val firstBrace = indexOf('{')
        val firstBracket = indexOf('[')
        val objectStartsFirst = firstBrace >= 0 && (firstBracket < 0 || firstBrace < firstBracket)

        return if (objectStartsFirst) {
            val lastBrace = lastIndexOf('}')
            if (lastBrace > firstBrace) substring(firstBrace, lastBrace + 1) else this
        } else if (firstBracket >= 0) {
            val lastBracket = lastIndexOf(']')
            if (lastBracket > firstBracket) substring(firstBracket, lastBracket + 1) else this
        } else {
            this
        }
    }
}

/**
 * Data class for event extraction results.
 */
data class EventExtraction(
    val title: String,
    val description: String,
    val startTime: String, // ISO-8601
    val endTime: String,   // ISO-8601
    val location: String,
    val attendees: List<String>,
    val confidence: Float? = null
)

fun EventExtraction.hasMeaningfulContent(): Boolean {
    return title.isNotBlank() ||
        description.isNotBlank() ||
        startTime.isNotBlank() ||
        endTime.isNotBlank() ||
        location.isNotBlank() ||
        attendees.isNotEmpty()
}

private fun EventExtraction.normalized(): EventExtraction {
    return copy(
        title = title.trim(),
        description = description.trim(),
        startTime = startTime.trim(),
        endTime = endTime.trim(),
        location = location.trim(),
        attendees = attendees.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    )
}

private fun EventExtraction.canMergeWith(other: EventExtraction): Boolean {
    val thisTitle = title.normalizedKey()
    val otherTitle = other.title.normalizedKey()
    val titlesCompatible = when {
        thisTitle.isBlank() || otherTitle.isBlank() -> true
        else -> thisTitle == otherTitle
    }

    if (!titlesCompatible) return false

    // If neither fragment has a title, require at least one shared non-empty anchor.
    if (thisTitle.isBlank() && otherTitle.isBlank()) {
        val sharedStart = startTime.isNotBlank() && startTime == other.startTime
        val sharedEnd = endTime.isNotBlank() && endTime == other.endTime
        val sharedLocation = location.isNotBlank() && location.equals(other.location, ignoreCase = true)
        val sharedDescription = description.normalizedKey() == other.description.normalizedKey() &&
            description.normalizedKey().isNotBlank()

        if (!(sharedStart || sharedEnd || sharedLocation || sharedDescription)) {
            return false
        }
    }

    val startsCompatible = startTime.isBlank() || other.startTime.isBlank() || startTime == other.startTime
    val endsCompatible = endTime.isBlank() || other.endTime.isBlank() || endTime == other.endTime
    val locationsCompatible = location.isBlank() || other.location.isBlank() || location.equals(other.location, ignoreCase = true)

    return startsCompatible && endsCompatible && locationsCompatible
}

private fun EventExtraction.mergeWith(other: EventExtraction): EventExtraction {
    return EventExtraction(
        title = longerNonBlank(title, other.title),
        description = mergeDescriptions(description, other.description),
        startTime = firstNonBlank(startTime, other.startTime),
        endTime = firstNonBlank(endTime, other.endTime),
        location = longerNonBlank(location, other.location),
        attendees = (attendees + other.attendees).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        confidence = listOfNotNull(confidence, other.confidence).maxOrNull()
    )
}

private fun String.normalizedKey(): String {
    return trim().lowercase().replace(Regex("\\s+"), " ")
}

private fun firstNonBlank(first: String, second: String): String {
    return first.takeIf { it.isNotBlank() } ?: second
}

private fun longerNonBlank(first: String, second: String): String {
    return listOf(first, second)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .maxByOrNull { it.length }
        .orEmpty()
}

private fun mergeDescriptions(first: String, second: String): String {
    val parts = listOf(first.trim(), second.trim()).filter { it.isNotBlank() }.distinct()
    return parts.joinToString("\n\n")
}
