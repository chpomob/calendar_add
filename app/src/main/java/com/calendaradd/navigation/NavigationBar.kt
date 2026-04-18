package com.calendaradd.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Simple bottom navigation bar.
 */
@Composable
fun NavigationBar(
    items: List<NavItem>,
    activeTabIndex: Int,
    onItemClicked: (index: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                icon = {
                    val icon = if (index == activeTabIndex) item.iconOnSelected else item.iconOnUnselected
                    Icon(
                        imageVector = icon,
                        contentDescription = item.title
                    )
                },
                label = { Text(item.title) },
                selected = index == activeTabIndex,
                onClick = { onItemClicked(index) }
            )
        }
    }
}

data class NavItem(
    val title: String,
    val iconOnSelected: ImageVector,
    val iconOnUnselected: ImageVector
)
