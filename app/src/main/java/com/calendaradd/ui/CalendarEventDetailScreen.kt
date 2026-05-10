package com.calendaradd.ui

import android.graphics.BitmapFactory
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val editStatus by viewModel.editStatus.collectAsState()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var editDraft by remember { mutableStateOf(EventEditDraft()) }

    LaunchedEffect(event?.id) {
        event?.let { editDraft = DetailViewModel.draftFrom(it) }
    }

    LaunchedEffect(editStatus) {
        if (editStatus is EditStatus.Success) {
            isEditing = false
        }
    }

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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (isEditing) "Edit event" else "Event receipt")
                        Text(
                            if (isEditing) "Save changes before calendar sync" else "Check the details before syncing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    event?.let { currentEvent ->
                        if (isEditing) {
                            IconButton(
                                onClick = {
                                    editDraft = DetailViewModel.draftFrom(currentEvent)
                                    isEditing = false
                                    viewModel.resetEditStatus()
                                },
                                enabled = editStatus !is EditStatus.Saving
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel edit")
                            }
                            IconButton(
                                onClick = { viewModel.saveEdits(editDraft) },
                                enabled = editStatus !is EditStatus.Saving
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save changes")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    editDraft = DetailViewModel.draftFrom(currentEvent)
                                    viewModel.resetEditStatus()
                                    isEditing = true
                                }
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit event")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
                )
            )
        }
    ) { padding ->
        event?.let { e ->
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.26f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
                    .padding(padding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (isEditing) {
                        EventEditCard(
                            draft = editDraft,
                            onDraftChange = { editDraft = it },
                            isSaving = editStatus is EditStatus.Saving
                        )
                    } else {
                        EventHeroCard(e)
                        EventFactsCard(e)
                        SourceAttachmentSection(e)

                        Button(
                            onClick = {
                                if (context.hasCalendarPermissions()) {
                                    viewModel.syncToSystemCalendar()
                                } else {
                                    calendarPermissionLauncher.launch(calendarPermissions)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = syncStatus !is SyncStatus.Syncing,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (syncStatus) {
                                    is SyncStatus.Syncing -> "Syncing..."
                                    is SyncStatus.Success -> "Synced to calendar"
                                    else -> if (e.systemCalendarEventId != null) {
                                        "Update system calendar"
                                    } else {
                                        "Add to system calendar"
                                    }
                                }
                            )
                        }
                    }
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

    when (val status = editStatus) {
        is EditStatus.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEditStatus() },
                title = { Text("Could not save") },
                text = { Text(status.message) },
                confirmButton = { TextButton(onClick = { viewModel.resetEditStatus() }) { Text("OK") } }
            )
        }
        is EditStatus.Success -> {
            status.warning?.let { warning ->
                AlertDialog(
                    onDismissRequest = { viewModel.resetEditStatus() },
                    title = { Text("Event saved") },
                    text = { Text(warning) },
                    confirmButton = { TextButton(onClick = { viewModel.resetEditStatus() }) { Text("OK") } }
                )
            }
        }
        else -> Unit
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
private fun EventEditCard(
    draft: EventEditDraft,
    onDraftChange: (EventEditDraft) -> Unit,
    isSaving: Boolean
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onDraftChange(draft.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                singleLine = true,
                label = { Text("Title") },
                leadingIcon = { Icon(Icons.Default.EventAvailable, contentDescription = null) }
            )
            OutlinedTextField(
                value = draft.startTime,
                onValueChange = { onDraftChange(draft.copy(startTime = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                singleLine = true,
                label = { Text("Starts") },
                supportingText = { Text("YYYY-MM-DD HH:mm") },
                leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) }
            )
            OutlinedTextField(
                value = draft.endTime,
                onValueChange = { onDraftChange(draft.copy(endTime = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                singleLine = true,
                label = { Text("Ends") },
                supportingText = { Text("YYYY-MM-DD HH:mm") },
                leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) }
            )
            OutlinedTextField(
                value = draft.location,
                onValueChange = { onDraftChange(draft.copy(location = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                singleLine = true,
                label = { Text("Place") },
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) }
            )
            OutlinedTextField(
                value = draft.attendees,
                onValueChange = { onDraftChange(draft.copy(attendees = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                label = { Text("People") },
                minLines = 1,
                maxLines = 3,
                leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) }
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = { onDraftChange(draft.copy(description = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 6,
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null) }
            )
            if (isSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EventHeroCard(event: Event) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(36.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Default.EventAvailable,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Text(event.sourceType.ifBlank { "manual" }.uppercase(), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = event.title.ifBlank { "Untitled event" },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = event.startTime.toDisplayDateTime(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
            )
        }
    }
}

@Composable
private fun EventFactsCard(event: Event) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailRow(Icons.Default.Schedule, "Starts", event.startTime.toDisplayDateTime())
            if (event.endTime > event.startTime) {
                DetailRow(Icons.Default.Flag, "Ends", event.endTime.toDisplayDateTime())
            }
            if (event.location.isNotBlank()) {
                DetailRow(Icons.Default.Place, "Place", event.location)
            }
            if (event.attendees.isNotBlank()) {
                DetailRow(Icons.Default.Group, "People", event.attendees)
            }
            if (event.description.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                Text("Notes", style = MaterialTheme.typography.labelLarge)
                Text(
                    event.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SourceAttachmentSection(event: Event) {
    if (event.sourceAttachmentPath.isBlank()) return
    val context = LocalContext.current
    val sourceFile = remember(event.sourceAttachmentPath) { File(event.sourceAttachmentPath) }
    if (!sourceFile.exists()) return

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Original source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Attachment, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        event.sourceAttachmentName.ifBlank { sourceFile.name },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        event.sourceAttachmentMimeType.ifBlank { event.sourceType },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(24.dp)
                            )
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
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open original source")
            }
        }
    }
}

private fun Long.toDisplayDateTime(): String {
    if (this <= 0L) return "No time"
    return SimpleDateFormat("EEE, MMM d yyyy HH:mm", Locale.getDefault()).format(Date(this))
}
