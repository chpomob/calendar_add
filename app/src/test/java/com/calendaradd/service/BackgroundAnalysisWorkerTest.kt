package com.calendaradd.service

import com.calendaradd.util.pcm16MonoToWav
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BackgroundAnalysisWorkerTest {

    @Test
    fun `hasExceededBackgroundAttemptLimit should stop after two retries`() {
        assertFalse(hasExceededBackgroundAttemptLimit(0))
        assertFalse(hasExceededBackgroundAttemptLimit(1))
        assertTrue(hasExceededBackgroundAttemptLimit(2))
    }

    @Test
    fun `buildProgressMessage should label retry attempts`() {
        assertEquals("Initializing Gemma...", buildProgressMessage("Initializing Gemma...", 0))
        assertEquals("Retry 2: Initializing Gemma...", buildProgressMessage("Initializing Gemma...", 1))
    }

    @Test
    fun `buildAnalysisTimeoutMessage should suggest heavy mode mitigation`() {
        val message = buildAnalysisTimeoutMessage()

        assertTrue(message.contains("timed out"))
        assertTrue(message.contains("heavy mode"))
    }

    @Test
    fun `probeAudioDurationMs should fall back to wav header duration`() {
        val pcmBytes = ByteArray(16_000 * 2)
        val wavFile = File.createTempFile("calendar-add-duration", ".wav").apply {
            writeBytes(pcm16MonoToWav(pcmBytes))
            deleteOnExit()
        }

        assertEquals(1_000L, probeAudioDurationMs(wavFile))
    }

    @Test
    fun `readWavDurationMs should parse wav with metadata chunk before data`() {
        val wavFile = File.createTempFile("calendar-add-duration-list", ".wav").apply {
            writeBytes(nonCanonicalWav(byteRate = 32_000, dataSize = 32_000))
            deleteOnExit()
        }

        assertEquals(1_000L, readWavDurationMs(wavFile))
    }
}

private fun nonCanonicalWav(byteRate: Int, dataSize: Int): ByteArray {
    val listPayload = "INFO".toByteArray()
    val fmtPayload = ByteBuffer.allocate(16)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(1.toShort())
        .putShort(1.toShort())
        .putInt(16_000)
        .putInt(byteRate)
        .putShort(2.toShort())
        .putShort(16.toShort())
        .array()
    val dataPayload = ByteArray(dataSize)
    val totalSize = 4 + chunkSize("fmt ", fmtPayload) + chunkSize("LIST", listPayload) + chunkSize("data", dataPayload)
    return ByteBuffer.allocate(8 + totalSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray())
        .putInt(totalSize)
        .put("WAVE".toByteArray())
        .putChunk("fmt ", fmtPayload)
        .putChunk("LIST", listPayload)
        .putChunk("data", dataPayload)
        .array()
}

private fun chunkSize(id: String, payload: ByteArray): Int {
    return 8 + payload.size + (payload.size % 2)
}

private fun ByteBuffer.putChunk(id: String, payload: ByteArray): ByteBuffer {
    put(id.toByteArray())
    putInt(payload.size)
    put(payload)
    if (payload.size % 2 == 1) {
        put(0)
    }
    return this
}
