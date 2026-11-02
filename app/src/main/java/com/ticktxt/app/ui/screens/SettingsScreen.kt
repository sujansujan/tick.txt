package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode
import java.io.File

/**
 * Settings screen built from scratch in https://plaintextsports.com/ style:
 * - Zero Compose/CSS box borders or filled rectangles
 * - Character-grid `+--------------------------------------+` scorecards
 * - Underlined inline text links with generous touch padding.
 */
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
    val monoFont = FontFamily.Monospace

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // =====================================================================
        // 1. APPEARANCE (Dark Mode | Light Mode, Font, Text Size)
        // =====================================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Appearance",
                color = palette.fg,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = monoFont
            )

            // Mode: Dark Mode  Light Mode  System
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Mode:", color = palette.fgMuted, fontSize = 14.sp, fontFamily = monoFont)
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
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onUpdateSettings(settings.copy(themeMode = mode)) }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }

            // Font: Monospace  System Sans  Serif
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Font:", color = palette.fgMuted, fontSize = 14.sp, fontFamily = monoFont)
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
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onUpdateSettings(settings.copy(fontMode = fMode)) }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }

            // Size: 14sp  16sp  18sp  20sp
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Size:", color = palette.fgMuted, fontSize = 14.sp, fontFamily = monoFont)
                listOf(14, 16, 18, 20).forEach { sz ->
                    val selected = settings.fontSizeSp == sz
                    Text(
                        text = "${sz}sp",
                        color = if (selected) palette.project else palette.fg,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onUpdateSettings(settings.copy(fontSizeSp = sz)) }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }
        }

        // =====================================================================
        // 2. REMINDERS & FULL-SCREEN ALARM
        // =====================================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Reminders & Full-Screen Alarm",
                color = palette.fg,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = monoFont
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Default:", color = palette.fgMuted, fontSize = 14.sp, fontFamily = monoFont)
                listOf(
                    "0m/a" to "At Due (Alarm)",
                    "-5m/a" to "5m Before",
                    "-15m/a" to "15m Before",
                    "-30m/a" to "30m Before",
                    "-1h/a" to "1h Before",
                    "-1d/a" to "1d Before"
                ).forEach { (spec, label) ->
                    val selected = settings.defaultTimedReminder == spec
                    Text(
                        text = label,
                        color = if (selected) palette.reminder else palette.fg,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onUpdateSettings(settings.copy(defaultTimedReminder = spec)) }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }

            val alarmCard = remember(settings.defaultTimedReminder) {
                buildString {
                    appendLine(PlainTextSportsAscii.TOP_BORDER)
                    appendLine(PlainTextSportsAscii.rowLeftRight("Full-Screen Alarm", "Active"))
                    appendLine(PlainTextSportsAscii.rowLeftRight("Default Spec", "rem:${settings.defaultTimedReminder}"))
                    appendLine(PlainTextSportsAscii.rowLeftRight("Snooze Units", "sec min hr day wk mo"))
                    append(PlainTextSportsAscii.BOT_BORDER)
                }
            }
            Text(
                text = alarmCard,
                color = palette.fg,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = monoFont
            )

            Text(
                text = "Test Full-Screen Alarm & Notification >",
                color = palette.priorityA,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = monoFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onFireImmediateNotification() }
                    .padding(vertical = 8.dp)
            )
        }

        // =====================================================================
        // 3. GOOGLE CALENDAR SYNC
        // =====================================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Google Calendar",
                color = palette.fg,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = monoFont
            )

            val gcalCard = remember(settings.autoSyncGoogleCalendar) {
                buildString {
                    appendLine(PlainTextSportsAscii.TOP_BORDER)
                    appendLine(
                        PlainTextSportsAscii.rowLeftRight(
                            "Auto-Sync Scheduled",
                            if (settings.autoSyncGoogleCalendar) "ON" else "OFF"
                        )
                    )
                    appendLine(PlainTextSportsAscii.rowLeftRight("Provider", "Android CalendarContract"))
                    append(PlainTextSportsAscii.BOT_BORDER)
                }
            }
            Text(
                text = gcalCard,
                color = palette.fg,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = monoFont
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (settings.autoSyncGoogleCalendar) "Disable Auto-Sync" else "Enable Auto-Sync",
                    color = palette.fg,
                    fontSize = 14.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable {
                            onUpdateSettings(settings.copy(autoSyncGoogleCalendar = !settings.autoSyncGoogleCalendar))
                        }
                        .padding(vertical = 8.dp)
                )

                Text(
                    text = "Sync All Scheduled Tasks >",
                    color = palette.contextTag,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { onSyncAllToGoogleCalendar() }
                        .padding(vertical = 8.dp)
                )
            }
        }

        // =====================================================================
        // 4. PLAIN TEXT STORAGE (todo.txt & done.txt)
        // =====================================================================
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Plain Text Storage",
                color = palette.fg,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = monoFont
            )

            val storageCard = remember(storageFolderPath) {
                buildString {
                    appendLine(PlainTextSportsAscii.TOP_BORDER)
                    appendLine(PlainTextSportsAscii.rowLeftRight("Active File", "todo.txt"))
                    appendLine(PlainTextSportsAscii.rowLeftRight("Archive File", "done.txt"))
                    PlainTextSportsAscii.wrapRows(storageFolderPath).forEach { appendLine(it) }
                    append(PlainTextSportsAscii.BOT_BORDER)
                }
            }
            Text(
                text = storageCard,
                color = palette.fg,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = monoFont
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Change Folder",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { onPickFolderWithSaf() }
                        .padding(vertical = 8.dp)
                )

                Text(
                    text = "Open / Share todo.txt",
                    color = palette.fg,
                    fontSize = 14.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { onOpenSavedFileLocation() }
                        .padding(vertical = 8.dp)
                )

                Text(
                    text = "Archive Completed",
                    color = palette.fgMuted,
                    fontSize = 14.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { onArchiveCompletedTasks() }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}
