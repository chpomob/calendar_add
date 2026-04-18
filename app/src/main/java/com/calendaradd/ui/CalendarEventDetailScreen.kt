package com.calendaradd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calendaradd.navigation.Screen
import com.calendaradd.service.GemmaLlmService
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.PreferencesManager

/**
 * Screen displaying details of a single calendar event.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEventDetailScreen(
    eventId: Long,
    navController: androidx.navigation.NavController,
    calendarUseCase: CalendarUseCase,
    preferencesManager: PreferencesManager,
    modifier: Modifier = Modifier
) {
    val viewModel: DetailViewModel = viewModel(
        factory = AppViewModelFactory(calendarUseCase = calendarUseCase, eventId = eventId)
    )
    
    val event by viewModel.event.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Details") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        event?.let { e ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(e.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Description: ${e.description}", style = MaterialTheme.typography.bodyMedium)
                Text("Start: ${e.startTime}", style = MaterialTheme.typography.bodyMedium)
                Text("End: ${e.endTime}", style = MaterialTheme.typography.bodyMedium)
                Text("Location: ${e.location}", style = MaterialTheme.typography.bodyMedium)

                Button(
                    onClick = { viewModel.syncToSystemCalendar() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = syncStatus !is SyncStatus.Syncing
                ) {
                    Text(when (syncStatus) {
                        is SyncStatus.Syncing -> "Syncing..."
                        is SyncStatus.Success -> "Synced to Calendar"
                        else -> "Sync to System Calendar"
                    })
                }
            }
        }
    }

    if (syncStatus is SyncStatus.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.resetSyncStatus() },
            title = { Text("Sync Error") },
            text = { Text((syncStatus as SyncStatus.Error).message) },
            confirmButton = { TextButton(onClick = { viewModel.resetSyncStatus() }) { Text("OK") } }
        )
    }
}
