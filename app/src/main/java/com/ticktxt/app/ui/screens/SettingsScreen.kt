package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.ui.theme.FontMode
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode
import java.io.File

/**
 * Settings screen with 100% of options, toggles, and actions placed strictly INSIDE
 * the full-width `+---...---+` ASCII boxes using ASCII icons (`[*]`, `[!]`, `[#]`, `[/]`)
 * and zero redundant text outside the boxes.
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
    val baseSp = settings.fontSizeSp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =====================================================================
        // 1. [*] APPEARANCE BOX (All options inside the box only)
        // =====================================================================
        Column(modifier = Modifier.fillMaxWidth()) {
            AsciiHorizontalBorder()
            AsciiBoxLeftRightRow(
                left = "[*] APPEARANCE",
                right = "${settings.fontSizeSp}sp",
                leftColor = palette.project,
                rightColor = palette.fgMuted,
                fontSizeSp = baseSp,
                boldLeft = true
            )
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxOptionRow(
                label = "Mode",
                options = listOf(
                    ThemeMode.DRACULA_DARK to "Dark",
                    ThemeMode.ALUCARD_LIGHT to "Light",
                    ThemeMode.SYSTEM to "System"
                ),
                selectedValue = settings.themeMode,
                onSelect = { onUpdateSettings(settings.copy(themeMode = it)) },
                activeColor = palette.project,
                fontSizeSp = baseSp
            )
            AsciiBoxOptionRow(
                label = "Font",
                options = listOf(
                    FontMode.MONOSPACE to "Mono",
                    FontMode.SYSTEM_SANS to "Sans",
                    FontMode.SYSTEM_SERIF to "Serif"
                ),
                selectedValue = settings.fontMode,
                onSelect = { onUpdateSettings(settings.copy(fontMode = it)) },
                activeColor = palette.project,
                fontSizeSp = baseSp
            )
            AsciiBoxOptionRow(
                label = "Size",
                options = listOf(14, 16, 18, 20).map { it to "${it}sp" },
                selectedValue = settings.fontSizeSp,
                onSelect = { onUpdateSettings(settings.copy(fontSizeSp = it)) },
                activeColor = palette.project,
                fontSizeSp = baseSp
            )
            AsciiHorizontalBorder()
        }

        // =====================================================================
        // 2. [!] REMINDERS & ALARM BOX (All options inside the box only)
        // =====================================================================
        Column(modifier = Modifier.fillMaxWidth()) {
            val normalizedRem = if (settings.defaultTimedReminder == "0m/n") "0m/a" else settings.defaultTimedReminder
            AsciiHorizontalBorder()
            AsciiBoxLeftRightRow(
                left = "[!] REMINDERS & ALARM",
                right = "rem:$normalizedRem",
                leftColor = palette.priorityB,
                rightColor = palette.reminder,
                fontSizeSp = baseSp,
                boldLeft = true
            )
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxOptionRow(
                label = "Audio",
                options = listOf(
                    true to "Muted (Notif)",
                    false to "Loud (No Notif)"
                ),
                selectedValue = settings.alarmMutedByDefault,
                onSelect = { onUpdateSettings(settings.copy(alarmMutedByDefault = it)) },
                activeColor = if (settings.alarmMutedByDefault) palette.reminder else palette.priorityA,
                fontSizeSp = baseSp
            )
            AsciiBoxOptionRow(
                label = "Offset",
                options = listOf(
                    "0m/a" to "0m",
                    "-5m/a" to "-5m",
                    "-15m/a" to "-15m",
                    "-30m/a" to "-30m",
                    "-1h/a" to "-1h",
                    "-1d/a" to "-1d"
                ),
                selectedValue = normalizedRem,
                onSelect = { onUpdateSettings(settings.copy(defaultTimedReminder = it)) },
                activeColor = palette.reminder,
                fontSizeSp = baseSp
            )
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxLeftRightRow(
                left = "[>] Test Full-Screen Alarm",
                right = "[Run >]",
                leftColor = palette.priorityA,
                rightColor = palette.priorityA,
                fontSizeSp = baseSp,
                boldLeft = true,
                boldRight = true,
                underlineRight = true,
                onClick = { onFireImmediateNotification() }
            )
            AsciiHorizontalBorder()
        }

        // =====================================================================
        // 3. [#] GOOGLE CALENDAR BOX (All options inside the box only)
        // =====================================================================
        Column(modifier = Modifier.fillMaxWidth()) {
            AsciiHorizontalBorder()
            AsciiBoxLeftRightRow(
                left = "[#] GOOGLE CALENDAR",
                right = if (settings.autoSyncGoogleCalendar) "AUTO: ON" else "AUTO: OFF",
                leftColor = palette.contextTag,
                rightColor = if (settings.autoSyncGoogleCalendar) palette.reminder else palette.fgMuted,
                fontSizeSp = baseSp,
                boldLeft = true,
                boldRight = true
            )
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxLeftRightRow(
                left = if (settings.autoSyncGoogleCalendar) "[x] Auto-Sync Scheduled" else "[ ] Auto-Sync Scheduled",
                right = "[Toggle]",
                leftColor = palette.fg,
                rightColor = palette.project,
                fontSizeSp = baseSp,
                boldRight = true,
                underlineRight = true,
                onClick = {
                    onUpdateSettings(settings.copy(autoSyncGoogleCalendar = !settings.autoSyncGoogleCalendar))
                }
            )
            AsciiBoxLeftRightRow(
                left = "[~] Sync All Scheduled Tasks",
                right = "[Sync >]",
                leftColor = palette.contextTag,
                rightColor = palette.contextTag,
                fontSizeSp = baseSp,
                boldLeft = true,
                boldRight = true,
                underlineRight = true,
                onClick = { onSyncAllToGoogleCalendar() }
            )
            AsciiHorizontalBorder()
        }

        // =====================================================================
        // 4. [/] PLAIN TEXT STORAGE BOX (All options inside the box only)
        // =====================================================================
        Column(modifier = Modifier.fillMaxWidth()) {
            AsciiHorizontalBorder()
            AsciiBoxLeftRightRow(
                left = "[/] PLAIN TEXT STORAGE",
                right = "todo.txt",
                leftColor = palette.project,
                rightColor = palette.fgMuted,
                fontSizeSp = baseSp,
                boldLeft = true
            )
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxBodyRow(
                text = storageFolderPath,
                textColor = palette.fgMuted,
                fontSizeSp = (baseSp - 2).coerceAtLeast(12)
            )
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxActionRow(
                actions = listOf(
                    Triple("[/] Folder", palette.project) { onPickFolderWithSaf() },
                    Triple("[>] Share", palette.fg) { onOpenSavedFileLocation() },
                    Triple("[x] Archive", palette.fgMuted) { onArchiveCompletedTasks() }
                ),
                fontSizeSp = (baseSp - 1).coerceAtLeast(12)
            )
            AsciiHorizontalBorder()
        }
    }
}
