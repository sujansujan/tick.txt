package com.ticktxt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeMode {
    SYSTEM,
    DRACULA_DARK,
    ALUCARD_LIGHT
}

enum class FontMode(val label: String) {
    MONOSPACE("monospace"),
    SYSTEM_SANS("system font (sans)"),
    SYSTEM_SERIF("system serif");

    fun toComposeFontFamily(): FontFamily = when (this) {
        MONOSPACE -> FontFamily.Monospace
        SYSTEM_SANS -> FontFamily.Default
        SYSTEM_SERIF -> FontFamily.Serif
    }
}

@Immutable
data class TickSemanticPalette(
    val isDark: Boolean,
    val bg: Color,
    val bgElevated: Color,
    val fg: Color,
    val fgMuted: Color,
    val border: Color,
    val priorityA: Color,
    val priorityB: Color,
    val priorityC: Color,
    val project: Color,
    val contextTag: Color,
    val reminder: Color,
    val noteLink: Color
)

val DraculaPalette = TickSemanticPalette(
    isDark = true,
    bg = Color(0xFF282A36),
    bgElevated = Color(0xFF1E1F29),
    fg = Color(0xFFF8F8F2),
    fgMuted = Color(0xFF9499B8),
    border = Color(0xFF44475A),
    priorityA = Color(0xFFFF6E6E),
    priorityB = Color(0xFFFFB86C),
    priorityC = Color(0xFFF1FA8C),
    project = Color(0xFFBD93F9),
    contextTag = Color(0xFF8BE9FD),
    reminder = Color(0xFF50FA7B),
    noteLink = Color(0xFFFF79C6)
)

val AlucardPalette = TickSemanticPalette(
    isDark = false,
    bg = Color(0xFFFFFBEB),
    bgElevated = Color(0xFFF3EFE0),
    fg = Color(0xFF1F1F1F),
    fgMuted = Color(0xFF595649),
    border = Color(0xFFD8D2C0),
    priorityA = Color(0xFFCB3A2A),
    priorityB = Color(0xFFA34D14),
    priorityC = Color(0xFF846E15),
    project = Color(0xFF644AC9),
    contextTag = Color(0xFF036A96),
    reminder = Color(0xFF14710A),
    noteLink = Color(0xFFA3144D)
)

val LocalTickPalette = staticCompositionLocalOf { DraculaPalette }
val LocalTickFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

fun buildTickTypography(fontFamily: FontFamily) = Typography(
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

@Composable
fun TickTxtTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    fontMode: FontMode = FontMode.MONOSPACE,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DRACULA_DARK -> true
        ThemeMode.ALUCARD_LIGHT -> false
    }
    val palette = if (useDark) DraculaPalette else AlucardPalette
    val fontFamily = fontMode.toComposeFontFamily()
    val colorScheme = if (useDark) {
        darkColorScheme(
            background = palette.bg,
            surface = palette.bg,
            onBackground = palette.fg,
            onSurface = palette.fg,
            primary = palette.project,
            secondary = palette.reminder,
            error = palette.priorityA
        )
    } else {
        lightColorScheme(
            background = palette.bg,
            surface = palette.bg,
            onBackground = palette.fg,
            onSurface = palette.fg,
            primary = palette.project,
            secondary = palette.reminder,
            error = palette.priorityA
        )
    }

    CompositionLocalProvider(
        LocalTickPalette provides palette,
        LocalTickFontFamily provides fontFamily
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = buildTickTypography(fontFamily),
            content = content
        )
    }
}
