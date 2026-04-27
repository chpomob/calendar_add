package com.calendaradd.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.calendaradd.navigation.Screen
import com.calendaradd.service.LiteRtModelConfig
import com.calendaradd.util.calendarPermissions
import com.calendaradd.util.hasCalendarPermissions

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
    val context = LocalContext.current
    val availableModels by viewModel.availableModels.collectAsState()
    val availableCalendars by viewModel.availableCalendars.collectAsState()
    val isAutoAddEnabled by viewModel.isAutoAddEnabled.collectAsState()
    val isHeavyAnalysisEnabled by viewModel.isHeavyAnalysisEnabled.collectAsState()
    val isWebVerificationEnabled by viewModel.isWebVerificationEnabled.collectAsState()
    val webSearchProvider by viewModel.webSearchProvider.collectAsState()
    val braveSearchApiKey by viewModel.braveSearchApiKey.collectAsState()
    val isFailureJsonDebugEnabled by viewModel.isFailureJsonDebugEnabled.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val selectedCalendarId by viewModel.selectedCalendarId.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCalendarPermissions = remember { mutableStateOf(context.hasCalendarPermissions()) }

    var showCalendarDialog by remember { mutableStateOf(false) }
    var pendingEnableAutoAdd by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        hasCalendarPermissions.value = granted
        if (granted) {
            viewModel.refreshCalendars()
            if (pendingEnableAutoAdd) {
                viewModel.setAutoAdd(true)
            }
        } else {
            permissionMessage = "Calendar access is required to sync events and choose a target calendar."
        }
        pendingEnableAutoAdd = false
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCalendarPermissions.value = context.hasCalendarPermissions()
                viewModel.refreshCalendars()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                "AI Model",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Card {
                Column {
                    ListItem(
                        headlineContent = { Text("Chosen Model") },
                        supportingContent = {
                            Text(
                                availableModels.firstOrNull { it.id == selectedModelId }?.displayName
                                    ?: "Gemma 4 E2B"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.AutoAwesome, contentDescription = null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Text(
                        "Tap a model below to switch. The next analysis job will use the chosen model after it is downloaded.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        items(availableModels) { model ->
                            ModelOptionRow(
                                model = model,
                                selected = model.id == selectedModelId,
                                onSelect = { viewModel.selectModel(model.id) }
                            )
                        }
                    }
                }
            }

            Text(
                "System Calendar",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            // Calendar Integration Section
            Card {
                Column {
                    // Auto-add toggle
                    ListItem(
                        headlineContent = { Text("Auto-add to Calendar") },
                        supportingContent = {
                            Text(
                                if (hasCalendarPermissions.value) {
                                    "Automatically push new events to your system calendar"
                                } else {
                                    "Grant calendar permission to enable automatic sync"
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isAutoAddEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && !hasCalendarPermissions.value) {
                                        pendingEnableAutoAdd = true
                                        calendarPermissionLauncher.launch(calendarPermissions)
                                    } else {
                                        viewModel.setAutoAdd(enabled)
                                    }
                                }
                            )
                        }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    
                    // Calendar selector
                    val selectedCalendarName = availableCalendars.find { it.id == selectedCalendarId }?.name ?: "Primary Calendar"
                    ListItem(
                        headlineContent = { Text("Target Calendar") },
                        supportingContent = {
                            Text(
                                if (hasCalendarPermissions.value) selectedCalendarName
                                else "Grant calendar permission to choose a calendar"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.CalendarToday, contentDescription = null) },
                        modifier = Modifier.clickable {
                            if (hasCalendarPermissions.value) {
                                showCalendarDialog = true
                            } else {
                                calendarPermissionLauncher.launch(calendarPermissions)
                            }
                        }
                    )
                }
            }

            Text(
                "Analysis",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Card {
                Column {
                    ListItem(
                        headlineContent = { Text("Heavy analysis mode") },
                        supportingContent = {
                            Text(
                                "Uses extra extraction rounds for images and audio. Slower, but can improve difficult inputs."
                            )
                        },
                        leadingContent = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isHeavyAnalysisEnabled,
                                onCheckedChange = { viewModel.setHeavyAnalysisEnabled(it) }
                            )
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Experimental web lookup") },
                        supportingContent = {
                            Text(
                                "Experimental. When image OCR exposes event hints, optionally search public pages to confirm or improve the extracted event. Off by default. Brave Search API is more reliable; DuckDuckGo HTML is only a fallback and may hit anti-bot pages."
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isWebVerificationEnabled,
                                onCheckedChange = { viewModel.setWebVerificationEnabled(it) }
                            )
                        }
                    )
                    if (isWebVerificationEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        WebSearchProviderSettings(
                            provider = webSearchProvider,
                            braveApiKey = braveSearchApiKey,
                            onProviderChange = viewModel::setWebSearchProvider,
                            onBraveApiKeyChange = viewModel::setBraveSearchApiKey
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Show raw model JSON on failure") },
                        supportingContent = {
                            Text(
                                "When enabled, failed background analyses can open a debug dialog with the raw model response. Keep this off for normal use."
                            )
                        },
                        leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isFailureJsonDebugEnabled,
                                onCheckedChange = { viewModel.setFailureJsonDebugEnabled(it) }
                            )
                        }
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Privacy & data") },
                        supportingContent = {
                            Text("Review what the app accesses, stores, and keeps on device")
                        },
                        leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.clickable {
                            navController.navigate(Screen.Privacy.route)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("AI Model") },
                        supportingContent = {
                            Text(
                                availableModels.firstOrNull { it.id == selectedModelId }?.displayName
                                    ?: "Gemma 4 E2B (Local)"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.AutoAwesome, contentDescription = null) }
                    )
                }
            }
        }
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

    if (showCalendarDialog && hasCalendarPermissions.value) {
        AlertDialog(
            onDismissRequest = { showCalendarDialog = false },
            title = { Text("Select Calendar") },
            text = {
                if (availableCalendars.isEmpty()) {
                    Text("No device calendars are currently available.")
                } else {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebSearchProviderSettings(
    provider: String,
    braveApiKey: String,
    onProviderChange: (String) -> Unit,
    onBraveApiKeyChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val providerLabel = when (provider) {
        "brave" -> "Brave Search API"
        else -> "DuckDuckGo HTML fallback"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = providerLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Experimental search provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Brave Search API") },
                    onClick = {
                        onProviderChange("brave")
                        expanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("DuckDuckGo HTML fallback") },
                    onClick = {
                        onProviderChange("duckduckgo")
                        expanded = false
                    }
                )
            }
        }

        if (provider == "brave") {
            OutlinedTextField(
                value = braveApiKey,
                onValueChange = onBraveApiKeyChange,
                label = { Text("Brave Search API key") },
                supportingText = {
                    Text("Stored locally on this device. If empty or rejected, lookup falls back to DuckDuckGo HTML.")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ModelOptionRow(
    model: LiteRtModelConfig,
    selected: Boolean,
    onSelect: () -> Unit
) {
    ListItem(
        headlineContent = { Text(model.displayName) },
        supportingContent = {
            Text("${model.capabilitySummary} • ${model.sizeLabel}\n${model.description}")
        },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        trailingContent = {
            if (selected) {
                AssistChip(onClick = onSelect, label = { Text("Selected") })
            }
        },
        modifier = Modifier.clickable(onClick = onSelect)
            .padding(horizontal = 4.dp)
    )
}
