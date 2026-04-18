package com.calendaradd.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.calendarPermissions
import com.calendaradd.util.hasCalendarPermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val context = LocalContext.current
    val viewModel: DetailViewModel = viewModel(
        factory = AppViewModelFactory(
            calendarUseCase = calendarUseCase,
            preferencesManager = preferencesManager,
            eventId = eventId
        )
    )
    
    val event by viewModel.event.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            viewModel.syncToSystemCalendar()
        } else {
            permissionMessage = "Calendar access is required to sync this event to your device calendar."
        }
    }

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
                Text("Start: ${e.startTime.toDisplayDateTime()}", style = MaterialTheme.typography.bodyMedium)
                Text("End: ${e.endTime.toDisplayDateTime()}", style = MaterialTheme.typography.bodyMedium)
                Text("Location: ${e.location}", style = MaterialTheme.typography.bodyMedium)

                Button(
                    onClick = {
                        if (context.hasCalendarPermissions()) {
                            viewModel.syncToSystemCalendar()
                        } else {
                            calendarPermissionLauncher.launch(calendarPermissions)
                        }
                    },
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
        } ?: Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("Event not found", style = MaterialTheme.typography.bodyLarge)
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

    permissionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { permissionMessage = null },
            title = { Text("Permission Required") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { permissionMessage = null }) {
                    Text("OK")
                }
            }
        )
    }
}

private fun Long.toDisplayDateTime(): String {
    if (this <= 0L) return "No time"
    return SimpleDateFormat("EEE, MMM d yyyy HH:mm", Locale.getDefault()).format(Date(this))
}
