package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
 * Settings screen using native Compose full-width ASCII scorecards (`AsciiHorizontalBorder`,
 * `AsciiBoxLeftRightRow`, `AsciiBoxBodyRow`) so every box fits 100% of the screen width
 * without ever wrapping border characters.
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
    val baseSp = settings.fontSizeSp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // =====================================================================
        // 1. APPEARANCE (Dark Mode | Light Mode, Font, Text Size)
        // =====================================================================
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

            Column(modifier = Modifier.fillMaxWidth()) {
                AsciiHorizontalBorder()
                AsciiBoxLeftRightRow(
                    left = "Theme Mode",
                    right = modeName,
                    leftColor = palette.fgMuted,
                    rightColor = palette.fg
                )
                AsciiBoxLeftRightRow(
                    left = "Typography",
                    right = "$fontName (${settings.fontSizeSp}sp)",
                    leftColor = palette.fgMuted,
                    rightColor = palette.fg
                )
                AsciiHorizontalBorder()
            }

            // Mode row
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

        // =====================================================================
        // 2. REMINDERS & FULL-SCREEN ALARM
        // =====================================================================
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

            Column(modifier = Modifier.fillMaxWidth()) {
                AsciiHorizontalBorder()
                AsciiBoxLeftRightRow(
                    left = "Alarm Audio",
                    right = if (settings.alarmMutedByDefault) "Muted (Notif Sound)" else "Unmuted (Notif Silenced)",
                    leftColor = palette.fgMuted,
                    rightColor = if (settings.alarmMutedByDefault) palette.reminder else palette.priorityA
                )
                AsciiBoxLeftRightRow(
                    left = "Default Spec",
                    right = "rem:${settings.defaultTimedReminder}",
                    leftColor = palette.fgMuted,
                    rightColor = palette.fg
                )
                AsciiBoxLeftRightRow(
                    left = "Snooze Units",
                    right = "sec min hr day wk mo",
                    leftColor = palette.fgMuted,
                    rightColor = palette.fg
                )
                AsciiHorizontalBorder()
            }

            // Audio Mode Selector
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

            // Default Reminder Offset
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

        // =====================================================================
        // 3. GOOGLE CALENDAR SYNC
        // =====================================================================
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

            Column(modifier = Modifier.fillMaxWidth()) {
                AsciiHorizontalBorder()
                AsciiBoxLeftRightRow(
                    left = "Auto-Sync Scheduled",
                    right = if (settings.autoSyncGoogleCalendar) "ON" else "OFF",
                    leftColor = palette.fgMuted,
                    rightColor = if (settings.autoSyncGoogleCalendar) palette.reminder else palette.fgMuted,
                    boldRight = true
                )
                AsciiBoxLeftRightRow(
                    left = "Provider",
                    right = "Android CalendarContract",
                    leftColor = palette.fgMuted,
                    rightColor = palette.fg
                )
                AsciiHorizontalBorder()
            }

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

        // =====================================================================
        // 4. PLAIN TEXT STORAGE
        // =====================================================================
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

            Column(modifier = Modifier.fillMaxWidth()) {
                AsciiHorizontalBorder()
                AsciiBoxLeftRightRow(
                    left = "Active File",
                    right = "todo.txt",
                    leftColor = palette.fgMuted,
                    rightColor = palette.fg
                )
                AsciiBoxLeftRightRow(
                    left = "Archive File",
                    right = "done.txt",
                    leftColor = palette.fgMuted,
                    rightColor = palette.fg
                )
                AsciiBoxBodyRow(
                    text = storageFolderPath,
                    textColor = palette.fgMuted,
                    fontSizeSp = 12
                )
                AsciiHorizontalBorder()
            }

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
