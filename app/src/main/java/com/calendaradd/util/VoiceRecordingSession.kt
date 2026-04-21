package com.calendaradd.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException

private const val RECORDING_DIR = "voice-recordings"
private const val RECORDING_EXTENSION = ".m4a"

class VoiceRecordingSession private constructor(
    private val recorder: MediaRecorder,
    val outputFile: File,
    private val startedAtElapsedRealtime: Long
) {
    companion object {
        fun start(context: Context): VoiceRecordingSession {
            val outputFile = createOutputFile(context)
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            try {
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                    start()
                }
                return VoiceRecordingSession(
                    recorder = recorder,
                    outputFile = outputFile,
                    startedAtElapsedRealtime = SystemClock.elapsedRealtime()
                )
            } catch (error: Exception) {
                runCatching { recorder.reset() }
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
            recorder.stop()
            outputFile.readBytes()
        } catch (error: Exception) {
            throw IOException("Could not stop microphone recording cleanly.", error)
        } finally {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            outputFile.delete()
        }
    }

    fun cancel() {
        runCatching { recorder.stop() }
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
        outputFile.delete()
    }
}
