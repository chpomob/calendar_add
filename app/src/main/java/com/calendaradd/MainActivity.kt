package com.calendaradd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.calendaradd.navigation.AppNavGraph
import com.calendaradd.ui.CalendarHomeScreen
import com.calendaradd.ui.theme.CalendarAddTheme
import com.calendaradd.usecase.Event
import com.calendaradd.usecase.UserPreferences

class MainActivity : ComponentActivity() {

    private val preferences by lazy { UserPreferences() }

    @Composable
    override fun Route() {
        val events by remember { mutableStateOf(emptyList<Event>()) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNavGraph(
                navController = rememberNavController()
            ) { navController ->
                // Pass events and preferences to navigation
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
