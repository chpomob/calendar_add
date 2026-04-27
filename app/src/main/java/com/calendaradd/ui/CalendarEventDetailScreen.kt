package com.calendaradd.ui

import android.graphics.BitmapFactory
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.Event
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.calendarPermissions
import com.calendaradd.util.hasCalendarPermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(e.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Start: ${e.startTime.toDisplayDateTime()}", style = MaterialTheme.typography.bodyMedium)
                Text("End: ${e.endTime.toDisplayDateTime()}", style = MaterialTheme.typography.bodyMedium)

                if (e.description.isNotBlank()) {
                    Text("Description: ${e.description}", style = MaterialTheme.typography.bodyMedium)
                }

                if (e.location.isNotBlank()) {
                    Text("Location: ${e.location}", style = MaterialTheme.typography.bodyMedium)
                }

                SourceAttachmentSection(e)

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

@Composable
private fun SourceAttachmentSection(event: Event) {
    if (event.sourceAttachmentPath.isBlank()) return
    val context = LocalContext.current
    val sourceFile = remember(event.sourceAttachmentPath) { File(event.sourceAttachmentPath) }
    if (!sourceFile.exists()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Created from", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                event.sourceAttachmentName.ifBlank { sourceFile.name },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                event.sourceAttachmentMimeType.ifBlank { event.sourceType },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (event.sourceAttachmentMimeType.startsWith("image/")) {
                val bitmap = remember(event.sourceAttachmentPath) {
                    BitmapFactory.decodeFile(event.sourceAttachmentPath)
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Original image source",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        sourceFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, event.sourceAttachmentMimeType.ifBlank { "*/*" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "Open original source"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open original source")
            }
        }
    }
}

private fun Long.toDisplayDateTime(): String {
    if (this <= 0L) return "No time"
    return SimpleDateFormat("EEE, MMM d yyyy HH:mm", Locale.getDefault()).format(Date(this))
}
