package com.calendaradd.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
private const val BYTES_PER_SAMPLE = 2
private const val MAX_RECORDING_DURATION_MS = 60L * 1000L
private const val MAX_RECORDING_PCM_BYTES = MODEL_AUDIO_SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * 60

class VoiceRecordingSession private constructor(
    private val recorder: AudioRecord,
    private val startedAtElapsedRealtime: Long,
    private val isRecording: AtomicBoolean,
    private val pcmOutput: ByteArrayOutputStream,
    private val recordingThread: Thread
) {
    companion object {
        private const val TAG = "VoiceRecordingSession"

        @SuppressLint("MissingPermission")
        fun start(context: Context): VoiceRecordingSession {
            val appContext = context.applicationContext
            val minBufferSize = AudioRecord.getMinBufferSize(
                MODEL_AUDIO_SAMPLE_RATE_HZ,
                AUDIO_CHANNEL_CONFIG,
                AUDIO_ENCODING
            )
            if (minBufferSize <= 0) {
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
                val startedAt = SystemClock.elapsedRealtime()
                val recordingThread = Thread(
                    {
                        val buffer = ByteArray(bufferSize)
                        while (isRecording.get()) {
                            if (SystemClock.elapsedRealtime() - startedAt >= MAX_RECORDING_DURATION_MS) {
                                AppLog.w(TAG, "Recording reached duration limit; stopping")
                                isRecording.set(false)
                                break
                            }
                            val read = recorder.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                synchronized(pcmOutput) {
                                    if (pcmOutput.size() + read > MAX_RECORDING_PCM_BYTES) {
                                        AppLog.w(TAG, "Recording reached byte limit; stopping")
                                        isRecording.set(false)
                                        return@synchronized
                                    }
                                    pcmOutput.write(buffer, 0, read)
                                }
                            } else if (read < 0) {
                                // AudioRecord error (e.g. ERROR_INVALID_OPERATION=-3)
                                AppLog.w(TAG, "AudioRecord read error: $read, stopping")
                                isRecording.set(false)
                                break
                            }
                        }
                    },
                    "calendaradd-audio-record"
                ).apply { start() }

                return VoiceRecordingSession(
                    recorder = recorder,
                    startedAtElapsedRealtime = startedAt,
                    isRecording = isRecording,
                    pcmOutput = pcmOutput,
                    recordingThread = recordingThread
                )
            } catch (error: Exception) {
                isRecording.set(false)
                runCatching { recorder.release() }
                throw IOException("Could not start microphone recording for ${appContext.packageName}.", error)
            }
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
        }
    }

    fun cancel() {
        runCatching { stopRecorder() }
        runCatching { recorder.release() }
    }

    private fun stopRecorder() {
        if (isRecording.getAndSet(false)) {
            runCatching { recorder.stop() }
        }
        runCatching { recordingThread.join(1_000L) }
    }
}
