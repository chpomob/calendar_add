package com.calendaradd

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.calendaradd.navigation.AppNavGraph
import com.calendaradd.service.*
import com.calendaradd.ui.theme.CalendarAddTheme
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.Event
import com.calendaradd.usecase.EventDatabase
import com.calendaradd.usecase.UserPreferences
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreviewService

class MainActivity : ComponentActivity() {

    private val preferences by lazy { UserPreferences() }
    private lateinit var eventDatabase: EventDatabase
    private lateinit var llmEngine: LlmEngine
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var linkPreviewService: LinkPreviewService
    private val lifecycleScope by lazy { LifecycleScope(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize database
        eventDatabase = EventDatabase.getDatabase(this)

        // Initialize LLM engine
        llmEngine = LlmEngine(context = this)

        // Initialize services
        val textAnalysisService = TextAnalysisService(llmEngine)
        calendarUseCase = CalendarUseCase(
            textAnalysisService = textAnalysisService,
            eventDatabase = eventDatabase,
            userPreferences = preferences
        )
        linkPreviewService = LinkPreviewService(context = this)

        // Load model in background on first launch
        lifecycleScope.launch {
            llmEngine.loadModel(downloadRequired = true)
        }

        setContent {
            val navController = rememberNavController()

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AppNavGraph(
                    navController = navController,
                    onImportEvent = { input, sourceType ->
                        lifecycleScope.launch {
                            calendarUseCase.createEvent(
                                input = input,
                                sourceType = sourceType
                            )
                        }
                    },
                    linkPreviewService = linkPreviewService,
                    fileImportHandler = FileImportHandler
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release LLM model
        llmEngine.unloadModel()
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
