package com.calendaradd.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val MODEL_AUDIO_SAMPLE_RATE_HZ = 16_000

fun pcm16MonoToWav(
    pcmData: ByteArray,
    sampleRateHz: Int = MODEL_AUDIO_SAMPLE_RATE_HZ
): ByteArray {
    val channelCount = 1
    val bitsPerSample = 16
    val byteRate = sampleRateHz * channelCount * bitsPerSample / 8
    val blockAlign = channelCount * bitsPerSample / 8
    val wav = ByteArray(WAV_HEADER_SIZE + pcmData.size)
    val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

    buffer.putAscii("RIFF")
    buffer.putInt(36 + pcmData.size)
    buffer.putAscii("WAVE")
    buffer.putAscii("fmt ")
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(channelCount.toShort())
    buffer.putInt(sampleRateHz)
    buffer.putInt(byteRate)
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(bitsPerSample.toShort())
    buffer.putAscii("data")
    buffer.putInt(pcmData.size)
    buffer.put(pcmData)

    return wav
}

fun ByteArray.hasWavHeader(): Boolean {
    return size >= WAV_HEADER_SIZE &&
        startsWithAscii("RIFF", 0) &&
        startsWithAscii("WAVE", 8)
}

private const val WAV_HEADER_SIZE = 44

private fun ByteArray.startsWithAscii(value: String, offset: Int): Boolean {
    if (offset + value.length > size) return false
    return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
}

private fun ByteBuffer.putAscii(value: String) {
    value.forEach { char -> put(char.code.toByte()) }
}
