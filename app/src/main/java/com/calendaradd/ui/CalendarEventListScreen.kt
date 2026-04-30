package com.calendaradd.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.Event
import com.calendaradd.navigation.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen displaying list of all calendar events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEventListScreen(
    navController: NavController,
    calendarUseCase: CalendarUseCase,
    modifier: Modifier = Modifier
) {
    val events by calendarUseCase.getAllEvents().collectAsState(initial = emptyList())
    val sortedEvents = events.sortedBy { event ->
        if (event.startTime > 0L) event.startTime else Long.MAX_VALUE
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Event library")
                        Text(
                            "${events.size} saved ${if (events.size == 1) "event" else "events"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
                )
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    LibraryHeader(eventCount = events.size)
                }

                if (sortedEvents.isEmpty()) {
                    item {
                        EmptyLibraryCard()
                    }
                } else {
                    items(sortedEvents, key = { it.id }) { event ->
                        EventListItem(
                            event = event,
                            onClick = {
                                navController.navigate(
                                    "${Screen.EventDetail.route}/${event.id}"
                                )
                            }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(eventCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(34.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Text("Your captured plans", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = if (eventCount == 0) {
                    "Nothing saved yet. Capture a flyer, paste a message, or hold voice from the home screen."
                } else {
                    "Every extracted event lands here with its source trail, so you can check what the model created before syncing."
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyLibraryCard() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.EventNote,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(34.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = "Your library is empty",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Use Capture to turn a flyer, screenshot, voice note, or copied message into the first event.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EventListItem(
    event: Event,
    onClick: () -> Unit
) {
    val emphasis by animateFloatAsState(
        targetValue = if (event.isFavorite) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "eventEmphasis"
    )

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .scale(emphasis)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DateBadge(startTime = event.startTime)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title.ifEmpty { "Untitled event" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (event.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = event.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SourcePill(event.sourceType)
                    Text(
                        text = event.startTime.toDisplayDateTime(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open event",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DateBadge(startTime: Long) {
    val month = if (startTime > 0L) {
        SimpleDateFormat("MMM", Locale.getDefault()).format(Date(startTime))
    } else {
        "---"
    }
    val day = if (startTime > 0L) {
        SimpleDateFormat("d", Locale.getDefault()).format(Date(startTime))
    } else {
        "?"
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.size(width = 62.dp, height = 70.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(month.uppercase(), style = MaterialTheme.typography.labelSmall)
            Text(day, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SourcePill(sourceType: String) {
    val normalized = sourceType.lowercase(Locale.ROOT)
    val icon = when (normalized) {
        "image" -> Icons.Default.Image
        "audio" -> Icons.Default.Mic
        "text" -> Icons.AutoMirrored.Filled.Notes
        else -> Icons.Default.Star
    }
    val label = when (normalized) {
        "image" -> "image"
        "audio" -> "audio"
        "text" -> "text"
        else -> sourceType.ifBlank { "manual" }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun Long.toDisplayDateTime(): String {
    if (this <= 0L) return "No time"
    return SimpleDateFormat("EEE, MMM d yyyy HH:mm", Locale.getDefault()).format(Date(this))
}
