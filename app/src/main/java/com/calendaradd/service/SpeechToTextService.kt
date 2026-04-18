package com.calendaradd.service

import com.google.mlkit.genai.speech.SpeechRecognition
import com.google.mlkit.genai.speech.SpeechRecognizerOptions
import com.google.mlkit.genai.speech.SpeechRecognizerRequest
import com.google.mlkit.genai.speech.AudioSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Service for on-device speech-to-text using ML Kit GenAI.
 */
class SpeechToTextService {

    private val options = SpeechRecognizerOptions.builder()
        .setLocale(Locale.getDefault())
        .setMode(SpeechRecognizerOptions.MODE_ADVANCED)
        .build()

    private val speechRecognizer = SpeechRecognition.getClient(options)

    /**
     * Transcribes audio from the microphone.
     */
    fun transcribeMicrophone(): Flow<String> {
        val request = SpeechRecognizerRequest.Builder()
            .setAudioSource(AudioSource.microphone())
            .build()

        return speechRecognizer.startRecognition(request).map { response ->
            response.text
        }
    }

    /**
     * Closes the recognizer.
     */
    fun close() {
        speechRecognizer.close()
    }
}
