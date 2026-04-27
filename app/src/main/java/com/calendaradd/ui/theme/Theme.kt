package com.calendaradd.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = HarborGold,
    onPrimary = HarborNight,
    primaryContainer = HarborTeal,
    onPrimaryContainer = HarborPaper,
    secondary = HarborCloud,
    onSecondary = HarborNight,
    secondaryContainer = HarborDeep,
    onSecondaryContainer = HarborPaper,
    tertiary = HarborCoral,
    onTertiary = HarborNight,
    tertiaryContainer = HarborSlate,
    onTertiaryContainer = HarborPaper,
    background = HarborNight,
    onBackground = HarborPaper,
    surface = Color(0xFF13212B),
    onSurface = HarborPaper,
    surfaceVariant = Color(0xFF223441),
    onSurfaceVariant = Color(0xFFD2DEDC),
    outline = Color(0xFF70828D)
)

private val LightColorScheme = lightColorScheme(
    primary = HarborInk,
    onPrimary = HarborPaper,
    primaryContainer = Color(0xFFD9E7E4),
    onPrimaryContainer = HarborInk,
    secondary = HarborTeal,
    onSecondary = HarborPaper,
    secondaryContainer = HarborMist,
    onSecondaryContainer = HarborDeep,
    tertiary = HarborCoral,
    onTertiary = HarborPaper,
    tertiaryContainer = Color(0xFFF7D7CA),
    onTertiaryContainer = Color(0xFF5B2917),
    background = HarborPaper,
    onBackground = HarborInk,
    surface = Color(0xFFFFFBF5),
    onSurface = HarborInk,
    surfaceVariant = Color(0xFFE4E0D7),
    onSurfaceVariant = HarborSlate,
    outline = Color(0xFF8B8C85)
)

@Composable
fun CalendarAddTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        content()
    }
}
