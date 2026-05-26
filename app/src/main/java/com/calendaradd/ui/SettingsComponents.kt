package com.calendaradd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calendaradd.service.LiteRtModelConfig

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

@Composable
internal fun UpdateCheckSection(
    currentVersion: String,
    updateState: UpdateCheckState,
    onCheck: () -> Unit,
    onInstall: () -> Unit
) {
    val isBusy = updateState is UpdateCheckState.Checking || updateState is UpdateCheckState.Downloading
    ListItem(
        headlineContent = { Text("App update") },
        supportingContent = {
            Column {
                Text("Current: $currentVersion")
                Spacer(Modifier.height(4.dp))
                when (updateState) {
                    is UpdateCheckState.Idle -> {
                        updateState.message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    UpdateCheckState.Checking -> {
                        Text(
                            "Checking GitHub Releases...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateCheckState.Available -> {
                        Text(
                            "${updateState.updateInfo.latestVersion} is available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is UpdateCheckState.Downloading -> {
                        Text(
                            "Downloading ${updateState.updateInfo.latestVersion}: ${updateState.progress}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { updateState.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is UpdateCheckState.Downloaded -> {
                        Text(
                            updateState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateCheckState.Error -> {
                        Text(
                            updateState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        trailingContent = {
            when (updateState) {
                is UpdateCheckState.Available -> {
                    Button(onClick = onInstall) {
                        Text("Download & Install")
                    }
                }
                is UpdateCheckState.Downloaded -> {
                    Button(onClick = onInstall) {
                        Text("Install")
                    }
                }
                else -> {
                    Button(
                        onClick = onCheck,
                        enabled = !isBusy
                    ) {
                        Text(if (updateState is UpdateCheckState.Error) "Retry" else "Check")
                    }
                }
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
