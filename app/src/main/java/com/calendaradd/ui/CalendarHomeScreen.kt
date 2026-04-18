package com.calendaradd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreview
import com.calendaradd.util.UriResolver
import com.calendaradd.navigation.Screen
import com.calendaradd.usecase.Event

/**
 * Main home screen for the calendar app with floating action button for event creation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarHomeScreen(
    navController: androidx.navigation.NavController,
    onImportEvent: suspend (String, String) -> Unit,
    linkPreviewService: com.calendaradd.util.LinkPreviewService,
    fileImportHandler: FileImportHandler = FileImportHandler,
    modifier: Modifier = Modifier
) {
    var selectedInput by remember { mutableStateOf("text") }
    var inputValue by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var eventCreated by remember { mutableStateOf<Event?>(null) }
    var isLinkPreview by remember { mutableStateOf(false) }
    var linkPreviewTitle by remember { mutableStateOf<String?>(null) }
    var linkPreviewDescription by remember { mutableStateOf<String?>(null) }
    var linkPreviewUrl by remember { mutableStateOf<String?>(null) }

    // Handle file import result
    fun handleFileImport(uri: androidx.content.Uri, type: String) {
        fileImportHandler.handleFileResult(resultCode = 0, data = null, uriResolver = UriResolver)
            ?.let { result ->
                if (result is FileImportHandler.FileImportResult.Success) {
                    // For now, just use text content
                    val text = "Imported from file: ${result.uri}"
                    onImportEvent.invoke(text, type)
                }
            }
    }

    // Process text input
    fun onTextSubmit() {
        isProcessing = true
        errorMessage = null
        inputValue = ""

        // Extract event from text
        val extractedEvent = onImportEvent.invoke(inputValue, "text")
        if (extractedEvent != null) {
            eventCreated = extractedEvent
        } else {
            errorMessage = "Failed to create event. Please try again."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar Add AI") },
                actions = {
                    IconButton(onClick = {
                        navController.navigate(Screen.Settings.route)
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* TODO: Open event creation dialog */ },
                containerColors = FloatingActionButtonDefaults.containerColors(
                    default = MaterialTheme.colorScheme.primaryContainer,
                    pressed = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
                )
            ) {
                Icon(Icons.Default.Event, contentDescription = "Create event")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Input type selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val inputTypes = listOf(
                    "text" to Icons.Default.Event to 120,
                    "audio" to Icons.Default.Mic to 0,
                    "image" to Icons.Default.Image to 0,
                    "link" to Icons.Default.Link to 0
                )

                inputTypes.forEach { (type, icon, available) ->
                    if (available == 0) return@forEach
                    val isSelected = selectedInput == type
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (isSelected) 4.dp else 0.dp
                        )
                    ) {
                        Surface(
                            tonalElevation = if (isSelected) 2.dp else 0.dp
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = type,
                                    modifier = Modifier.padding(8.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = type.capitalize(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Input field
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (selectedInput) {
                        "text" -> {
                            TextField(
                                value = inputValue,
                                onValueChange = { inputValue = it },
                                label = { Text("Enter event description or paste text...") },
                                placeholder = { Text("Describe your event, or paste notes about a meeting...") },
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            TextButton(onClick = {
                                onTextSubmit()
                            }) {
                                Text("Create Event")
                            }
                        }
                        "link" -> {
                            // Link preview input
                            TextField(
                                value = inputValue,
                                onValueChange = { inputValue = it },
                                label = { Text("Paste URL for link preview") },
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            if (isLinkPreview) {
                                LinkPreviewCard(
                                    preview = LinkPreview(
                                        url = linkPreviewUrl ?: "",
                                        title = linkPreviewTitle ?: "",
                                        description = linkPreviewDescription ?: "",
                                        imageUrl = null,
                                        faviconUrl = null
                                    ),
                                    onClick = {
                                        // Create event from link
                                    }
                                )
                            }
                        }
                        else -> {}
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (eventCreated != null) {
                        EventCard(event = eventCreated!!)
                    }
                }
            }

            // Event list header
            Text(
                text = "Your Events",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (eventCreated != null) {
                EventCard(event = eventCreated!!)
            } else if (eventCreated != null) {
                Text(
                    text = "No events yet. Create your first event above!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Card displaying a single event.
 */
@Composable
fun EventCard(
    event: Event
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "Event icon",
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title.ifEmpty { "Untitled Event" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (event.description.isNotEmpty()) {
                    Text(
                        text = event.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.startTime.ifEmpty { "No time" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Card displaying link preview.
 */
@Composable
fun LinkPreviewCard(
    preview: LinkPreview,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Favicon or image
            preview.imageUrl?.let { image ->
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp)
                )
            } ?: Icon(
                imageVector = Icons.Default.Http,
                contentDescription = "Link icon",
                modifier = Modifier.size(50.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preview.title.ifEmpty { preview.url },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.TextOverflow.Ellipsis
                )
                preview.description?.let { desc ->
                    Text(
                        text = desc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.TextOverflow.Ellipsis
                    )
                }
            }

            TextButton(onClick = onClick) {
                Text("Import")
            }
        }
    }
}
