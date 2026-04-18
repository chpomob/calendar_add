package com.calendaradd.navigation

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight

/**
 * Navigation bar for bottom navigation with animated active indicator.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavigationBar(
    items: List<NavItem>,
    activeTabIndex: Int,
    onItemClicked: (index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 300,
                easing = StandardEasing.ease
            )
        ), label = ""
    )

    Row(
        modifier = modifier
            .height(56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .pointerInput(activeTabIndex) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { offset, _ ->
                        if (offset != 0f) {
                            onItemClicked(
                                if (offset > 0) activeTabIndex + 1 else activeTabIndex - 1
                            )
                        }
                    }
                )
            }
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                icon = {
                    val icon = if (index == activeTabIndex) item.iconOnSelected else item.iconOnUnselected
                    Icon(
                        imageVector = icon,
                        contentDescription = item.title,
                        modifier = Modifier
                            .graphicsLayer {
                                if (index == activeTabIndex) {
                                    scaleX = scale
                                    scaleY = scale
                                    translationY = -4f
                                }
                            }
                            .padding(8.dp)
                    )
                },
                label = {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (index == activeTabIndex) FontWeight.Bold else FontWeight.Normal
                    )
                },
                selected = index == activeTabIndex,
                onClick = { onItemClicked(index) },
                colors = NavigationBarDefaults.colors()
            )
        }
    }
}

data class NavItem(
    val title: String,
    val iconOnSelected: Int,
    val iconOnUnselected: Int
)
