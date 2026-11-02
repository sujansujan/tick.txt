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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =====================================================================
        // 1. ALARM & REMINDER ENGINE
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "┌─ 01 // alarm & reminder defaults",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )

            Text(
                text = "├─ default trigger for timed tasks:",
                color = palette.fgMuted,
                fontSize = 13.sp,
                fontFamily = fontFamily
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "0m/a" to "0m/a (alarm)",
                    "0m/n" to "0m/n (notif)",
                    "-5m/a" to "-5m/a",
                    "-15m/a" to "-15m/a",
                    "-30m/a" to "-30m/a",
                    "-1h/a" to "-1h/a",
                    "-1d/a" to "-1d/a"
                ).forEach { (spec, label) ->
                    val selected = settings.defaultTimedReminder == spec
                    Box(
                        modifier = Modifier
                            .heightIn(min = 46.dp)
                            .background(palette.bg)
                            .border(1.dp, if (selected) palette.reminder else palette.border)
                            .clickable { onUpdateSettings(settings.copy(defaultTimedReminder = spec)) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "(•) $label" else "( ) $label",
                            color = if (selected) palette.reminder else palette.fgMuted,
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
                    .border(1.dp, palette.priorityA)
                    .clickable { onFireImmediateNotification() }
                    .padding(vertical = 12.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[!] test full-screen alarm & notification",
                    color = palette.priorityA,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
            }
        }

        // =====================================================================
        // 2. GOOGLE CALENDAR SYNC
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "┌─ 02 // google calendar sync",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(palette.bg)
                    .border(1.dp, palette.border)
                    .clickable {
                        onUpdateSettings(settings.copy(autoSyncGoogleCalendar = !settings.autoSyncGoogleCalendar))
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "├─ auto-sync scheduled tasks",
                    color = palette.fg,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
                Text(
                    text = if (settings.autoSyncGoogleCalendar) "[x] on" else "[ ] off",
                    color = if (settings.autoSyncGoogleCalendar) palette.reminder else palette.fgMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(palette.bg)
                    .border(1.dp, palette.contextTag)
                    .clickable { onSyncAllToGoogleCalendar() }
                    .padding(vertical = 12.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ +gcal : sync all scheduled tasks now ]",
                    color = palette.contextTag,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
            }
        }

        // =====================================================================
        // 3. APPEARANCE & TYPOGRAPHY
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "┌─ 03 // theme & typography",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )

            Text(text = "├─ color palette:", color = palette.fgMuted, fontSize = 13.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    ThemeMode.DRACULA_DARK to "dracula",
                    ThemeMode.ALUCARD_LIGHT to "alucard",
                    ThemeMode.SYSTEM to "system"
                ).forEach { (mode, label) ->
                    val selected = settings.themeMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(themeMode = mode)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "(•) $label" else "( ) $label",
                            color = if (selected) palette.project else palette.fgMuted,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }

            Text(text = "├─ font family:", color = palette.fgMuted, fontSize = 13.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    FontMode.MONOSPACE to "mono",
                    FontMode.SYSTEM_SANS to "sans",
                    FontMode.SYSTEM_SERIF to "serif"
                ).forEach { (fMode, label) ->
                    val selected = settings.fontMode == fMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(fontMode = fMode)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "(•) $label" else "( ) $label",
                            color = if (selected) palette.project else palette.fgMuted,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = fMode.toComposeFontFamily()
                        )
                    }
                }
            }

            Text(text = "└─ text scale:", color = palette.fgMuted, fontSize = 13.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(14, 16, 18, 20).forEach { sz ->
                    val selected = settings.fontSizeSp == sz
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(fontSizeSp = sz)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "[*${sz}sp]" else "[ ${sz}sp]",
                            color = if (selected) palette.project else palette.fgMuted,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        // =====================================================================
        // 4. STORAGE & PLAIN-TEXT FILES
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "┌─ 04 // plain-text disk files",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "├─ dir : $storageFolderPath",
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
                        .background(palette.bg)
                        .border(1.dp, palette.project)
                        .clickable { onPickFolderWithSaf() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[ choose folder ]",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
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
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[ share todo.txt ]",
                        color = palette.fg,
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
                    .background(palette.bg)
                    .border(1.dp, palette.border)
                    .clickable { onArchiveCompletedTasks() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ archive completed (x) -> done.txt ]",
                    color = palette.fgMuted,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
            }
        }
    }
}
