package com.calendaradd.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaradd.navigation.Screen
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreview
import com.calendaradd.util.LinkPreviewService

/**
 * Main home screen for the calendar app.
 * Automatically processes shared content if provided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarHomeScreen(
    navController: androidx.navigation.NavController,
    viewModel: HomeViewModel,
    linkPreviewService: LinkPreviewService,
    onResetSharedContent: () -> Unit,
    fileImportHandler: FileImportHandler = FileImportHandler,
    sharedText: String? = null,
    sharedImage: Bitmap? = null,
    sharedAudio: ByteArray? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isModelReady by viewModel.isModelReady.collectAsState()
    
    var inputValue by remember { mutableStateOf("") }

    // Automatically process shared content
    LaunchedEffect(sharedText) {
        sharedText?.let { 
            viewModel.processText(it)
            onResetSharedContent()
        }
    }
    LaunchedEffect(sharedImage) {
        sharedImage?.let { 
            viewModel.processImage(it)
            onResetSharedContent()
        }
    }
    LaunchedEffect(sharedAudio) {
        sharedAudio?.let { 
            viewModel.processAudio(it)
            onResetSharedContent()
        }
    }

    // Initialize model if not ready (using a placeholder path for now)
    LaunchedEffect(isModelReady) {
        if (!isModelReady) {
            viewModel.initializeModel("/data/local/tmp/gemma-4-e2b-it.litertlm")
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
                onClick = { navController.navigate(Screen.EventList.route) },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.List, contentDescription = "View Events")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Smart Event Creator",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Paste text, share an image, or record audio to create events instantly with local AI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Text Input Card
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = inputValue,
                            onValueChange = { inputValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Event details...") },
                            placeholder = { Text("e.g., Lunch with Maria at 1pm tomorrow at Bistro") }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.processText(inputValue) },
                            modifier = Modifier.align(Alignment.End),
                            enabled = isModelReady && inputValue.isNotBlank()
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Analyze")
                        }
                    }
                }

                // Quick Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionCard(
                        icon = Icons.Default.Mic,
                        label = "Voice",
                        onClick = { /* TODO: Implement live recording */ },
                        modifier = Modifier.weight(1f)
                    )
                    ActionCard(
                        icon = Icons.Default.Image,
                        label = "Image",
                        onClick = { /* TODO: Implement gallery picker */ },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Status Message
                if (!isModelReady) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Initializing Gemma 4 Engine...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Processing Overlay
            if (uiState is HomeUiState.Loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text((uiState as HomeUiState.Loading).message)
                        }
                    }
                }
            }

            // Success/Error Dialogs
            if (uiState is HomeUiState.Success) {
                AlertDialog(
                    onDismissRequest = { viewModel.resetState() },
                    title = { Text("Event Created!") },
                    text = { Text("Successfully created event: ${(uiState as HomeUiState.Success).eventTitle}") },
                    confirmButton = {
                        Button(onClick = { 
                            viewModel.resetState()
                            navController.navigate(Screen.EventList.route) 
                        }) {
                            Text("View List")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.resetState() }) {
                            Text("Dismiss")
                        }
                    }
                )
            }

            if (uiState is HomeUiState.Error) {
                AlertDialog(
                    onDismissRequest = { viewModel.resetState() },
                    title = { Text("Extraction Error") },
                    text = { Text((uiState as HomeUiState.Error).message) },
                    confirmButton = {
                        Button(onClick = { viewModel.resetState() }) {
                            Text("OK")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.height(80.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null)
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
