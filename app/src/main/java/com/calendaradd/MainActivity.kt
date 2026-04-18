package com.calendaradd

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.calendaradd.navigation.AppNavGraph
import com.calendaradd.service.*
import com.calendaradd.ui.theme.CalendarAddTheme
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.Event
import com.calendaradd.usecase.EventDatabase
import com.calendaradd.usecase.UserPreferences
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreviewService
import com.calendaradd.util.UriResolver
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MainActivity : ComponentActivity() {

    private val preferences by lazy { UserPreferences() }
    private lateinit var eventDatabase: EventDatabase
    private lateinit var llmEngine: LlmEngine
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var linkPreviewService: LinkPreviewService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize database
        eventDatabase = EventDatabase.getDatabase(this)

        // Initialize LLM engine (model will load asynchronously)
        llmEngine = LlmEngine(context = this)

        // Initialize services
        val textAnalysisService = TextAnalysisService(llmEngine)
        calendarUseCase = CalendarUseCase(
            textAnalysisService = textAnalysisService,
            eventDatabase = eventDatabase,
            userPreferences = preferences
        )
        linkPreviewService = LinkPreviewService(context = this)

        setContent {
            val navController = rememberNavController()

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AppNavGraph(
                    navController = navController,
                    onImportEvent = { input, sourceType ->
                        calendarUseCase.createEvent(
                            input = input,
                            sourceType = sourceType
                        )
                    },
                    linkPreviewService = linkPreviewService,
                    fileImportHandler = FileImportHandler
                )
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Pre-load LLM model in background
        llmEngine.loadModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release LLM model
        llmEngine.unloadModel()
    }

    companion object {
        // Handle intent from Files app or share sheet
        fun handleIntent(intent: Intent?) {
            intent?.let {
                // Extract URI from intent
                val uri = it.data
                val action = it.action

                // Handle file import
                if (action == Intent.ACTION_VIEW && uri != null) {
                    val result = FileImportHandler.handleFileResult(
                        resultCode = it.resultCode,
                        data = it,
                        uriResolver = UriResolver
                    )
                    // TODO: Pass to app for event creation
                }
            }
        }
    }
}

@Composable
fun GreetingPreview() {
    CalendarAddTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            androidx.compose.material3.Text(text = "Calendar Add AI - Event Creator")
        }
    }
}
