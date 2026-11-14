package com.openfolio.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class OpenFolioColors(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val content: Color,
    val gainGlyph: Color,
    val lossGlyph: Color,
)

val LightOpenFolioColors = OpenFolioColors(
    background = Color(0xFFFAFAF7),
    surface = Color(0xFFFFFFFF),
    primary = Color(0xFF0F6E5C),
    content = Color(0xFF1A1D1F),
    gainGlyph = Color(0xFF1F7A4D),
    lossGlyph = Color(0xFFA93226),
)

val DarkOpenFolioColors = OpenFolioColors(
    background = Color(0xFF0E1113),
    surface = Color(0xFF171B1E),
    primary = Color(0xFF2A9C86),
    content = Color(0xFFE6E8E6),
    gainGlyph = Color(0xFF1F7A4D),
    lossGlyph = Color(0xFFA93226),
)

val LocalOpenFolioColors = staticCompositionLocalOf { LightOpenFolioColors }
