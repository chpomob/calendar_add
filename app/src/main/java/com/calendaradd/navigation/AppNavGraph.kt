package com.calendaradd.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavGraph
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.calendaradd.ui.CalendarHomeScreen
import com.calendaradd.ui.CalendarEventListScreen
import com.calendaradd.ui.CalendarEventDetailScreen
import com.calendaradd.ui.CalendarSettingsScreen
import com.calendaradd.ui.Screen
import com.calendaradd.util.FileImportHandler

/**
 * App navigation graph defining all routes and navigation logic.
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    onImportEvent: suspend (String, String) -> Unit,
    linkPreviewService: com.calendaradd.util.LinkPreviewService,
    fileImportHandler: FileImportHandler = FileImportHandler,
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Main navigation container
        val mainGraph = navigation(
            startDestination = Screen.Home.route
        ) {
            composable(Screen.Home.route) {
                CalendarHomeScreen(
                    navController = navController,
                    onImportEvent = onImportEvent,
                    fileImportHandler = fileImportHandler,
                    linkPreviewService = linkPreviewService
                )
            }

            composable(Screen.EventList.route) {
                CalendarEventListScreen(
                    navController = navController,
                    onImportEvent = onImportEvent
                )
            }

            composable(
                route = Screen.EventDetail.route + "{eventId}"
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

        // Add main graph to root
        mainGraph
    }
}

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object EventList : Screen("eventlist")
    object EventDetail : Screen("eventdetail/{eventId}")
    object Settings : Screen("settings")
}
