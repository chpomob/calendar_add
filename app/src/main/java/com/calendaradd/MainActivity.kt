package com.calendaradd

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.EventDatabase
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreviewService
import java.io.InputStream

class MainActivity : ComponentActivity() {

    private lateinit var eventDatabase: EventDatabase
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var gemmaLlmService: GemmaLlmService
    private lateinit var systemCalendarService: SystemCalendarService

    // State to hold shared content for navigation
    private val sharedText = mutableStateOf<String?>(null)
    private val sharedImage = mutableStateOf<Bitmap?>(null)
    private val sharedAudio = mutableStateOf<ByteArray?>(null)

    fun resetSharedContent() {
        sharedText.value = null
        sharedImage.value = null
        sharedAudio.value = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Services
        eventDatabase = EventDatabase.getDatabase(this)
        gemmaLlmService = GemmaLlmService(this)
        systemCalendarService = SystemCalendarService(this)

        val textAnalysisService = TextAnalysisService(gemmaLlmService)
        calendarUseCase = CalendarUseCase(textAnalysisService, eventDatabase)

        handleIntent(intent)

        setContent {
            val navController = rememberNavController()

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AppNavGraph(
                    navController = navController,
                    onImportEvent = { input, sourceType ->
                        // Handled by ViewModels
                    },
                    linkPreviewService = LinkPreviewService(this),
                    calendarUseCase = calendarUseCase,
                    gemmaLlmService = gemmaLlmService,
                    onResetSharedContent = ::resetSharedContent,
                    fileImportHandler = FileImportHandler,
                    // Pass shared content to UI if needed
                    sharedText = sharedText.value,
                    sharedImage = sharedImage.value,
                    sharedAudio = sharedAudio.value
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            when {
                intent.type?.startsWith("text/") == true -> {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        sharedText.value = it
                    }
                }
                intent.type?.startsWith("image/") == true -> {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                        sharedImage.value = uriToBitmap(uri)
                    }
                }
                intent.type?.startsWith("audio/") == true -> {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                        sharedAudio.value = uriToBytes(uri)
                    }
                }
            }
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun uriToBytes(uri: Uri): ByteArray? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            inputStream?.readBytes()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gemmaLlmService.close()
    }
}
