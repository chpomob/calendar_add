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
import com.calendaradd.usecase.UserPreferences

/**
 * Settings screen for app configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(
    navController: com.calendaradd.navigation.NavHostController,
    preferences: UserPreferences = UserPreferences(),
    modifier: Modifier = Modifier
) {
    var selectedSetting by remember { mutableStateOf("privacy") }
    var showAboutDialog by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Settings list
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Privacy settings
                    SettingItem(
                        title = "Privacy",
                        subtitle = "Control what data is stored locally",
                        icon = Icons.Default.Lock
                    ) {
                        SettingPrivacySection(preferences = preferences)
                    }

                    // About
                    SettingItem(
                        title = "About",
                        subtitle = "App version, license, contact",
                        icon = Icons.Default.Info
                    ) {
                        Column {
                            Text(
                                text = "Calendar Add AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Version 1.0",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "License: MIT",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showAboutDialog = true }
                    ) {
                        Text("Show About")
                    }

                    Dialog(
                        onDismissRequest = { showAboutDialog = false },
                        title = { Text("About") },
                        icon = { Icon(Icons.Default.Info, contentDescription = "About") },
                        text = {
                            Text(
                                text = """
                                Calendar Add AI helps you create calendar events using local AI processing.

                                All your data stays on your device.

                                © 2026 Calendar Add AI

                                License: MIT
                                """.trimIndent()
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = { showAboutDialog = false }) {
                                Text("OK")
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Data export button
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Export All Data",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { /* TODO: Export data */ }) {
                        Text("Export")
                    }
                }
            }
        }
    }
}

@Composable
fun SettingItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}
