package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.ui.theme.FontMode
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode
import java.io.File

/**
 * Settings screen built in https://plaintextsports.com/ style with:
 * - Full-width responsive `+---...---+` character-grid scorecards (zero wasted right space)
 * - Wrapping `FlowRow` links so no option ("30m Before", "Archive Completed") is ever clipped
 * - Full support for `alarmMutedByDefault` (default Muted with System Notification Sound;
 *   when Unmuted, silences System Notification Sound).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    baseDir: File,
    storageFolderPath: String,
    settings: PersistedSettings,
    onUpdateSettings: (PersistedSettings) -> Unit,
    onSyncAllToGoogleCalendar: () -> Unit,
    onPickFolderWithSaf: () -> Unit,
    onOpenSavedFileLocation: () -> Unit,
    onArchiveCompletedTasks: () -> Unit,
    onFireImmediateNotification: () -> Unit
) {
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current
    val monoFont = FontFamily.Monospace
    val baseSp = settings.fontSizeSp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        val innerCols = rememberResponsiveInnerCols(constraints, boxFontSizeSp = 13)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // =================================================================
            // 1. APPEARANCE (Dark Mode | Light Mode, Font, Text Size)
            // =================================================================
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Appearance",
                    color = palette.fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = (baseSp + 1).sp,
                    fontFamily = appFont
                )

                val appearanceBox = remember(settings.themeMode, settings.fontMode, settings.fontSizeSp, innerCols) {
                    val modeName = when (settings.themeMode) {
                        ThemeMode.DRACULA_DARK -> "Dark Mode (Dracula)"
                        ThemeMode.ALUCARD_LIGHT -> "Light Mode (Alucard)"
                        ThemeMode.SYSTEM -> "System Default"
                    }
                    val fontName = when (settings.fontMode) {
                        FontMode.MONOSPACE -> "Monospace"
                        FontMode.SYSTEM_SANS -> "System Sans"
                        FontMode.SYSTEM_SERIF -> "Serif"
                    }
                    buildString {
                        appendLine(PlainTextSportsAscii.topBorder(innerCols))
                        appendLine(PlainTextSportsAscii.rowLeftRight("Theme Mode", modeName, innerCols))
                        appendLine(PlainTextSportsAscii.rowLeftRight("Typography", "$fontName (${settings.fontSizeSp}sp)", innerCols))
                        append(PlainTextSportsAscii.botBorder(innerCols))
                    }
                }
                Text(
                    text = appearanceBox,
                    color = palette.fg,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = monoFont,
                    modifier = Modifier.fillMaxWidth()
                )

                // Mode row (evenly distributed / wrapped)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Mode:",
                        color = palette.fgMuted,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    listOf(
                        ThemeMode.DRACULA_DARK to "Dark Mode",
                        ThemeMode.ALUCARD_LIGHT to "Light Mode",
                        ThemeMode.SYSTEM to "System"
                    ).forEach { (mode, label) ->
                        val selected = settings.themeMode == mode
                        Text(
                            text = label,
                            color = if (selected) palette.project else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = baseSp.sp,
                            fontFamily = appFont,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable { onUpdateSettings(settings.copy(themeMode = mode)) }
                                .padding(vertical = 6.dp)
                        )
                    }
                }

                // Font row
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Font:",
                        color = palette.fgMuted,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    listOf(
                        FontMode.MONOSPACE to "Monospace",
                        FontMode.SYSTEM_SANS to "System Sans",
                        FontMode.SYSTEM_SERIF to "Serif"
                    ).forEach { (fMode, label) ->
                        val selected = settings.fontMode == fMode
                        Text(
                            text = label,
                            color = if (selected) palette.project else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = baseSp.sp,
                            fontFamily = fMode.toComposeFontFamily(),
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable { onUpdateSettings(settings.copy(fontMode = fMode)) }
                                .padding(vertical = 6.dp)
                        )
                    }
                }

                // Size row
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Size:",
                        color = palette.fgMuted,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    listOf(14, 16, 18, 20).forEach { sz ->
                        val selected = settings.fontSizeSp == sz
                        Text(
                            text = "${sz}sp",
                            color = if (selected) palette.project else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = baseSp.sp,
                            fontFamily = appFont,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable { onUpdateSettings(settings.copy(fontSizeSp = sz)) }
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            }

            // =================================================================
            // 2. REMINDERS & FULL-SCREEN ALARM (Audio Mode + Wrapped Offsets)
            // =================================================================
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Reminders & Full-Screen Alarm",
                    color = palette.fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = (baseSp + 1).sp,
                    fontFamily = appFont
                )

                val alarmCard = remember(settings.defaultTimedReminder, settings.alarmMutedByDefault, innerCols) {
                    val audioDesc = if (settings.alarmMutedByDefault) {
                        "Muted (System Notif Sound)"
                    } else {
                        "Unmuted (Notif Silenced)"
                    }
                    buildString {
                        appendLine(PlainTextSportsAscii.topBorder(innerCols))
                        appendLine(PlainTextSportsAscii.rowLeftRight("Alarm Audio", audioDesc, innerCols))
                        appendLine(PlainTextSportsAscii.rowLeftRight("Default Spec", "rem:${settings.defaultTimedReminder}", innerCols))
                        appendLine(PlainTextSportsAscii.rowLeftRight("Snooze Units", "sec min hr day wk mo", innerCols))
                        append(PlainTextSportsAscii.botBorder(innerCols))
                    }
                }
                Text(
                    text = alarmCard,
                    color = palette.fg,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = monoFont,
                    modifier = Modifier.fillMaxWidth()
                )

                // Audio Mode Selector: Muted (System Notification Sound) vs Unmuted (Silences Notification)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Audio:",
                        color = palette.fgMuted,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    listOf(
                        true to "Muted (System Notif Sound)",
                        false to "Unmuted (Silence Notif)"
                    ).forEach { (mutedVal, label) ->
                        val selected = settings.alarmMutedByDefault == mutedVal
                        Text(
                            text = label,
                            color = if (selected) palette.reminder else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = baseSp.sp,
                            fontFamily = appFont,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable { onUpdateSettings(settings.copy(alarmMutedByDefault = mutedVal)) }
                                .padding(vertical = 6.dp)
                        )
                    }
                }

                // Default Reminder Offset (FlowRow so 30m Before / 1h Before / 1d Before wrap cleanly!)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Default:",
                        color = palette.fgMuted,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    listOf(
                        "0m/a" to "At Due (Alarm)",
                        "-5m/a" to "5m Before",
                        "-15m/a" to "15m Before",
                        "-30m/a" to "30m Before",
                        "-1h/a" to "1h Before",
                        "-1d/a" to "1d Before"
                    ).forEach { (spec, label) ->
                        val selected = settings.defaultTimedReminder == spec ||
                            (spec == "0m/a" && settings.defaultTimedReminder == "0m/n")
                        Text(
                            text = label,
                            color = if (selected) palette.reminder else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = baseSp.sp,
                            fontFamily = appFont,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable { onUpdateSettings(settings.copy(defaultTimedReminder = spec)) }
                                .padding(vertical = 6.dp)
                        )
                    }
                }

                Text(
                    text = "Test Full-Screen Alarm & Notification >",
                    color = palette.priorityA,
                    fontWeight = FontWeight.Bold,
                    fontSize = baseSp.sp,
                    fontFamily = appFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { onFireImmediateNotification() }
                        .padding(vertical = 6.dp)
                )
            }

            // =================================================================
            // 3. GOOGLE CALENDAR SYNC
            // =================================================================
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Google Calendar",
                    color = palette.fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = (baseSp + 1).sp,
                    fontFamily = appFont
                )

                val gcalCard = remember(settings.autoSyncGoogleCalendar, innerCols) {
                    buildString {
                        appendLine(PlainTextSportsAscii.topBorder(innerCols))
                        appendLine(
                            PlainTextSportsAscii.rowLeftRight(
                                "Auto-Sync Scheduled",
                                if (settings.autoSyncGoogleCalendar) "ON" else "OFF",
                                innerCols
                            )
                        )
                        appendLine(PlainTextSportsAscii.rowLeftRight("Provider", "Android CalendarContract", innerCols))
                        append(PlainTextSportsAscii.botBorder(innerCols))
                    }
                }
                Text(
                    text = gcalCard,
                    color = palette.fg,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = monoFont,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (settings.autoSyncGoogleCalendar) "Disable Auto-Sync" else "Enable Auto-Sync",
                        color = palette.fg,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                onUpdateSettings(settings.copy(autoSyncGoogleCalendar = !settings.autoSyncGoogleCalendar))
                            }
                            .padding(vertical = 6.dp)
                    )

                    Text(
                        text = "Sync All Scheduled Tasks >",
                        color = palette.contextTag,
                        fontWeight = FontWeight.Bold,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onSyncAllToGoogleCalendar() }
                            .padding(vertical = 6.dp)
                    )
                }
            }

            // =================================================================
            // 4. PLAIN TEXT STORAGE (Full-Width Box & Wrapped Links)
            // =================================================================
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Plain Text Storage",
                    color = palette.fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = (baseSp + 1).sp,
                    fontFamily = appFont
                )

                val storageCard = remember(storageFolderPath, innerCols) {
                    buildString {
                        appendLine(PlainTextSportsAscii.topBorder(innerCols))
                        appendLine(PlainTextSportsAscii.rowLeftRight("Active File", "todo.txt", innerCols))
                        appendLine(PlainTextSportsAscii.rowLeftRight("Archive File", "done.txt", innerCols))
                        PlainTextSportsAscii.wrapRows(storageFolderPath, innerCols).forEach { appendLine(it) }
                        append(PlainTextSportsAscii.botBorder(innerCols))
                    }
                }
                Text(
                    text = storageCard,
                    color = palette.fg,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = monoFont,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Change Folder",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onPickFolderWithSaf() }
                            .padding(vertical = 6.dp)
                    )

                    Text(
                        text = "Open / Share todo.txt",
                        color = palette.fg,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onOpenSavedFileLocation() }
                            .padding(vertical = 6.dp)
                    )

                    Text(
                        text = "Archive Completed",
                        color = palette.fgMuted,
                        fontSize = baseSp.sp,
                        fontFamily = appFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onArchiveCompletedTasks() }
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}
