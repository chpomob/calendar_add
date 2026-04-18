package com.calendaradd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Settings screen for app configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(
    navController: androidx.navigation.NavController,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val availableCalendars by viewModel.availableCalendars.collectAsState()
    val isAutoAddEnabled by viewModel.isAutoAddEnabled.collectAsState()
    val selectedCalendarId by viewModel.selectedCalendarId.collectAsState()
    
    var showCalendarDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Calendar Integration Section
            Text(
                "System Calendar",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            Card {
                Column {
                    // Auto-add toggle
                    ListItem(
                        headlineContent = { Text("Auto-add to Calendar") },
                        supportingContent = { Text("Automatically push new events to your system calendar") },
                        leadingContent = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isAutoAddEnabled,
                                onCheckedChange = { viewModel.setAutoAdd(it) }
                            )
                        }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    
                    // Calendar selector
                    val selectedCalendarName = availableCalendars.find { it.id == selectedCalendarId }?.name ?: "Primary Calendar"
                    ListItem(
                        headlineContent = { Text("Target Calendar") },
                        supportingContent = { Text(selectedCalendarName) },
                        leadingContent = { Icon(Icons.Default.CalendarToday, contentDescription = null) },
                        modifier = Modifier.clickable { showCalendarDialog = true }
                    )
                }
            }

            // About Section
            Text(
                "App Info",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            Card {
                Column {
                    ListItem(
                        headlineContent = { Text("Version") },
                        supportingContent = { Text("1.0.0 (LiteRT-LM Edition)") },
                        leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                    )
                    ListItem(
                        headlineContent = { Text("AI Model") },
                        supportingContent = { Text("Gemma 4 E2B (Local)") },
                        leadingContent = { Icon(Icons.Default.AutoAwesome, contentDescription = null) }
                    )
                }
            }
        }
    }

    if (showCalendarDialog) {
        AlertDialog(
            onDismissRequest = { showCalendarDialog = false },
            title = { Text("Select Calendar") },
            text = {
                LazyColumn {
                    items(availableCalendars) { calendar ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectCalendar(calendar.id, calendar.name)
                                    showCalendarDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = calendar.id == selectedCalendarId,
                                onClick = {
                                    viewModel.selectCalendar(calendar.id, calendar.name)
                                    showCalendarDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                                Text(calendar.accountName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCalendarDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
