package com.calendaradd.service

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal data class HeavyImageScheduleRow(
    val title: String,
    val location: String,
    val startTime: String,
    val endTime: String
)

internal fun parseOcrScheduleRows(ocrText: String, timezone: String): List<HeavyImageScheduleRow> {
    val lines = ocrText.lineSequence()
        .map { normalizeVisibleText(it) }
        .filter { it.isNotBlank() }
        .toList()
    if (lines.isEmpty()) return emptyList()

    val rows = mutableListOf<HeavyImageScheduleRow>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (!looksLikeDateLine(line)) {
            index++
            continue
        }

        val title = findNextMeaningfulLine(lines, index + 1)
        val timeLine = findNextTimeLine(lines, index + 1)
        if (title != null && timeLine != null) {
            val timeRange = parseTimeRange(timeLine) ?: run {
                index++
                continue
            }
            val date = parseDateLine(line) ?: run {
                index++
                continue
            }
            val location = findLikelyLocationLine(lines, 0, index)
            val start = ZonedDateTime.of(date, timeRange.start, ZoneId.of(timezone))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val end = ZonedDateTime.of(date, timeRange.end, ZoneId.of(timezone))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            rows += HeavyImageScheduleRow(
                title = title,
                location = location.orEmpty(),
                startTime = start,
                endTime = end
            )
        }
        index++
    }
    return rows
}

private fun findNextMeaningfulLine(lines: List<String>, startIndex: Int): String? {
    for (index in startIndex until lines.size) {
        val line = lines[index]
        if (looksLikeDateLine(line) || looksLikeTimeLine(line) || line.equals("Event flyer", ignoreCase = true)) {
            continue
        }
        return line
    }
    return null
}

private fun findNextTimeLine(lines: List<String>, startIndex: Int): String? {
    for (index in startIndex until lines.size) {
        val line = lines[index]
        if (looksLikeDateLine(line)) return null
        if (looksLikeTimeLine(line)) return line
    }
    return null
}

private fun findLikelyLocationLine(lines: List<String>, startIndex: Int, endIndexExclusive: Int): String? {
    for (index in startIndex until endIndexExclusive) {
        val line = lines[index]
        if (looksLikeLocationLine(line)) {
            return line
        }
    }
    return null
}

private fun parseDateLine(value: String): LocalDate? {
    val formatters = listOf(
        DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH)
    )
    return formatters.firstNotNullOfOrNull { formatter ->
        try {
            LocalDate.parse(value, formatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

private fun parseTimeRange(value: String): TimeRange? {
    val pattern = Regex("""(?i)^\s*(.+?)\s*[-–]\s*(.+?)\s*$""")
    val match = pattern.matchEntire(value) ?: return null
    val start = parseClockTime(match.groupValues[1]) ?: return null
    val end = parseClockTime(match.groupValues[2]) ?: return null
    return TimeRange(start, end)
}

private fun parseClockTime(value: String): LocalTime? {
    val trimmed = normalizeVisibleText(value).replace(".", "")
    val formatters = listOf(
        DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("h a", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("hh a", Locale.ENGLISH)
    )
    return formatters.firstNotNullOfOrNull { formatter ->
        try {
            LocalTime.parse(trimmed.uppercase(Locale.ENGLISH), formatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

private fun looksLikeDateLine(value: String): Boolean {
    return value.matches(Regex("(?i)^(mon|tues|wednes|thurs|fri|satur|sun)day,?\\s+.*$")) ||
        value.matches(Regex("(?i)^(january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2},\\s+\\d{4}$")) ||
        value.matches(Regex("(?i)^(mon|tues|wednes|thurs|fri|satur|sun)day,?\\s+(january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2},\\s+\\d{4}$"))
}

private fun looksLikeTimeLine(value: String): Boolean {
    return value.matches(Regex("(?i)^\\d{1,2}(:\\d{2})?\\s*[ap]m\\s*[-–]\\s*\\d{1,2}(:\\d{2})?\\s*[ap]m$"))
}

private fun looksLikeLocationLine(value: String): Boolean {
    return value.contains("(") ||
        value.contains(")") ||
        value.contains(Regex("(?i)\\b(online|virtual|zoom|teams|meet|room|suite|hall|center|centre|building|campus|auditorium|library|office|floor)\\b"))
}

internal fun normalizeVisibleText(value: String): String {
    return value.trim()
        .replace('’', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('–', '-')
        .replace('—', '-')
        .replace(Regex("\\s+"), " ")
}

private data class TimeRange(
    val start: LocalTime,
    val end: LocalTime
)
