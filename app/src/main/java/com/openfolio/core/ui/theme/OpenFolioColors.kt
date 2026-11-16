package com.openfolio.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class OpenFolioColors(
    val background: Color,
    val surface: Color,
    val brand: Color,
    val brandSubtle: Color,
    val onAccent: Color,
    val content: Color,
    val muted: Color,
    val border: Color,
    val chartPalette: List<Color>,
    val gainGlyph: Color,
    val lossGlyph: Color,
) {
    val loss: Color get() = lossGlyph
}

val LightOpenFolioColors = OpenFolioColors(
    background = Color(0xFFFAFAF7),
    surface = Color(0xFFFFFFFF),
    brand = Color(0xFF0F6E5C),
    brandSubtle = Color(0xFFE0F0EC),
    onAccent = Color(0xFFFFFFFF),
    content = Color(0xFF1A1D1F),
    muted = Color(0xFF8B9094),
    border = Color(0xFFE8E6E1),
    chartPalette = listOf(
        Color(0xFF0F6E5C),
        Color(0xFF4C5AA8),
        Color(0xFFB8842A),
        Color(0xFF8B4A6F),
        Color(0xFF5A6A72),
    ),
    gainGlyph = Color(0xFF1F7A4D),
    lossGlyph = Color(0xFFA93226),
)

val DarkOpenFolioColors = OpenFolioColors(
    background = Color(0xFF0E1113),
    surface = Color(0xFF171B1E),
    brand = Color(0xFF2A9C86),
    brandSubtle = Color(0xFF203A35),
    onAccent = Color(0xFFFFFFFF),
    content = Color(0xFFE6E8E6),
    muted = Color(0xFF8B9094),
    border = Color(0xFF2A3033),
    chartPalette = listOf(
        Color(0xFF0F6E5C),
        Color(0xFF4C5AA8),
        Color(0xFFB8842A),
        Color(0xFF8B4A6F),
        Color(0xFF5A6A72),
    ),
    gainGlyph = Color(0xFF1F7A4D),
    lossGlyph = Color(0xFFA93226),
)

val LocalOpenFolioColors = staticCompositionLocalOf { LightOpenFolioColors }
