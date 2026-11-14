package com.openfolio.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.openfolio.R

private val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val OpenFolioTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.withInter(),
        displayMedium = base.displayMedium.withInter(),
        displaySmall = base.displaySmall.withInter(),
        headlineLarge = base.headlineLarge.withInter(),
        headlineMedium = base.headlineMedium.withInter(),
        headlineSmall = base.headlineSmall.withInter(),
        titleLarge = base.titleLarge.withInter(),
        titleMedium = base.titleMedium.withInter(),
        titleSmall = base.titleSmall.withInter(),
        bodyLarge = base.bodyLarge.withInter(),
        bodyMedium = base.bodyMedium.withInter(),
        bodySmall = base.bodySmall.withInter(),
        labelLarge = base.labelLarge.withInter(),
        labelMedium = base.labelMedium.withInter(),
        labelSmall = base.labelSmall.withInter(),
    )
}

private fun TextStyle.withInter(): TextStyle = copy(fontFamily = InterFontFamily)

@Composable
fun OpenFolioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    check(!dynamicColor) { "Dynamic color is disabled to preserve the OpenFolio design system." }
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
            typography = OpenFolioTypography,
            content = content,
        )
    }
}
