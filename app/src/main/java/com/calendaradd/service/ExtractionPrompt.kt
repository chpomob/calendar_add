package com.calendaradd.service

/**
 * Extraction prompt templates for event creation.
 */
object ExtractionPrompt {

    const val DEFAULT_SYSTEM_PROMPT = """
        You are an AI assistant that extracts calendar events from user input.
        Extract only one event from the input. If multiple events exist, extract the first complete one.

        For each input, extract:
        - title: Event title (required, use input text if no title)
        - description: Event description/details
        - date: Date in YYYY-MM-DD format, or "today" if not specified
        - time: Time in HH:mm format (24-hour), or empty if not specified
        - duration: Duration in minutes (default 60)
        - location: Location name (optional)
        - attendees: Comma-separated list of attendees (optional)
        - description: Event description/details (optional)

        Return JSON format:
        {
            "title": "string",
            "description": "string or empty",
            "date": "YYYY-MM-DD or today",
            "time": "HH:mm or empty",
            "duration": "number",
            "location": "string or empty",
            "attendees": "comma-separated or empty"
        }

        If the input doesn't contain a valid event, return: null
    """

    const val TEXT_EXTRACTION_PROMPT = """
        Extract calendar event from this text:

        $input

        Return JSON or null if no valid event found.
    """

    const val AUDIO_TRANSCRIPTION_PROMPT = """
        Transcribe this audio and extract event:

        $transcription

        Return JSON or null if no valid event.
    """

    const val IMAGE_OCR_PROMPT = """
        Extract event information from this image:

        $ocrText

        Return JSON or null if no valid event.
    """

    fun getPrompt(input: String, modelType: String = "text"): String = when (modelType) {
        "text" -> TEXT_EXTRACTION_PROMPT.replace("$input", input)
        "audio" -> AUDIO_TRANSCRIPTION_PROMPT.replace("$transcription", input)
        "image" -> IMAGE_OCR_PROMPT.replace("$ocrText", input)
        else -> TEXT_EXTRACTION_PROMPT.replace("$input", input)
    }
}
