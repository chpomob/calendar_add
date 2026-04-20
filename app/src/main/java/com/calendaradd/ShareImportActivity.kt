package com.calendaradd

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.calendaradd.service.BackgroundAnalysisScheduler
import com.calendaradd.service.ModelDownloadManager
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.AppLog
import com.calendaradd.util.ModelImageLoader
import kotlinx.coroutines.launch

class ShareImportActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ShareImportActivity"
    }

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var backgroundAnalysisScheduler: BackgroundAnalysisScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(this)
        modelDownloadManager = ModelDownloadManager(this, preferencesManager)
        backgroundAnalysisScheduler = BackgroundAnalysisScheduler(this)
        processIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIncomingIntent(intent)
    }

    private fun processIncomingIntent(intent: Intent?) {
        lifecycleScope.launch {
            val handledInBackground = runCatching { tryEnqueueSharedContent(intent) }
                .onFailure { AppLog.e(TAG, "Failed to process shared content directly", it) }
                .getOrDefault(false)

            if (handledInBackground) {
                Toast.makeText(
                    this@ShareImportActivity,
                    "Analysis queued in background.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }

            AppLog.i(TAG, "Falling back to MainActivity for shared intent")
            startActivity(createMainActivityIntent(intent))
            finish()
        }
    }

    private fun tryEnqueueSharedContent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false

        val selectedModel = modelDownloadManager.getSelectedModel()
        if (!modelDownloadManager.isModelDownloaded(selectedModel)) {
            AppLog.i(TAG, "Selected model is not downloaded; opening app UI instead")
            return false
        }

        return when {
            intent.type?.startsWith("text/") == true -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return false
                val workId = backgroundAnalysisScheduler.enqueueText(text, selectedModel)
                AppLog.i(TAG, "Queued shared text directly workId=$workId model=${selectedModel.shortName}")
                true
            }

            intent.type?.startsWith("image/") == true -> {
                if (!selectedModel.supportsImage) {
                    AppLog.i(TAG, "Selected model ${selectedModel.shortName} does not support shared images")
                    return false
                }
                val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return false
                val bitmap = ModelImageLoader.loadForInference(contentResolver, uri) ?: return false
                try {
                    val workId = backgroundAnalysisScheduler.enqueueImage(bitmap, selectedModel)
                    AppLog.i(TAG, "Queued shared image directly workId=$workId model=${selectedModel.shortName}")
                } finally {
                    bitmap.recycle()
                }
                true
            }

            intent.type?.startsWith("audio/") == true -> {
                if (!selectedModel.supportsAudio) {
                    AppLog.i(TAG, "Selected model ${selectedModel.shortName} does not support shared audio")
                    return false
                }
                val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return false
                val audioBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
                val workId = backgroundAnalysisScheduler.enqueueAudio(audioBytes, selectedModel)
                AppLog.i(TAG, "Queued shared audio directly workId=$workId model=${selectedModel.shortName}")
                true
            }

            else -> false
        }
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

    private inline fun <reified T : Parcelable> Intent.parcelableExtra(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }
}
