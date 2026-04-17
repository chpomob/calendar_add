package com.calendaradd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Main home screen for the calendar app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarHomeScreen(
    useCase: com.calendaradd.usecase.CalendarUseCase,
    modifier: Modifier = Modifier
) {
    var selectedInput by remember { mutableStateOf("text") }
    var inputValue by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar Add AI") },
                actions = {
                    IconButton(onClick = { /* TODO: Settings */ }) {
                        Text("⚙️")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Input type selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(3) { index ->
                    val types = listOf("text", "audio", "image")
                    val typeLabel = when(index) {
                        0 -> "Text"
                        1 -> "Audio"
                        2 -> "Image"
                        else -> ""
                    }
                    Surface(
                        tonalElevation = if (selectedInput == types[index]) 4.dp else 0.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        TextButton(
                            onClick = { selectedInput = types[index] }
                        ) {
                            Text(typeLabel, fontWeight = if (selectedInput == types[index]) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }

            // Input field
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        label = { Text("Enter event description or paste text...") },
                        placeholder = { Text("Describe your event, or paste notes about a meeting...") },
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = {
                        isProcessing = true
                        errorMessage = null
                    }) {
                        Text("Create Event")
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // TODO: Event list

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
