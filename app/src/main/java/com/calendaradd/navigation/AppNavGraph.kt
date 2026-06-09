package com.calendaradd.navigation

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.calendaradd.service.*
import com.calendaradd.ui.*
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.util.AppLog
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.ApkInstaller
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.SharedAudioContent

/**
 * App navigation graph defining all routes and navigation logic.
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    onImportEvent: suspend (String, String) -> Unit,
    calendarUseCase: CalendarUseCase,
    gemmaLlmService: GemmaLlmService,
    modelDownloadManager: ModelDownloadManager,
    updateCheckerService: UpdateCheckerService,
    apkDownloadManager: ApkDownloadManager,
    apkInstaller: ApkInstaller,
    backgroundAnalysisScheduler: BackgroundAnalysisScheduler,
    preferencesManager: PreferencesManager,
    onResetSharedContent: () -> Unit,
    fileImportHandler: FileImportHandler = FileImportHandler,
    startDestination: String = Screen.Home.route,
    sharedText: String? = null,
    sharedImage: Bitmap? = null,
    sharedAudio: SharedAudioContent? = null,
    openRoute: String? = null,
    onResetOpenRoute: () -> Unit = {},
    debugFailureTitle: String? = null,
    debugFailureBody: String? = null,
    onResetDebugFailure: () -> Unit = {}
) {
    LaunchedEffect(openRoute) {
        if (!openRoute.isNullOrBlank()) {
            // Accept all routes that the app and BackgroundAnalysisWorker emit:
            // home, eventlist, settings, privacy, and eventdetail/<positive long>.
            val validPattern = Regex("""^(home|eventlist|settings|privacy|eventdetail/\d+)$""")
            if (openRoute.matches(validPattern)) {
                navController.navigate(openRoute) {
                    launchSingleTop = true
                }
            } else {
                AppLog.w("AppNavGraph", "Rejected invalid openRoute: $openRoute")
            }
            onResetOpenRoute()
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = AppViewModelFactory(
                    calendarUseCase = calendarUseCase,
                    gemmaLlmService = gemmaLlmService,
                    modelDownloadManager = modelDownloadManager,
                    backgroundAnalysisScheduler = backgroundAnalysisScheduler
                )
            )
            
            CalendarHomeScreen(
                navController = navController,
                viewModel = homeViewModel,
                fileImportHandler = fileImportHandler,
                sharedText = sharedText,
                sharedImage = sharedImage,
                sharedAudio = sharedAudio,
                onResetSharedContent = onResetSharedContent,
                debugFailureTitle = debugFailureTitle,
                debugFailureBody = debugFailureBody,
                onResetDebugFailure = onResetDebugFailure
            )
        }

        composable(Screen.EventList.route) {
            CalendarEventListScreen(
                navController = navController,
                calendarUseCase = calendarUseCase
            )
        }

        composable(
            route = Screen.EventDetail.route + "/{eventId}"
        ) { backStackEntry ->
            // Guard both nullity and non-numeric input. A malformed route like
            // "eventdetail/abc" or a missing argument used to crash with
            // NumberFormatException; we now log and skip the composition.
            val eventId = backStackEntry.arguments?.getString("eventId")?.toLongOrNull()
            if (eventId == null) {
                AppLog.w(
                    "AppNavGraph",
                    "Rejected EventDetail route with invalid eventId=${backStackEntry.arguments?.getString("eventId")}"
                )
                return@composable
            }
            CalendarEventDetailScreen(
                eventId = eventId,
                navController = navController,
                calendarUseCase = calendarUseCase,
                preferencesManager = preferencesManager
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = AppViewModelFactory(
                    calendarUseCase = calendarUseCase,
                    preferencesManager = preferencesManager,
                    updateCheckerService = updateCheckerService,
                    apkDownloadManager = apkDownloadManager,
                    apkInstaller = apkInstaller
                )
            )
            
            CalendarSettingsScreen(
                navController = navController,
                viewModel = settingsViewModel
            )
        }

        composable(Screen.Privacy.route) {
            PrivacyPolicyScreen(navController = navController)
        }
    }
}

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object EventList : Screen("eventlist")
    object EventDetail : Screen("eventdetail")
    object Settings : Screen("settings")
    object Privacy : Screen("privacy")
}
