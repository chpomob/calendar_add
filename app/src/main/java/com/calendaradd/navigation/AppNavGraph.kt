package com.calendaradd.navigation

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.calendaradd.service.*
import com.calendaradd.ui.*
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreviewService

/**
 * App navigation graph defining all routes and navigation logic.
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    onImportEvent: suspend (String, String) -> Unit,
    linkPreviewService: LinkPreviewService,
    calendarUseCase: CalendarUseCase,
    gemmaLlmService: GemmaLlmService,
    modelDownloadManager: ModelDownloadManager,
    onResetSharedContent: () -> Unit,
    fileImportHandler: FileImportHandler = FileImportHandler,
    startDestination: String = Screen.Home.route,
    sharedText: String? = null,
    sharedImage: Bitmap? = null,
    sharedAudio: ByteArray? = null
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModelFactory(calendarUseCase, gemmaLlmService, modelDownloadManager)
            )
            
            CalendarHomeScreen(
                navController = navController,
                viewModel = homeViewModel,
                linkPreviewService = linkPreviewService,
                fileImportHandler = fileImportHandler,
                sharedText = sharedText,
                sharedImage = sharedImage,
                sharedAudio = sharedAudio,
                onResetSharedContent = onResetSharedContent
            )
        }

        composable(Screen.EventList.route) {
            CalendarEventListScreen(
                navController = navController,
                onImportEvent = onImportEvent
            )
        }

        composable(
            route = Screen.EventDetail.route + "/{eventId}"
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getString("eventId") ?: return@composable
            CalendarEventDetailScreen(
                eventId = eventId.toLong(),
                navController = navController
            )
        }

        composable(Screen.Settings.route) {
            CalendarSettingsScreen(
                navController = navController
            )
        }
    }
}

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object EventList : Screen("eventlist")
    object EventDetail : Screen("eventdetail")
    object Settings : Screen("settings")
}
