package com.calendaradd

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.calendaradd.navigation.AppNavGraph
import com.calendaradd.service.*
import com.calendaradd.ui.theme.CalendarAddTheme
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.EventDatabase
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreviewService
import com.calendaradd.util.ModelImageLoader
import com.calendaradd.util.AppLog

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_OPEN_ROUTE = "open_route"
        const val EXTRA_DEBUG_FAILURE_TITLE = "debug_failure_title"
        const val EXTRA_DEBUG_FAILURE_BODY = "debug_failure_body"
    }

    private lateinit var eventDatabase: EventDatabase
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var gemmaLlmService: GemmaLlmService
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var backgroundAnalysisScheduler: BackgroundAnalysisScheduler
    private lateinit var systemCalendarService: SystemCalendarService
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var ocrService: OcrService
    private lateinit var webVerificationService: WebVerificationService

    // State to hold shared content for navigation
    private val sharedText = mutableStateOf<String?>(null)
    private val sharedImage = mutableStateOf<Bitmap?>(null)
    private val sharedAudio = mutableStateOf<ByteArray?>(null)
    private val pendingOpenRoute = mutableStateOf<String?>(null)
    private val pendingDebugFailureTitle = mutableStateOf<String?>(null)
    private val pendingDebugFailureBody = mutableStateOf<String?>(null)

    fun resetSharedContent() {
        sharedText.value = null
        sharedImage.value = null
        sharedAudio.value = null
    }

    fun resetPendingOpenRoute() {
        pendingOpenRoute.value = null
    }

    fun resetPendingDebugFailure() {
        pendingDebugFailureTitle.value = null
        pendingDebugFailureBody.value = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Services
        eventDatabase = EventDatabase.getDatabase(this)
        gemmaLlmService = GemmaLlmService(this)
        preferencesManager = PreferencesManager(this)
        modelDownloadManager = ModelDownloadManager(this, preferencesManager)
        backgroundAnalysisScheduler = BackgroundAnalysisScheduler(this)
        systemCalendarService = SystemCalendarService(this)
        ocrService = OcrService()
        webVerificationService = WebVerificationService(webSearchClient = PreferencesWebSearchClient(preferencesManager))

        val textAnalysisService = TextAnalysisService(
            gemmaLlmService,
            preferencesManager,
            ocrService,
            webVerificationService
        )
        calendarUseCase = CalendarUseCase(
            textAnalysisService = textAnalysisService,
            eventDatabase = eventDatabase,
            systemCalendarService = systemCalendarService,
            preferencesManager = preferencesManager
        )

        handleIntent(intent)

        setContent {
            val navController = rememberNavController()

            CalendarAddTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavGraph(
                        navController = navController,
                        onImportEvent = { input, sourceType ->
                            // Handled by ViewModels
                        },
                        linkPreviewService = LinkPreviewService(),
                        calendarUseCase = calendarUseCase,
                        gemmaLlmService = gemmaLlmService,
                        modelDownloadManager = modelDownloadManager,
                        backgroundAnalysisScheduler = backgroundAnalysisScheduler,
                        preferencesManager = preferencesManager,
                        onResetSharedContent = ::resetSharedContent,
                        fileImportHandler = FileImportHandler,
                        // Pass shared content to UI if needed
                        sharedText = sharedText.value,
                        sharedImage = sharedImage.value,
                        sharedAudio = sharedAudio.value,
                        openRoute = pendingOpenRoute.value,
                        onResetOpenRoute = ::resetPendingOpenRoute,
                        debugFailureTitle = pendingDebugFailureTitle.value,
                        debugFailureBody = pendingDebugFailureBody.value,
                        onResetDebugFailure = ::resetPendingDebugFailure
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        AppLog.i(TAG, "handleIntent action=${intent?.action} type=${intent?.type}")
        intent?.getStringExtra(EXTRA_OPEN_ROUTE)?.let { route ->
            AppLog.i(TAG, "Received open route $route")
            pendingOpenRoute.value = route
        }
        val debugTitle = intent?.getStringExtra(EXTRA_DEBUG_FAILURE_TITLE)
        val debugBody = intent?.getStringExtra(EXTRA_DEBUG_FAILURE_BODY)
        if (!debugBody.isNullOrBlank()) {
            AppLog.i(TAG, "Received debug failure payload chars=${debugBody.length}")
            pendingDebugFailureTitle.value = debugTitle ?: "Failure Debug JSON"
            pendingDebugFailureBody.value = debugBody
            if (pendingOpenRoute.value.isNullOrBlank()) {
                pendingOpenRoute.value = "home"
            }
        }
        if (intent?.action == Intent.ACTION_SEND) {
            resetSharedContent()
            when {
                intent.type?.startsWith("text/") == true -> {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        AppLog.i(TAG, "Received shared text chars=${it.length}")
                        sharedText.value = it
                    }
                }
                intent.type?.startsWith("image/") == true -> {
                    intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                        AppLog.i(TAG, "Received shared image uri=$uri")
                        sharedImage.value = uriToBitmap(uri)
                    }
                }
                intent.type?.startsWith("audio/") == true -> {
                    intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                        AppLog.i(TAG, "Received shared audio uri=$uri")
                        sharedAudio.value = uriToBytes(uri)
                    }
                }
            }
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            ModelImageLoader.loadForInference(contentResolver, uri)?.also { bitmap ->
                AppLog.i(TAG, "Decoded shared image uri=$uri size=${bitmap.width}x${bitmap.height}")
            } ?: run {
                AppLog.w(TAG, "Unable to decode shared image uri=$uri")
                null
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to decode shared image uri=$uri", e)
            null
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "Out of memory decoding shared image uri=$uri", e)
            null
        }
    }

    private fun uriToBytes(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }?.also { bytes ->
                AppLog.i(TAG, "Decoded shared audio uri=$uri bytes=${bytes.size}")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to decode shared audio uri=$uri", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gemmaLlmService.close()
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
