package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val promptDateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

internal const val HEAVY_IMAGE_STAGE_1 = "Heavy mode stage 1/3: multimodal image observations"
internal const val HEAVY_IMAGE_STAGE_2 = "Heavy mode stage 2/3: temporal normalization"
internal const val HEAVY_IMAGE_STAGE_3 = "Heavy mode stage 3/3: final event composition"
internal const val HEAVY_AUDIO_STAGE_1 = "Heavy mode stage 1/3: multimodal audio observations"
internal const val HEAVY_AUDIO_STAGE_2 = "Heavy mode stage 2/3: temporal normalization"
internal const val HEAVY_AUDIO_STAGE_3 = "Heavy mode stage 3/3: final event composition"

internal fun buildReferencePrompt(context: InputContext): String {
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
        appendLine("Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.")
    }
}

internal fun buildFinalEventJsonInstructions(): String {
    return buildString {
        appendLine("If the input contains multiple fragments about the same event, merge them into one event.")
        appendLine("If the input contains multiple distinct events, return them all.")
        appendLine("Return ONLY valid JSON in this exact shape: { \"events\": [ { \"title\": \"\", \"description\": \"\", \"startTime\": \"ISO-8601 with timezone offset\", \"endTime\": \"ISO-8601 with timezone offset\", \"location\": \"\", \"attendees\": [] } ] }")
        appendLine("If there is only one event, still return it inside the events array.")
        appendLine("If there are no events, return { \"events\": [] }.")
    }
}

internal fun buildHeavyImageObservationPrompt(context: InputContext): String {
    return buildString {
        append(buildReferencePrompt(context))
        appendLine(HEAVY_IMAGE_STAGE_1)
        appendLine("Inspect the image conservatively and capture raw event evidence before normalization.")
        appendLine("Treat the image as a flyer, poster, screenshot, or event notice.")
        appendLine("Prefer exact visible event title, date, time, and location text.")
        appendLine("Treat visible virtual-location text such as Online, Virtual, Zoom, or Teams as a real location value.")
        appendLine("Do not invent details that are not visible.")
        appendLine("If the image shows a schedule table or recurring series with multiple explicit date/time rows, keep one candidate per row when the rows clearly describe separate occurrences.")
        appendLine("Do not merge distinct schedule rows into one generic observation just because they share the same date, venue, or series title.")
        appendLine("For schedule rows, keep the row's own visible title separate from the flyer or series title.")
        appendLine("The flyer banner title is not the event title for individual schedule rows.")
        appendLine("Copy the row title exactly as visible and do not add extra words, adjectives, or paraphrases.")
        appendLine("When copying locations, preserve spaces, punctuation, and parentheses as visible instead of compressing them.")
        appendLine("Return ONLY JSON in this exact shape:")
        appendLine("{ \"events\": [ { \"titleCandidates\": [], \"descriptionCandidates\": [], \"locationCandidates\": [], \"dateCandidates\": [], \"timeCandidates\": [], \"supportingText\": [], \"notes\": [] } ], \"globalNotes\": [] }")
        appendLine("Keep multiple candidate dates or times if the image is ambiguous.")
        appendLine("Copy visible phrases as they appear when useful. Do not output final ISO timestamps yet.")
    }
}

internal fun buildHeavyImageObservationPrompt(context: InputContext, ocrText: String?): String {
    return buildString {
        append(buildHeavyImageObservationPrompt(context))
        if (!ocrText.isNullOrBlank()) {
            appendLine("OCR text extracted from the image:")
            appendLine(normalizeVisibleText(ocrText))
        }
    }
}

internal fun buildHeavyAudioObservationPrompt(context: InputContext): String {
    return buildString {
        append(buildReferencePrompt(context))
        appendLine(HEAVY_AUDIO_STAGE_1)
        appendLine("Listen to the audio conservatively and capture raw event evidence before normalization.")
        appendLine("Only capture actual calendar commitments; ignore incidental future mentions and general availability statements.")
        appendLine("Do not invent a generic meeting when the audio only mentions a time or date without naming an event.")
        appendLine("The audio may contain filler words, background noise, repeated fragments, and ASR mistakes.")
        appendLine("Keep the intended event, not the transcription artifacts.")
        appendLine("Return ONLY JSON in this exact shape:")
        appendLine("{ \"events\": [ { \"titleCandidates\": [], \"descriptionCandidates\": [], \"locationCandidates\": [], \"dateCandidates\": [], \"timeCandidates\": [], \"quotedPhrases\": [], \"notes\": [] } ], \"globalNotes\": [] }")
        appendLine("Keep multiple candidate dates or times if the speaker is ambiguous.")
        appendLine("Do not output final ISO timestamps yet.")
    }
}

internal fun buildTemporalResolutionPrompt(
    context: InputContext,
    stageLabel: String,
    sourceType: String,
    observations: String
): String {
    return buildString {
        append(buildReferencePrompt(context))
        appendLine(stageLabel)
        appendLine("You are resolving temporal information for a heavy $sourceType extraction pass.")
        appendLine("Use the observation JSON below and focus only on dates, times, durations, and event boundaries.")
        appendLine("Return ONLY JSON in this exact shape:")
        appendLine("{ \"events\": [ { \"resolvedStartTime\": \"ISO-8601 or empty\", \"resolvedEndTime\": \"ISO-8601 or empty\", \"dateReasoning\": \"\", \"remainingAmbiguity\": \"\" } ] }")
        appendLine("If you cannot safely resolve a time, leave it empty instead of guessing.")
        appendLine("Observation JSON:")
        appendLine(observations)
    }
}

internal fun buildFinalCompositionPrompt(
    context: InputContext,
    stageLabel: String,
    sourceType: String,
    observations: String,
    temporalResolution: String
): String {
    return buildString {
        append(buildReferencePrompt(context))
        appendLine(stageLabel)
        appendLine("You are composing final events for a heavy $sourceType extraction pass.")
        appendLine("Use the observation JSON for titles, descriptions, locations, and attendees.")
        appendLine("Use the temporal-resolution JSON for startTime and endTime when available.")
        appendLine("Return ONLY valid JSON in this exact shape: { \"events\": [ { \"title\": \"\", \"description\": \"\", \"startTime\": \"ISO-8601\", \"endTime\": \"ISO-8601\", \"location\": \"\", \"attendees\": [] } ] }")
        appendLine("Keep multiple distinct events if the earlier stages found them.")
        appendLine("If a date cannot be resolved safely, leave startTime and endTime empty rather than inventing one.")
        appendLine("Observation JSON:")
        appendLine(observations)
        appendLine()
        appendLine("Temporal-resolution JSON:")
        appendLine(temporalResolution)
    }
}
