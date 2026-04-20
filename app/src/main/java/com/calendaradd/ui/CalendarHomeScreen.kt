package com.calendaradd.ui

import android.graphics.Bitmap
import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.calendaradd.navigation.Screen
import com.calendaradd.util.AppLog
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.LinkPreviewService
import com.calendaradd.util.ModelImageLoader
import com.calendaradd.util.calendarPermissions
import com.calendaradd.util.hasCalendarPermissions
import java.io.File
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
    var pendingCameraImagePath by rememberSaveable { mutableStateOf<String?>(null) }

    fun clearPendingCameraFile() {
        pendingCameraImagePath?.let { path ->
            runCatching { File(path).takeIf { it.exists() }?.delete() }
        }
        pendingCameraImagePath = null
    }

    fun processImageUri(uri: Uri, source: String) {
        AppLog.i(tag, "$source selected uri=$uri")
        try {
            val bitmap = ModelImageLoader.loadForInference(context.contentResolver, uri)
            if (bitmap != null) {
                AppLog.i(tag, "$source decoded bitmap=${bitmap.width}x${bitmap.height}")
                viewModel.processImage(bitmap)
            } else {
                AppLog.w(tag, "$source failed to decode uri=$uri")
                scope.launch {
                    snackbarHostState.showSnackbar("Unable to load that image for analysis.")
                }
            }
        } catch (e: OutOfMemoryError) {
            AppLog.e(tag, "$source ran out of memory uri=$uri", e)
            scope.launch {
                snackbarHostState.showSnackbar("That image is too large to analyze safely.")
            }
        }
    }

    fun createCameraCaptureUri(): Uri {
        val imageDir = File(context.cacheDir, "captured-images").apply { mkdirs() }
        val imageFile = File.createTempFile("capture_", ".jpg", imageDir)
        pendingCameraImagePath = imageFile.absolutePath
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    }

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

    fun processAudioUri(uri: Uri, source: String) {
        AppLog.i(tag, "$source selected uri=$uri")
        try {
            val audioBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (audioBytes != null) {
                AppLog.i(tag, "$source loaded audio bytes=${audioBytes.size}")
                viewModel.processAudio(audioBytes)
            } else {
                AppLog.w(tag, "$source failed to read uri=$uri")
                scope.launch {
                    snackbarHostState.showSnackbar("Unable to read that audio file for analysis.")
                }
            }
        } catch (e: Exception) {
            AppLog.e(tag, "$source failed uri=$uri", e)
            scope.launch {
                snackbarHostState.showSnackbar("Unable to read that audio file for analysis.")
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            scope.launch {
                snackbarHostState.showSnackbar("Notification permission is recommended for background analysis updates.")
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            processImageUri(it, "Image picker")
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            processAudioUri(it, "Audio picker")
        }
    }

    val cameraCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { isSuccess ->
        val imagePath = pendingCameraImagePath
        if (isSuccess && imagePath != null) {
            processImageUri(Uri.fromFile(File(imagePath)), "Camera capture")
        } else if (!isSuccess) {
            AppLog.i(tag, "Camera capture cancelled")
        }
        clearPendingCameraFile()
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val captureUri = createCameraCaptureUri()
            cameraCaptureLauncher.launch(captureUri)
        } else {
            scope.launch { snackbarHostState.showSnackbar("Camera permission denied.") }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            clearPendingCameraFile()
        }
    }

    // Check permissions on launch
    LaunchedEffect(Unit) {
        if (!context.hasCalendarPermissions()) {
            calendarPermissionLauncher.launch(calendarPermissions)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Calendar Add")
                        Text(
                            "Offline capture for messy real-life plans",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
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
            ExtendedFloatingActionButton(
                onClick = { navController.navigate(Screen.EventList.route) },
                icon = {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                },
                text = { Text("Events") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(padding)
        ) {
            if (!isModelReady) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    HeroPanel(
                        title = "Make a calendar from the chaos.",
                        subtitle = "Run extraction on your phone, keep your schedule private, and let the heavy lifting happen in the background.",
                        modelLabel = selectedModel.shortName,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(20.dp))
                    HomeSectionCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(52.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Download ${selectedModel.shortName}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "${selectedModel.sizeLabel} • ${selectedModel.capabilitySummary}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "A one-time on-device model download is required before the app can analyze text, photos, or audio.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { viewModel.downloadModel() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            enabled = uiState !is HomeUiState.Loading,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                when {
                                    downloadProgress != null -> "Downloading ${selectedModel.shortName}"
                                    uiState is HomeUiState.Error -> "Retry Download"
                                    else -> "Download ${selectedModel.shortName}"
                                }
                            )
                        }

                        if (downloadProgress != null) {
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { (downloadProgress ?: 0) / 100f },
                                modifier = Modifier.fillMaxWidth().height(10.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${selectedModel.shortName} download progress: ${downloadProgress}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (sharedText != null || sharedImage != null || sharedAudio != null) {
                        Spacer(Modifier.height(16.dp))
                        NoticeCard("Shared content is waiting and will start as soon as the model is ready.")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    HeroPanel(
                        title = "Capture a flyer, a screenshot, or a thought.",
                        subtitle = "Calendar Add turns rough inputs into structured events and keeps long analysis running in the background.",
                        modelLabel = selectedModel.shortName,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState is HomeUiState.Queued) {
                        NoticeCard((uiState as HomeUiState.Queued).message)
                    }

                    HomeSectionCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Describe it",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Paste a message, a copied invitation, or loose notes. The model can split one input into multiple events.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Column {
                            OutlinedTextField(
                                value = inputValue,
                                onValueChange = { inputValue = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 320.dp),
                                label = { Text("Event details") },
                                placeholder = { Text("Lunch with Maria at 1pm tomorrow, then parent-teacher meeting at 6pm") },
                                maxLines = 10,
                                shape = RoundedCornerShape(20.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.processText(inputValue) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isModelReady && inputValue.isNotBlank(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Analyze with ${selectedModel.shortName}")
                            }
                        }
                    }

                    HomeSectionCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Quick capture",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Use the fastest input method for the moment, and let the app finish the job in the background.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ActionCard(
                                icon = Icons.Default.PhotoCamera,
                                label = "Camera",
                                caption = "Shoot a poster",
                                accent = MaterialTheme.colorScheme.tertiaryContainer,
                                onClick = {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ActionCard(
                                icon = Icons.Default.Image,
                                label = "Image",
                                caption = "Pick from files",
                                accent = MaterialTheme.colorScheme.primaryContainer,
                                onClick = {
                                    imagePickerLauncher.launch("image/*")
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ActionCard(
                                icon = Icons.Default.Mic,
                                label = if (selectedModel.supportsAudio) "Audio" else "Audio off",
                                caption = if (selectedModel.supportsAudio) "Pick a recording" else "Current model has no audio",
                                accent = MaterialTheme.colorScheme.secondaryContainer,
                                onClick = {
                                    if (selectedModel.supportsAudio) {
                                        audioPickerLauncher.launch("audio/*")
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("${selectedModel.displayName} does not support audio.")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ActionCard(
                                icon = Icons.AutoMirrored.Filled.List,
                                label = "Events",
                                caption = "Review imports",
                                accent = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = {
                                    navController.navigate(Screen.EventList.route)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HomeSectionCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Current model",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        ActionCard(
                            icon = Icons.Default.Tune,
                            label = selectedModel.displayName,
                            caption = selectedModel.capabilitySummary,
                            accent = MaterialTheme.colorScheme.primaryContainer,
                            onClick = {
                                navController.navigate(Screen.Settings.route)
                            },
                            modifier = Modifier.fillMaxWidth()
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

            if (uiState is HomeUiState.Queued) {
                AlertDialog(
                    onDismissRequest = { viewModel.resetState() },
                    title = { Text("Running In Background") },
                    text = { Text((uiState as HomeUiState.Queued).message) },
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
    icon: ImageVector,
    label: String,
    caption: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = accent,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HomeSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = content
        )
    }
}

@Composable
private fun HeroPanel(
    title: String,
    subtitle: String,
    modelLabel: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CapabilityChip(label = modelLabel)
                    CapabilityChip(label = "Background ready")
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
                )
            }
        }
    }
}

@Composable
private fun CapabilityChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun NoticeCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
