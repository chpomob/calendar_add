package com.calendaradd.service

internal fun combineHeavyModeResponses(
    observations: String?,
    temporalResolution: String?,
    finalJson: String?
): String {
    return buildString {
        appendLine("Heavy mode observation stage:")
        appendLine(observations ?: "<no response>")
        appendLine()
        appendLine("Heavy mode temporal stage:")
        appendLine(temporalResolution ?: "<no response>")
        appendLine()
        appendLine("Heavy mode final stage:")
        appendLine(finalJson ?: "<no response>")
    }.trim()
}

data class AnalysisDebugSnapshot(
    val traceId: String,
    val rawResponse: String?,
    val cleanedResponse: String?,
    val issue: String?
)

data class AnalysisFailureDebug(
    val title: String,
    val body: String
)

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

internal fun mergeRelatedEventExtractions(events: List<EventExtraction>): List<EventExtraction> {
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
