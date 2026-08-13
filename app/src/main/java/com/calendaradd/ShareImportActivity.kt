package com.calendaradd

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.calendaradd.service.BackgroundAnalysisScheduler
import com.calendaradd.service.ModelDownloadManager
import com.calendaradd.ui.theme.CalendarAddTheme
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.AppLog
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.ModelImageLoader
import com.calendaradd.util.ShareImportTokenStore
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P9: bridge activity for ACTION_SEND shares. Stays exported so third-party share sheets keep
 * working, but no longer trusts the sender -- it shows an inert preview of the shared content
 * (R1) and only starts analysis after the user taps Analyze, authorized by a one-shot token
 * (R2). If the model isn't downloaded it never forces a download (R3); it hands off to
 * MainActivity, which prefills the shared content and surfaces the normal P2/P4 download-consent
 * flow instead.
 */
class ShareImportActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ShareImportActivity"
        private const val TEXT_PREVIEW_CHAR_LIMIT = 280

        // R2: process-scoped so a token's lifetime is tied to the consent it guards, not to this
        // activity instance (which is noHistory and may be recreated between preview and confirm).
        private val tokenStore = ShareImportTokenStore()
    }

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var backgroundAnalysisScheduler: BackgroundAnalysisScheduler

    private val screenState = mutableStateOf<ShareImportUiState>(ShareImportUiState.Preparing)
    private var pendingToken: String? = null

    // A1: bumped on every intent delivery (onCreate/onNewIntent) so a preview build or a confirm
    // flow that started against an earlier share can detect it has been superseded and avoid
    // clobbering, finishing, or forwarding on behalf of content the user is no longer looking at.
    private var currentGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(this)
        modelDownloadManager = ModelDownloadManager(this, preferencesManager)
        backgroundAnalysisScheduler = BackgroundAnalysisScheduler(this)

        setContent {
            CalendarAddTheme {
                ShareImportScreen(
                    state = screenState.value,
                    onConfirm = ::confirmAnalysis,
                    onDismiss = ::dismiss
                )
            }
        }

        loadPreview(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // R2: a fresh delivery gets its own preview and token; drop any unconsumed token from a
        // prior delivery so it can never later be replayed against this new content.
        pendingToken?.let(tokenStore::invalidate)
        pendingToken = null
        loadPreview(intent)
    }

    /** R1: builds an inert preview only -- no persistence, enqueue, download, or model init. */
    private fun loadPreview(intent: Intent?) {
        screenState.value = ShareImportUiState.Preparing
        val generation = ++currentGeneration
        lifecycleScope.launch {
            val preview = runCatching { buildPreview(intent) }
                .onFailure { AppLog.e(TAG, "Failed to build share preview", it) }
                .getOrNull()

            // A1: a newer share intent arrived (onNewIntent) while this preview was building;
            // this result is stale and must never overwrite the state built for the newer one.
            if (generation != currentGeneration) {
                preview?.thumbnail?.recycle()
                return@launch
            }

            if (preview == null) {
                AppLog.i(TAG, "No previewable content in shared intent; falling back to MainActivity")
                forwardToMainActivity(intent)
                return@launch
            }

            val token = tokenStore.issue(preview.contentKey)
            pendingToken = token
            screenState.value = ShareImportUiState.ReadyForConsent(preview, generation, token)
        }
    }

    private suspend fun buildPreview(intent: Intent?): SharePreview? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return when {
            intent.type?.startsWith("text/") == true -> buildTextPreview(intent)
            intent.type?.startsWith("image/") == true -> buildImagePreview(intent)
            intent.type?.startsWith("audio/") == true -> buildAudioPreview(intent)
            else -> null
        }
    }

    private fun buildTextPreview(intent: Intent): SharePreview? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return null
        return SharePreview(
            type = ShareContentType.TEXT,
            contentKey = "text:" + sha256Hex(text),
            sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            textPreview = text.take(TEXT_PREVIEW_CHAR_LIMIT),
            textTruncated = text.length > TEXT_PREVIEW_CHAR_LIMIT,
            fullText = text,
            sourceIntent = intent
        )
    }

    private suspend fun buildImagePreview(intent: Intent): SharePreview? {
        val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
        return withContext(Dispatchers.IO) {
            val (displayName, sizeBytes) = queryMetadata(uri)
            // A decode-only read (no side effect) so the preview can show a real thumbnail; skip
            // it for oversized content rather than spending time on a decode confirm() will
            // reject anyway.
            val withinLimit = FileImportHandler.isWithinSizeLimit(
                contentResolver, uri, FileImportHandler.MAX_COMPRESSED_IMAGE_BYTES
            )
            val thumbnail = if (withinLimit) {
                runCatching { ModelImageLoader.loadForInference(contentResolver, uri) }.getOrNull()
            } else {
                null
            }
            SharePreview(
                type = ShareContentType.IMAGE,
                contentKey = "image:$uri",
                sizeBytes = sizeBytes,
                displayName = displayName,
                thumbnail = thumbnail,
                sourceUri = uri,
                sourceIntent = intent
            )
        }
    }

    private suspend fun buildAudioPreview(intent: Intent): SharePreview? {
        val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
        return withContext(Dispatchers.IO) {
            val (displayName, sizeBytes) = queryMetadata(uri)
            SharePreview(
                type = ShareContentType.AUDIO,
                contentKey = "audio:$uri",
                sizeBytes = sizeBytes,
                displayName = displayName,
                sourceUri = uri,
                sourceIntent = intent
            )
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                name to size
            }
        }.getOrNull() ?: (null to null)
    }

    /**
     * R1/R2: the explicit affirmative action. Only the token minted for the currently displayed
     * preview, and not already consumed, may proceed; a stale UI, a double tap, or a token bound
     * to different content is a silent no-op.
     */
    private fun confirmAnalysis(token: String) {
        val state = screenState.value as? ShareImportUiState.ReadyForConsent ?: return
        if (state.token != token) return
        val preview = state.preview
        val generation = state.generation
        if (!tokenStore.consume(token, preview.contentKey)) {
            AppLog.w(TAG, "Rejected stale or replayed share-import token")
            return
        }
        pendingToken = null
        screenState.value = ShareImportUiState.Processing

        lifecycleScope.launch {
            val enqueued = runCatching { enqueueForAnalysis(preview) }
                .onFailure { AppLog.e(TAG, "Failed to enqueue shared content", it) }
                .getOrDefault(false)

            // A1: a newer share has since arrived and is already being shown/handled by this
            // activity instance. Don't finish or forward on its behalf -- that would silently
            // discard the newer share the user hasn't acted on yet.
            if (generation != currentGeneration) {
                preview.thumbnail?.recycle()
                return@launch
            }

            if (!enqueued) {
                preview.thumbnail?.recycle()
                // R3: never auto-download and never drop the share silently. Hand off to
                // MainActivity, which prefills the shared content and, if the model is missing,
                // surfaces the normal P2/P4 download-consent flow so analysis can resume once the
                // model is ready. Uses the intent this preview was actually built from, not the
                // activity's possibly-newer intent, so the forwarded content always matches what
                // the user approved.
                forwardToMainActivity(preview.sourceIntent)
                return@launch
            }

            Toast.makeText(this@ShareImportActivity, "Analysis queued in background.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** R3: never initializes or downloads a model; only enqueues when one is already present. */
    private suspend fun enqueueForAnalysis(preview: SharePreview): Boolean {
        val selectedModel = modelDownloadManager.getSelectedModel()
        if (!withContext(Dispatchers.IO) { modelDownloadManager.isModelDownloaded(selectedModel) }) {
            AppLog.i(TAG, "Selected model is not downloaded; deferring to MainActivity")
            return false
        }

        return when (preview.type) {
            ShareContentType.TEXT -> {
                val text = preview.fullText ?: return false
                val workId = withContext(Dispatchers.IO) {
                    backgroundAnalysisScheduler.enqueueText(text, selectedModel)
                }
                AppLog.i(TAG, "Queued shared text after consent workId=$workId model=${selectedModel.shortName}")
                true
            }

            ShareContentType.IMAGE -> {
                if (!selectedModel.supportsImage) {
                    AppLog.i(TAG, "Selected model ${selectedModel.shortName} does not support shared images")
                    return false
                }
                val bitmap = preview.thumbnail ?: return false
                val workId = withContext(Dispatchers.IO) {
                    try {
                        backgroundAnalysisScheduler.enqueueImage(bitmap, selectedModel)
                    } finally {
                        bitmap.recycle()
                    }
                }
                AppLog.i(TAG, "Queued shared image after consent workId=$workId model=${selectedModel.shortName}")
                true
            }

            ShareContentType.AUDIO -> {
                if (!selectedModel.supportsAudio) {
                    AppLog.i(TAG, "Selected model ${selectedModel.shortName} does not support shared audio")
                    return false
                }
                val uri = preview.sourceUri ?: return false
                val workId = withContext(Dispatchers.IO) {
                    try {
                        backgroundAnalysisScheduler.enqueueAudioFromUri(
                            contentResolver,
                            uri,
                            selectedModel,
                            contentResolver.getType(uri)
                        )
                    } catch (e: FileImportHandler.FileTooLargeException) {
                        AppLog.w(TAG, "Shared audio exceeds size limit uri=$uri", e)
                        null
                    }
                }
                if (workId == null) return false
                AppLog.i(TAG, "Queued shared audio after consent workId=$workId model=${selectedModel.shortName}")
                true
            }
        }
    }

    /** R1: denial or dismissal leaves no side effects; the token is burned so it can't be reused. */
    private fun dismiss() {
        pendingToken?.let(tokenStore::invalidate)
        pendingToken = null
        finish()
    }

    private fun forwardToMainActivity(sourceIntent: Intent?) {
        startActivity(createMainActivityIntent(sourceIntent))
        finish()
    }

    private fun createMainActivityIntent(intent: Intent?): Intent {
        val permissionFlags = intent?.flags?.and(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        ) ?: 0

        return Intent(this, MainActivity::class.java).apply {
            action = intent?.action
            type = intent?.type
            if (intent?.extras != null) {
                putExtras(intent.extras!!)
            }
            clipData = intent?.clipData
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or permissionFlags)
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private inline fun <reified T : Parcelable> Intent.parcelableExtra(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }
}

private enum class ShareContentType { TEXT, IMAGE, AUDIO }

private data class SharePreview(
    val type: ShareContentType,
    val contentKey: String,
    val sizeBytes: Long?,
    val displayName: String? = null,
    val textPreview: String? = null,
    val textTruncated: Boolean = false,
    val fullText: String? = null,
    val thumbnail: Bitmap? = null,
    val sourceUri: Uri? = null,
    val sourceIntent: Intent
)

private sealed class ShareImportUiState {
    object Preparing : ShareImportUiState()
    data class ReadyForConsent(val preview: SharePreview, val generation: Int, val token: String) : ShareImportUiState()
    object Processing : ShareImportUiState()
}

@Composable
private fun ShareImportScreen(
    state: ShareImportUiState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                when (state) {
                    is ShareImportUiState.Preparing -> {
                        Text("Preparing preview...", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    is ShareImportUiState.ReadyForConsent -> {
                        SharePreviewContent(state.preview)
                        Spacer(Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { onConfirm(state.token) }) { Text("Analyze") }
                        }
                    }

                    is ShareImportUiState.Processing -> {
                        Text("Starting analysis...", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun SharePreviewContent(preview: SharePreview) {
    Text(
        text = when (preview.type) {
            ShareContentType.TEXT -> "Shared text"
            ShareContentType.IMAGE -> "Shared image"
            ShareContentType.AUDIO -> "Shared audio"
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    preview.sizeBytes?.let { bytes ->
        Spacer(Modifier.height(4.dp))
        Text(formatSizeLabel(bytes), style = MaterialTheme.typography.bodySmall)
    }
    preview.displayName?.let { name ->
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.bodySmall)
    }
    when (preview.type) {
        ShareContentType.TEXT -> {
            Spacer(Modifier.height(12.dp))
            Text(
                text = preview.textPreview.orEmpty() + if (preview.textTruncated) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 8
            )
        }

        ShareContentType.IMAGE -> {
            Spacer(Modifier.height(12.dp))
            val thumbnail = preview.thumbnail
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "Shared image preview",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                )
            } else {
                Text("Preview unavailable.", style = MaterialTheme.typography.bodySmall)
            }
        }

        ShareContentType.AUDIO -> Unit
    }
    Spacer(Modifier.height(12.dp))
    Text("This will be analyzed on-device to find calendar events.", style = MaterialTheme.typography.bodySmall)
}

private fun formatSizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}
