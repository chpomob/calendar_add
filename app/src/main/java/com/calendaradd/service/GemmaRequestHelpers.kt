package com.calendaradd.service

import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

private const val GEMMA_MAX_IMAGE_DIMENSION = 1280
private const val GEMMA_PNG_QUALITY = 100
private const val LITERTLM_CALLBACK_POLL_MS = 250L

internal data class PreparedImageBytes(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val sizeBytes: Int
)

internal enum class RequestMode {
    TEXT,
    IMAGE,
    AUDIO,
    IMAGE_AND_AUDIO
}

internal fun requestMode(image: Bitmap?, audio: ByteArray?): RequestMode {
    return when {
        image != null && audio != null -> RequestMode.IMAGE_AND_AUDIO
        image != null -> RequestMode.IMAGE
        audio != null -> RequestMode.AUDIO
        else -> RequestMode.TEXT
    }
}

internal fun Bitmap.toModelImageBytes(): PreparedImageBytes {
    require(!isRecycled) { "Bitmap is already recycled" }

    val normalizedBitmap = ensureArgb8888()
    val preparedBitmap = normalizedBitmap.scaleDownIfNeeded(GEMMA_MAX_IMAGE_DIMENSION)
    val output = ByteArrayOutputStream()

    return try {
        check(preparedBitmap.compress(Bitmap.CompressFormat.PNG, GEMMA_PNG_QUALITY, output)) {
            "Bitmap compression failed"
        }
        val imageBytes = output.toByteArray()
        PreparedImageBytes(
            bytes = imageBytes,
            width = preparedBitmap.width,
            height = preparedBitmap.height,
            sizeBytes = imageBytes.size
        )
    } finally {
        if (preparedBitmap !== normalizedBitmap) {
            preparedBitmap.recycle()
        }
        if (normalizedBitmap !== this && normalizedBitmap !== preparedBitmap) {
            normalizedBitmap.recycle()
        }
    }
}

private fun Bitmap.ensureArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    return copy(Bitmap.Config.ARGB_8888, false)
}

internal fun Bitmap?.describeForLogs(): String {
    if (this == null) return "none"
    return "size=${width}x${height},config=${config ?: "null"},bytes=$allocationByteCount,recycled=$isRecycled"
}

internal fun ByteArray?.describeForLogs(): String {
    return if (this == null) "none" else "bytes=$size"
}

private fun Bitmap.scaleDownIfNeeded(maxDimension: Int): Bitmap {
    val largestSide = max(width, height)
    if (largestSide <= maxDimension) return this

    val scale = maxDimension.toFloat() / largestSide.toFloat()
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

internal fun Conversation.awaitResponse(
    contents: Contents,
    requestId: String,
    cancellationJob: Job?
): String? {
    val completed = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val response = StringBuilder()

    try {
        sendMessageAsync(
            contents,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val chunk = message.textChunk()
                    if (chunk.isNotBlank()) {
                        synchronized(response) {
                            response.append(chunk)
                        }
                    }
                }

                override fun onDone() {
                    completed.countDown()
                }

                override fun onError(throwable: Throwable) {
                    failure.set(throwable)
                    completed.countDown()
                }
            },
            emptyMap()
        )

        while (!completed.await(LITERTLM_CALLBACK_POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancellationJob?.isActive == false) {
                cancelProcess()
                throw CancellationException("LiteRT-LM request $requestId was cancelled")
            }
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        cancelProcess()
        throw CancellationException("LiteRT-LM request $requestId was interrupted")
    }

    failure.get()?.let { error ->
        if (error is Exception) throw error
        throw RuntimeException("LiteRT-LM async callback failed", error)
    }

    return synchronized(response) {
        response.toString().ifBlank { null }
    }
}

private fun Message.textChunk(): String {
    val contentText = contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString("\n") { it.text }
    return contentText.ifBlank { toString() }
}
