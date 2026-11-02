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
        // =====================================================================
        // ASCII TERMINAL CONFIG BANNER
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.project)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "╔════════════════════════════════════════╗",
                color = palette.project,
                fontSize = 12.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "║  [⚙] T I C K . T X T  //  S Y S - C F G ║",
                color = palette.fg,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "╚════════════════════════════════════════╝",
                color = palette.project,
                fontSize = 12.sp,
                fontFamily = fontFamily
            )
        }

        // =====================================================================
        // 1. FULL-SCREEN ALARM, SNOOZE & REMINDER DEFAULTS
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
                text = "┌──[ 01 // FULL-SCREEN ALARM & SNOOZE ]──┐",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )

            Text(
                text = ":: DEFAULT REMINDER OFFSET FOR SCHEDULED TASKS ::",
                color = palette.fgMuted,
                fontSize = 12.sp,
                fontFamily = fontFamily
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "0m/a" to "[0m FULL ALARM]",
                    "0m/n" to "[0m AT DUE]",
                    "-5m/a" to "[-5m ALARM]",
                    "-15m/a" to "[-15m ALARM]",
                    "-30m/a" to "[-30m ALARM]",
                    "-1h/a" to "[-1h ALARM]",
                    "-1d/a" to "[-1d ALARM]"
                ).forEach { (spec, label) ->
                    val selected = settings.defaultTimedReminder == spec
                    Box(
                        modifier = Modifier
                            .heightIn(min = 46.dp)
                            .background(if (selected) palette.reminder else palette.bg)
                            .border(1.dp, if (selected) palette.reminder else palette.border)
                            .clickable { onUpdateSettings(settings.copy(defaultTimedReminder = spec)) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "* $label *" else label,
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
                    .heightIn(min = 52.dp)
                    .background(palette.bg)
                    .border(2.dp, palette.priorityA)
                    .clickable { onFireImmediateNotification() }
                    .padding(vertical = 14.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "╔═[ 🔔 TEST FULL-SCREEN ALARM & NOTIFICATION ]═╗",
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
                text = "┌──[ 02 // GOOGLE CALENDAR SYNC ]────────┐",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
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
                    text = ">> Auto-sync scheduled tasks:",
                    color = palette.fg,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .background(if (settings.autoSyncGoogleCalendar) palette.reminder else palette.bg)
                        .border(1.dp, if (settings.autoSyncGoogleCalendar) palette.reminder else palette.border)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (settings.autoSyncGoogleCalendar) "[[ ON ]]" else "[ OFF ]",
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
                    .heightIn(min = 50.dp)
                    .background(palette.contextTag)
                    .border(1.dp, palette.fg)
                    .clickable { onSyncAllToGoogleCalendar() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ ⇄ SYNC ALL SCHEDULED TASKS TO GOOGLE CAL ]",
                    color = palette.bg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
            }
        }

        // =====================================================================
        // 3. THEME, FONT FAMILY & TEXT SIZE
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
                text = "┌──[ 03 // ASCII THEME & TYPOGRAPHY ]────┐",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )

            Text(text = ":: COLOR PALETTE ::", color = palette.fgMuted, fontSize = 12.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    ThemeMode.DRACULA_DARK to "DRACULA",
                    ThemeMode.ALUCARD_LIGHT to "ALUCARD",
                    ThemeMode.SYSTEM to "SYSTEM"
                ).forEach { (mode, label) ->
                    val selected = settings.themeMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(if (selected) palette.project else palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(themeMode = mode)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "[$label]" else label,
                            color = if (selected) palette.bg else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }

            Text(text = ":: FONT ENGINE ::", color = palette.fgMuted, fontSize = 12.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    FontMode.MONOSPACE to "MONO",
                    FontMode.SYSTEM_SANS to "SYS SANS",
                    FontMode.SYSTEM_SERIF to "SERIF"
                ).forEach { (fMode, label) ->
                    val selected = settings.fontMode == fMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(if (selected) palette.project else palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(fontMode = fMode)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "[$label]" else label,
                            color = if (selected) palette.bg else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = fMode.toComposeFontFamily()
                        )
                    }
                }
            }

            Text(text = ":: TEXT SCALE (SP) ::", color = palette.fgMuted, fontSize = 12.sp, fontFamily = fontFamily)
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
                            .background(if (selected) palette.project else palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { onUpdateSettings(settings.copy(fontSizeSp = sz)) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "[${sz}sp]" else "${sz}sp",
                            color = if (selected) palette.bg else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
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
                text = "┌──[ 04 // PLAIN-TEXT DISK STORAGE ]─────┐",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "PATH >> $storageFolderPath",
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
                        text = "[ 📁 PICK DIR ]",
                        color = palette.bg,
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
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[ 📤 OPEN TODO.TXT ]",
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
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ 📦 ARCHIVE DONE (x) -> DONE.TXT ]",
                    color = palette.fgMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
            }
        }
    }
}
