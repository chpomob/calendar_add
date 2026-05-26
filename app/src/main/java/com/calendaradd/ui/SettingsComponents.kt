package com.calendaradd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ListItem
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
