package com.calendaradd.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavAudioTest {
    @Test
    fun `pcm16MonoToWav should write a 16 kHz mono PCM wav header`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = pcm16MonoToWav(pcm)

        assertTrue(wav.hasWavHeader())
        assertEquals(44 + pcm.size, wav.size)
        assertEquals(16_000, wav.readLittleEndianInt(24))
        assertEquals(16_000 * 2, wav.readLittleEndianInt(28))
        assertEquals(1, wav.readLittleEndianShort(22))
        assertEquals(16, wav.readLittleEndianShort(34))
        assertEquals(pcm.size, wav.readLittleEndianInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
}

private fun ByteArray.readLittleEndianShort(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.readLittleEndianInt(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)
}
