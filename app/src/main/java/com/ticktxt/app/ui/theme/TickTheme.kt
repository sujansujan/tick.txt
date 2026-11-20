package com.ticktxt.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat

enum class ThemeMode {
    SYSTEM,
    DRACULA_DARK,
    AMOLED_BLACK,
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
    val bgSurface: Color,
    val bgElevated: Color,
    val fg: Color,
    val fgMuted: Color,
    val border: Color,
    val scrim: Color,
    val priorityA: Color,
    val priorityB: Color,
    val priorityC: Color,
    val project: Color,
    val contextTag: Color,
    val reminder: Color,
    val noteLink: Color
)

/**
 * Dracula (Dark) palette tuned for flat ASCII legibility:
 * - Passive ASCII chrome (`border = #52566E`) recedes cleanly behind `#F8F8F2` ink
 * - Modals & floating boxes use `#2E303E` (`bgSurface`) for shadow-free separation
 * - Inline code & swipe wells use `#1E1F29` (`bgElevated`)
 */
val DraculaPalette = TickSemanticPalette(
    isDark = true,
    bg = Color(0xFF282A36),
    bgSurface = Color(0xFF2E303E),
    bgElevated = Color(0xFF1E1F29),
    fg = Color(0xFFF8F8F2),
    fgMuted = Color(0xFF8F96BA),
    border = Color(0xFF52566E),
    scrim = Color(0xFF14151C).copy(alpha = 0.78f),
    priorityA = Color(0xFFFF6E6E),
    priorityB = Color(0xFFFFB86C),
    priorityC = Color(0xFFF1FA8C),
    project = Color(0xFFBD93F9),
    contextTag = Color(0xFF8BE9FD),
    reminder = Color(0xFF50FA7B),
    noteLink = Color(0xFFFF79C6)
)

/**
 * Pure `#000000` AMOLED Black palette with dimmed OLED-friendly ASCII borders
 * and crisp Dracula syntax highlights. Always used by the Full-Screen Notification
 * and selectable as a full-app theme in Settings.
 */
val AmoledPalette = TickSemanticPalette(
    isDark = true,
    bg = Color(0xFF000000),
    bgSurface = Color(0xFF0D0E14),
    bgElevated = Color(0xFF0A0B10),
    fg = Color(0xFFEBEBF0),
    fgMuted = Color(0xFF6E7594),
    border = Color(0xFF34384A),
    scrim = Color(0xFF000000).copy(alpha = 0.85f),
    priorityA = Color(0xFFFF6E6E),
    priorityB = Color(0xFFFFB86C),
    priorityC = Color(0xFFF1FA8C),
    project = Color(0xFFBD93F9),
    contextTag = Color(0xFF8BE9FD),
    reminder = Color(0xFF50FA7B),
    noteLink = Color(0xFFFF79C6)
)

/**
 * Alucard (Light) warm-parchment palette calibrated to WCAG AA+:
 * - Warm paper `#FFFBEB` canvas, `#F7F2DF` modal surface, `#EAE3CC` inset well
 * - `#C6BFA8` ASCII borders hold crisp character-grid structure without fighting `#1F1F1F` ink
 * - Warm sepia scrim (`#1F1C14` @ 48%) keeps popups native to the light theme
 */
val AlucardPalette = TickSemanticPalette(
    isDark = false,
    bg = Color(0xFFFFFBEB),
    bgSurface = Color(0xFFF7F2DF),
    bgElevated = Color(0xFFEAE3CC),
    fg = Color(0xFF1F1F1F),
    fgMuted = Color(0xFF595649),
    border = Color(0xFFC6BFA8),
    scrim = Color(0xFF1F1C14).copy(alpha = 0.48f),
    priorityA = Color(0xFFCB3A2A),
    priorityB = Color(0xFFA34D14),
    priorityC = Color(0xFF7A630F),
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
    val sysDark = isSystemInDarkTheme()
    val palette = when (themeMode) {
        ThemeMode.SYSTEM -> if (sysDark) DraculaPalette else AlucardPalette
        ThemeMode.DRACULA_DARK -> DraculaPalette
        ThemeMode.AMOLED_BLACK -> AmoledPalette
        ThemeMode.ALUCARD_LIGHT -> AlucardPalette
    }
    val useDark = palette.isDark
    val fontFamily = fontMode.toComposeFontFamily()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    val colorScheme = if (useDark) {
        darkColorScheme(
            background = palette.bg,
            surface = palette.bgSurface,
            surfaceVariant = palette.bgElevated,
            onBackground = palette.fg,
            onSurface = palette.fg,
            onSurfaceVariant = palette.fgMuted,
            outline = palette.border,
            primary = palette.project,
            onPrimary = palette.bg,
            secondary = palette.reminder,
            onSecondary = palette.bg,
            error = palette.priorityA,
            onError = palette.bg
        )
    } else {
        lightColorScheme(
            background = palette.bg,
            surface = palette.bgSurface,
            surfaceVariant = palette.bgElevated,
            onBackground = palette.fg,
            onSurface = palette.fg,
            onSurfaceVariant = palette.fgMuted,
            outline = palette.border,
            primary = palette.project,
            onPrimary = palette.bg,
            secondary = palette.reminder,
            onSecondary = palette.bg,
            error = palette.priorityA,
            onError = palette.bg
        )
    }

    val textSelectionColors = remember(palette) {
        TextSelectionColors(
            handleColor = palette.project,
            backgroundColor = palette.project.copy(alpha = if (useDark) 0.30f else 0.22f)
        )
    }

    CompositionLocalProvider(
        LocalTickPalette provides palette,
        LocalTickFontFamily provides fontFamily,
        LocalTextSelectionColors provides textSelectionColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = buildTickTypography(fontFamily),
            content = content
        )
    }
}
