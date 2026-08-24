package com.calendaradd.util

import android.net.Uri
import java.security.MessageDigest

/**
 * P1/R3: canonical content-key computation for ACTION_SEND shares. ShareImportActivity binds its
 * consent and handoff tokens to these keys and MainActivity recomputes the key from the payload
 * it is about to import, so a token minted for one share can never authorize different content.
 * Text is SHA-256-bound; media is URI-bound — identical to the keys used before this helper
 * existed, now shared so both ends can never drift.
 */
object ShareContentKeys {
    private const val TEXT_PREFIX = "text:"
    private const val IMAGE_PREFIX = "image:"
    private const val AUDIO_PREFIX = "audio:"

    /**
     * P1/R2: the previewability gate for text shares. text/plain with no usable EXTRA_TEXT
     * cannot be previewed, so ShareImportActivity must terminate locally — never forward it.
     */
    fun previewableTextOrNull(text: String?): String? = text?.takeIf { it.isNotBlank() }

    fun forText(text: String): String = TEXT_PREFIX + sha256Hex(text)

    fun forImage(uri: Uri): String = IMAGE_PREFIX + uri

    fun forAudio(uri: Uri): String = AUDIO_PREFIX + uri

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
