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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaradd.navigation.Screen
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreview
import com.calendaradd.util.LinkPreviewService

/**
 * Main home screen for the calendar app.
 * Handles model download and AI extraction.
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
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    
    var inputValue by remember { mutableStateOf("") }

    // Automatically process shared content
    LaunchedEffect(sharedText) {
        sharedText?.let { 
            if (isModelReady) {
                viewModel.processText(it)
                onResetSharedContent()
            }
        }
    }
    LaunchedEffect(sharedImage) {
        sharedImage?.let { 
            if (isModelReady) {
                viewModel.processImage(it)
                onResetSharedContent()
            }
        }
    }
    LaunchedEffect(sharedAudio) {
        sharedAudio?.let { 
            if (isModelReady) {
                viewModel.processAudio(it)
                onResetSharedContent()
            }
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
            if (uiState is HomeUiState.ModelMissing) {
                // Download Model Screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "AI Model Required",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "To extract events locally and protect your privacy, a one-time download of the Gemma 4 model (~1.5GB) is required.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { viewModel.downloadModel() },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Download Model")
                    }
                }
            } else {
                // Main Content
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

                    // Text Input Card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = inputValue,
                                onValueChange = { inputValue = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Event details...") },
                                placeholder = { Text("e.g., Lunch with Maria at 1pm tomorrow") }
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

                    // Shared Content Notification
                    if (!isModelReady && (sharedText != null || sharedImage != null || sharedAudio != null)) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                "Waiting for AI model to process shared content...",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Processing/Download Overlay
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
                            Spacer(modifier = Modifier.height(16.dp))
                            Text((uiState as HomeUiState.Loading).message)
                            
                            downloadProgress?.let {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = it / 100f,
                                    modifier = Modifier.fillMaxWidth().height(8.dp)
                                )
                            }
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
                    title = { Text("Error") },
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
