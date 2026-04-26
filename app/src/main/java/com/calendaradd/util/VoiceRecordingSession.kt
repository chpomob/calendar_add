package com.calendaradd.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val RECORDING_DIR = "voice-recordings"
private const val RECORDING_EXTENSION = ".wav"
private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
private const val BYTES_PER_SAMPLE = 2

class VoiceRecordingSession private constructor(
    private val recorder: AudioRecord,
    val outputFile: File,
    private val startedAtElapsedRealtime: Long,
    private val isRecording: AtomicBoolean,
    private val pcmOutput: ByteArrayOutputStream,
    private val recordingThread: Thread
) {
    companion object {
        @SuppressLint("MissingPermission")
        fun start(context: Context): VoiceRecordingSession {
            val outputFile = createOutputFile(context)
            val minBufferSize = AudioRecord.getMinBufferSize(
                MODEL_AUDIO_SAMPLE_RATE_HZ,
                AUDIO_CHANNEL_CONFIG,
                AUDIO_ENCODING
            )
            if (minBufferSize <= 0) {
                outputFile.delete()
                throw IOException("Could not create microphone recording buffer.")
            }

            val bufferSize = max(
                minBufferSize,
                MODEL_AUDIO_SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 10
            )
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(MODEL_AUDIO_SAMPLE_RATE_HZ)
                .setChannelMask(AUDIO_CHANNEL_CONFIG)
                .setEncoding(AUDIO_ENCODING)
                .build()
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
            val isRecording = AtomicBoolean(false)
            val pcmOutput = ByteArrayOutputStream()

            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IOException("Microphone recorder was not initialized.")
                }
                recorder.startRecording()
                isRecording.set(true)
                val recordingThread = Thread(
                    {
                        val buffer = ByteArray(bufferSize)
                        while (isRecording.get()) {
                            val read = recorder.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                synchronized(pcmOutput) {
                                    pcmOutput.write(buffer, 0, read)
                                }
                            }
                        }
                    },
                    "calendaradd-audio-record"
                ).apply { start() }

                return VoiceRecordingSession(
                    recorder = recorder,
                    outputFile = outputFile,
                    startedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                    isRecording = isRecording,
                    pcmOutput = pcmOutput,
                    recordingThread = recordingThread
                )
            } catch (error: Exception) {
                isRecording.set(false)
                runCatching { recorder.release() }
                outputFile.delete()
                throw IOException("Could not start microphone recording.", error)
            }
        }

        private fun createOutputFile(context: Context): File {
            val dir = File(context.noBackupFilesDir, RECORDING_DIR).apply { mkdirs() }
            return File(dir, "voice_${SystemClock.elapsedRealtime()}$RECORDING_EXTENSION")
        }
    }

    fun elapsedMillis(): Long = SystemClock.elapsedRealtime() - startedAtElapsedRealtime

    fun stopAndReadBytes(): ByteArray {
        return try {
            stopRecorder()
            val pcmBytes = synchronized(pcmOutput) {
                pcmOutput.toByteArray()
            }
            pcm16MonoToWav(pcmBytes)
        } catch (error: Exception) {
            throw IOException("Could not stop microphone recording cleanly.", error)
        } finally {
            runCatching { recorder.release() }
            outputFile.delete()
        }
    }

    fun cancel() {
        runCatching { stopRecorder() }
        runCatching { recorder.release() }
        outputFile.delete()
    }

    private fun stopRecorder() {
        if (isRecording.getAndSet(false)) {
            runCatching { recorder.stop() }
        }
        runCatching { recordingThread.join(1_000L) }
    }
}
