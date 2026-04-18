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
import com.calendaradd.navigation.Screen
import com.calendaradd.usecase.Event

/**
 * Screen displaying details of a single calendar event.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEventDetailScreen(
    eventId: Long,
    navController: com.calendaradd.navigation.NavHostController,
    event: Event? = null,
    modifier: Modifier = Modifier
) {
    var editMode by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var newStartTime by remember { mutableStateOf("") }
    var newEndTime by remember { mutableStateOf("") }
    var newLocation by remember { mutableStateOf("") }
    var newAttendees by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editMode) "Edit Event" else "Event Details") },
                actions = {
                    if (editMode) {
                        IconButton(onClick = {
                            navController.popBackStack()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    } else {
                        IconButton(onClick = { editMode = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
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
                // Event title
                OutlinedTextField(
                    value = if (editMode) newTitle else e.title,
                    onValueChange = { if (editMode) newTitle = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Description
                OutlinedTextField(
                    value = if (editMode) newDescription else e.description,
                    onValueChange = { if (editMode) newDescription = it },
                    label = { Text("Description") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Start time
                OutlinedTextField(
                    value = if (editMode) newStartTime else e.startTime,
                    onValueChange = { if (editMode) newStartTime = it },
                    label = { Text("Start Time") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // End time
                OutlinedTextField(
                    value = if (editMode) newEndTime else e.endTime,
                    onValueChange = { if (editMode) newEndTime = it },
                    label = { Text("End Time") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Location
                OutlinedTextField(
                    value = if (editMode) newLocation else e.location,
                    onValueChange = { if (editMode) newLocation = it },
                    label = { Text("Location") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Attendees
                OutlinedTextField(
                    value = if (editMode) newAttendees else e.attendees,
                    onValueChange = { if (editMode) newAttendees = it },
                    label = { Text("Attendees (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { editMode = false }
                    ) {
                        Text("Cancel")
                    }

                    if (!editMode) {
                        OutlinedButton(
                            onClick = { editMode = true }
                        ) {
                            Text("Edit")
                        }
                    } else {
                        Button(
                            onClick = { /* TODO: Save event */ },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Changes")
                        }
                    }
                }
            }
        } ?: run {
            // Show loading or error state
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Event not found", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("The event you're looking for doesn't exist.")
                }
            }
        }
    }
}
