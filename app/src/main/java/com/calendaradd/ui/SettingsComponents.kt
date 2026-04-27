package com.calendaradd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.calendaradd.service.LiteRtModelConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebSearchProviderSettings(
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
internal fun ModelOptionRow(
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
        modifier = Modifier
            .clickable(onClick = onSelect)
            .padding(horizontal = 4.dp)
    )
}
