package com.openfolio.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun OpenFolioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val openFolioColors = if (darkTheme) DarkOpenFolioColors else LightOpenFolioColors
    val materialColors = if (darkTheme) {
        darkColorScheme(
            primary = openFolioColors.primary,
            onPrimary = openFolioColors.surface,
            background = openFolioColors.background,
            onBackground = openFolioColors.content,
            surface = openFolioColors.surface,
            onSurface = openFolioColors.content,
        )
    } else {
        lightColorScheme(
            primary = openFolioColors.primary,
            onPrimary = openFolioColors.surface,
            background = openFolioColors.background,
            onBackground = openFolioColors.content,
            surface = openFolioColors.surface,
            onSurface = openFolioColors.content,
        )
    }

    CompositionLocalProvider(LocalOpenFolioColors provides openFolioColors) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content,
        )
    }
}
