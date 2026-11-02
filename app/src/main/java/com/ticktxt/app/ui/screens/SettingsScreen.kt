package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.ui.theme.FontMode
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode
import java.io.File

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
    val fontFamily = LocalTickFontFamily.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. GOOGLE CALENDAR SYNC
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Google Calendar Sync",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = fontFamily
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onUpdateSettings(settings.copy(autoSyncGoogleCalendar = !settings.autoSyncGoogleCalendar))
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto-sync new scheduled tasks",
                    color = palette.fg,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
                Box(
                    modifier = Modifier
                        .background(if (settings.autoSyncGoogleCalendar) palette.reminder else palette.bg)
                        .border(1.dp, if (settings.autoSyncGoogleCalendar) palette.reminder else palette.border)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (settings.autoSyncGoogleCalendar) "ON" else "OFF",
                        color = if (settings.autoSyncGoogleCalendar) palette.bg else palette.fgMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = fontFamily
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(palette.contextTag)
                    .clickable { onSyncAllToGoogleCalendar() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Sync All Scheduled Tasks to Google Calendar",
                    color = palette.bg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            }
        }

        // 2. THEME, FONT FAMILY & TEXT SIZE
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Appearance & Font",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = fontFamily
            )

            Text(text = "Theme", color = palette.fgMuted, fontSize = 13.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    ThemeMode.DRACULA_DARK to "Dracula",
                    ThemeMode.ALUCARD_LIGHT to "Alucard",
                    ThemeMode.SYSTEM to "System"
                ).forEach { (mode, label) ->
                    val selected = settings.themeMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .background(if (selected) palette.project else palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(themeMode = mode)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) palette.bg else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }

            Text(text = "Font Family", color = palette.fgMuted, fontSize = 13.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    FontMode.SYSTEM_SANS to "System Sans",
                    FontMode.MONOSPACE to "Monospace",
                    FontMode.SYSTEM_SERIF to "Serif"
                ).forEach { (fMode, label) ->
                    val selected = settings.fontMode == fMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .background(if (selected) palette.project else palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(fontMode = fMode)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) palette.bg else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = fMode.toComposeFontFamily()
                        )
                    }
                }
            }

            Text(text = "Text Size", color = palette.fgMuted, fontSize = 13.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(14, 16, 18, 20).forEach { sz ->
                    val selected = settings.fontSizeSp == sz
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .background(if (selected) palette.project else palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(fontSizeSp = sz)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${sz}sp",
                            color = if (selected) palette.bg else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        // 3. REMINDER DEFAULTS
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Reminders",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = fontFamily
            )

            Text(text = "Default Reminder Offset", color = palette.fgMuted, fontSize = 13.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "0m/n" to "At due time",
                    "-5m/n" to "5m before",
                    "-15m/n" to "15m before",
                    "-30m/n" to "30m before",
                    "-1h/n" to "1h before",
                    "-1d/n" to "1d before",
                    "0m/a" to "Loud Alarm"
                ).forEach { (spec, label) ->
                    val selected = settings.defaultTimedReminder == spec
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .background(if (selected) palette.reminder else palette.bg)
                            .border(1.dp, if (selected) palette.reminder else palette.border)
                            .clickable { onUpdateSettings(settings.copy(defaultTimedReminder = spec)) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) palette.bg else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(palette.bg)
                    .border(1.dp, palette.reminder)
                    .clickable { onFireImmediateNotification() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Send Test Notification Now",
                    color = palette.reminder,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            }
        }

        // 4. STORAGE & FILES
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Files & Storage",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "Folder: $storageFolderPath",
                color = palette.fgMuted,
                fontSize = 12.sp,
                fontFamily = fontFamily
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(palette.project)
                        .clickable { onPickFolderWithSaf() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Choose Folder",
                        color = palette.bg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(palette.bg)
                        .border(1.dp, palette.border)
                        .clickable { onOpenSavedFileLocation() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Open / Share todo.txt",
                        color = palette.fg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(palette.bg)
                    .border(1.dp, palette.border)
                    .clickable { onArchiveCompletedTasks() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Archive Completed Tasks to done.txt",
                    color = palette.fgMuted,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            }
        }
    }
}
