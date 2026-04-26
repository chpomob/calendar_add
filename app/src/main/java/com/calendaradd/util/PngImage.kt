package com.calendaradd.util

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50.toByte(),
    0x4E.toByte(),
    0x47.toByte(),
    0x0D.toByte(),
    0x0A.toByte(),
    0x1A.toByte(),
    0x0A.toByte()
)

fun ByteArray.hasPngHeader(): Boolean {
    return size >= PNG_SIGNATURE.size &&
        indices.take(PNG_SIGNATURE.size).all { index -> this[index] == PNG_SIGNATURE[index] }
}
