package com.calendaradd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(
    navController: androidx.navigation.NavController,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & data") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = {
                        Text(
                            "Calendar Add is designed to keep event extraction on your device after the model download.",
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    supportingContent = {
                        Text(
                            "This screen is a user-facing privacy summary. The release draft policy in the repository must still be finalized with real contact details before store publication."
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Lock, contentDescription = null)
                    }
                )
            }

            PolicySection(
                title = "What the app can access",
                body = listOf(
                    "Text you paste or share into the app",
                    "Images you capture, select, or share",
                    "Audio you share into the app",
                    "Your device calendar if you enable sync",
                    "Notifications for background processing updates",
                    "Network access only to download AI model files"
                )
            )

            PolicySection(
                title = "How data is used",
                body = listOf(
                    "Extract event details from text, images, and audio",
                    "Store created events locally in the app database",
                    "Optionally write created events into your system calendar",
                    "Download and keep the selected AI model on the device"
                )
            )

            PolicySection(
                title = "What stays on device",
                body = listOf(
                    "Event extraction input is intended to be processed locally after model download",
                    "Created events are stored in local app storage",
                    "Temporary analysis files may be created in app cache during background jobs"
                )
            )

            PolicySection(
                title = "What is not currently in the app",
                body = listOf(
                    "No account system",
                    "No advertising SDK",
                    "No analytics SDK",
                    "No crash reporting SDK",
                    "No Calendar Add cloud backend in this codebase"
                )
            )

            PolicySection(
                title = "Important note",
                body = listOf(
                    "This in-app summary does not replace the final public privacy policy required for store release.",
                    "Before publication, replace the draft contact information in the repository privacy policy with a real support or privacy contact."
                )
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PolicySection(
    title: String,
    body: List<String>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            body.forEach { line ->
                Text(
                    text = "\u2022 $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    }
}
