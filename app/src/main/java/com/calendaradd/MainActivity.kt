package com.calendaradd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.calendaradd.navigation.AppNavGraph
import com.calendaradd.service.*
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.EventDatabase
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreviewService

class MainActivity : ComponentActivity() {

    private lateinit var eventDatabase: EventDatabase
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var gemmaLlmService: GemmaLlmService
    private lateinit var speechToTextService: SpeechToTextService
    private lateinit var ocrService: OcrService
    private lateinit var systemCalendarService: SystemCalendarService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Services
        eventDatabase = EventDatabase.getDatabase(this)
        gemmaLlmService = GemmaLlmService()
        speechToTextService = SpeechToTextService()
        ocrService = OcrService()
        systemCalendarService = SystemCalendarService(this)

        val textAnalysisService = TextAnalysisService(gemmaLlmService, ocrService)
        calendarUseCase = CalendarUseCase(textAnalysisService, eventDatabase)

        setContent {
            val navController = rememberNavController()

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AppNavGraph(
                    navController = navController,
                    onImportEvent = { input, sourceType ->
                        // Handled by ViewModels (to be implemented)
                    },
                    linkPreviewService = LinkPreviewService(this),
                    fileImportHandler = FileImportHandler
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechToTextService.close()
    }
}
