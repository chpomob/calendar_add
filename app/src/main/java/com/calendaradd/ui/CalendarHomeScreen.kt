package com.calendaradd.ui

import android.graphics.Bitmap
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calendaradd.navigation.Screen
import com.calendaradd.util.AppLog
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreviewService
import com.calendaradd.util.ModelImageLoader
import com.calendaradd.util.calendarPermissions
import com.calendaradd.util.hasCalendarPermissions
import kotlinx.coroutines.launch

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
    val tag = "CalendarHomeScreen"
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val isModelReady by viewModel.isModelReady.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    
    var inputValue by remember { mutableStateOf("") }

    // Permission Launchers
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            scope.launch {
                snackbarHostState.showSnackbar("Calendar permissions are needed to auto-sync events.")
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch { snackbarHostState.showSnackbar("Voice capture ready!") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Audio permission denied.") }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            AppLog.i(tag, "Image picker selected uri=$it")
            try {
                val bitmap = ModelImageLoader.loadForInference(context.contentResolver, it)
                if (bitmap != null) {
                    AppLog.i(tag, "Image picker decoded bitmap=${bitmap.width}x${bitmap.height}")
                    viewModel.processImage(bitmap)
                } else {
                    AppLog.w(tag, "Image picker failed to decode uri=$it")
                    scope.launch {
                        snackbarHostState.showSnackbar("Unable to load that image for analysis.")
                    }
                }
            } catch (e: OutOfMemoryError) {
                AppLog.e(tag, "Image picker ran out of memory uri=$it", e)
                scope.launch {
                    snackbarHostState.showSnackbar("That image is too large to analyze safely.")
                }
            }
        }
    }

    // Check permissions on launch
    LaunchedEffect(Unit) {
        if (!context.hasCalendarPermissions()) {
            calendarPermissionLauncher.launch(calendarPermissions)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshModelState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Automatically process shared content
    LaunchedEffect(sharedText, isModelReady) {
        sharedText?.let { 
            if (isModelReady) {
                viewModel.processText(it)
                onResetSharedContent()
            }
        }
    }
    LaunchedEffect(sharedImage, isModelReady) {
        sharedImage?.let { 
            if (isModelReady) {
                viewModel.processImage(it)
                onResetSharedContent()
            }
        }
    }
    LaunchedEffect(sharedAudio, isModelReady) {
        sharedAudio?.let { 
            if (isModelReady) {
                viewModel.processAudio(it)
                onResetSharedContent()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Calendar Add (Recovery)") },
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
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "View Events")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!isModelReady) {
                // Download Model Screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
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
                        "To extract events locally and protect your privacy, a one-time download of ${selectedModel.shortName} (${selectedModel.sizeLabel}) is required.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { viewModel.downloadModel() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = uiState !is HomeUiState.Loading
                    ) {
                        Text(
                            when {
                                downloadProgress != null -> "Downloading ${selectedModel.shortName}"
                                uiState is HomeUiState.Error -> "Retry Download"
                                else -> "Download ${selectedModel.shortName} (${selectedModel.sizeLabel})"
                            }
                        )
                    }

                    if (downloadProgress != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "${selectedModel.shortName} download progress: ${downloadProgress}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (sharedText != null || sharedImage != null || sharedAudio != null) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                "Shared content is queued and will be processed after the model is ready.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            } else {
                // Main Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Smart Event Creator",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Model: ${selectedModel.displayName} • ${selectedModel.capabilitySummary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Text Input Card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = inputValue,
                                onValueChange = { inputValue = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp),
                                label = { Text("Event details...") },
                                placeholder = { Text("e.g., Lunch with Maria at 1pm tomorrow") },
                                maxLines = 10
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.processText(inputValue) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isModelReady && inputValue.isNotBlank()
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Analyze with ${selectedModel.shortName}")
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
                            label = if (selectedModel.supportsAudio) "Voice" else "Voice off",
                            onClick = {
                                if (selectedModel.supportsAudio) {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("${selectedModel.displayName} does not support audio.")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ActionCard(
                            icon = Icons.Default.Image,
                            label = "Image",
                            onClick = {
                                imagePickerLauncher.launch("image/*")
                            },
                            modifier = Modifier.weight(1f)
                        )
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
                                    progress = { it / 100f },
                                    modifier = Modifier.fillMaxWidth().height(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Success/Error Dialogs
            if (uiState is HomeUiState.Success) {
                val successState = uiState as HomeUiState.Success
                AlertDialog(
                    onDismissRequest = { viewModel.resetState() },
                    title = {
                        Text(
                            if (successState.createdCount == 1) "Event Created!"
                            else "Events Created!"
                        )
                    },
                    text = {
                        Text(
                            if (successState.createdCount == 1) {
                                "Successfully created event: ${successState.firstEventTitle}"
                            } else {
                                "Successfully created ${successState.createdCount} events. First event: ${successState.firstEventTitle}"
                            }
                        )
                    },
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
