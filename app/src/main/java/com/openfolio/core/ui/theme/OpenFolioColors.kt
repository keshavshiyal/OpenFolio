package com.openfolio.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class OpenFolioColors(
    val background: Color,
    val surface: Color,
    val brand: Color,
    val onAccent: Color,
    val content: Color,
    val muted: Color,
    val border: Color,
    val gainGlyph: Color,
    val lossGlyph: Color,
)

val LightOpenFolioColors = OpenFolioColors(
    background = Color(0xFFFAFAF7),
    surface = Color(0xFFFFFFFF),
    brand = Color(0xFF0F6E5C),
    onAccent = Color(0xFFFFFFFF),
    content = Color(0xFF1A1D1F),
    muted = Color(0xFF8B9094),
    border = Color(0xFFE8E6E1),
    gainGlyph = Color(0xFF1F7A4D),
    lossGlyph = Color(0xFFA93226),
)

val DarkOpenFolioColors = OpenFolioColors(
    background = Color(0xFF0E1113),
    surface = Color(0xFF171B1E),
    brand = Color(0xFF2A9C86),
    onAccent = Color(0xFFFFFFFF),
    content = Color(0xFFE6E8E6),
    muted = Color(0xFF8B9094),
    border = Color(0xFF2A3033),
    gainGlyph = Color(0xFF1F7A4D),
    lossGlyph = Color(0xFFA93226),
)

val LocalOpenFolioColors = staticCompositionLocalOf { LightOpenFolioColors }
