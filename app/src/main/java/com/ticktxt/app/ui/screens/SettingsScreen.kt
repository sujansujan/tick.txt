package com.ticktxt.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.ui.theme.FontMode
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode
import java.io.File

/**
 * Compact Settings screen with all options strictly inside the full-width ASCII boxes,
 * plus 1-tap overlay permission so alarms and notifications always pop up in front of other apps.
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
    val context = LocalContext.current
    val palette = LocalTickPalette.current
    val baseSp = settings.fontSizeSp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. +--[* APPEARANCE]-----------------------------------------+
        Column(modifier = Modifier.fillMaxWidth()) {
            AsciiTitledTopBorder(
                badgeText = "* APPEARANCE",
                badgeColor = palette.project,
                rightBadgeText = "${settings.fontSizeSp}sp"
            )
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

        // 2. +--[! REMINDERS & ALARM]----------------------------------+
        Column(modifier = Modifier.fillMaxWidth()) {
            val normalizedRem = settings.defaultTimedReminder
            AsciiTitledTopBorder(
                badgeText = "! REMINDERS & ALARM",
                badgeColor = palette.priorityB,
                rightBadgeText = "rem:$normalizedRem",
                rightBadgeColor = palette.reminder
            )
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
                label = "Default",
                options = listOf(
                    "0m/a" to "0m Alarm",
                    "0m/n" to "0m Notif",
                    "-5m/a" to "-5m",
                    "-15m/a" to "-15m",
                    "-30m/a" to "-30m",
                    "-1h/a" to "-1h"
                ),
                selectedValue = normalizedRem,
                onSelect = { onUpdateSettings(settings.copy(defaultTimedReminder = it)) },
                activeColor = palette.reminder,
                fontSizeSp = baseSp
            )
            AsciiBoxActionRow(
                actions = listOf(
                    Triple("[!] Pop-Up Over Apps", palette.project) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    Triple("[> Test Full Screen]", palette.priorityA) {
                        onFireImmediateNotification()
                    }
                ),
                fontSizeSp = (baseSp - 1).coerceAtLeast(12)
            )
            AsciiHorizontalBorder()
        }

        // 3. +--[# GOOGLE CALENDAR]------------------------------------+
        Column(modifier = Modifier.fillMaxWidth()) {
            AsciiTitledTopBorder(
                badgeText = "# GOOGLE CALENDAR",
                badgeColor = palette.contextTag,
                rightBadgeText = if (settings.autoSyncGoogleCalendar) "AUTO:ON" else "AUTO:OFF",
                rightBadgeColor = if (settings.autoSyncGoogleCalendar) palette.reminder else palette.fgMuted
            )
            AsciiBoxActionRow(
                actions = listOf(
                    Triple(
                        if (settings.autoSyncGoogleCalendar) "[x] Auto-Sync" else "[ ] Auto-Sync",
                        if (settings.autoSyncGoogleCalendar) palette.reminder else palette.fgMuted
                    ) {
                        onUpdateSettings(settings.copy(autoSyncGoogleCalendar = !settings.autoSyncGoogleCalendar))
                    },
                    Triple("[# Sync All Tasks >]", palette.contextTag) {
                        onSyncAllToGoogleCalendar()
                    }
                ),
                fontSizeSp = baseSp
            )
            AsciiHorizontalBorder()
        }

        // 4. +--[/ PLAIN TEXT STORAGE]---------------------------------+
        Column(modifier = Modifier.fillMaxWidth()) {
            AsciiTitledTopBorder(
                badgeText = "/ PLAIN TEXT STORAGE",
                badgeColor = palette.project,
                rightBadgeText = "todo.txt"
            )
            AsciiBoxBodyRow(
                text = storageFolderPath,
                textColor = palette.fgMuted,
                fontSizeSp = (baseSp - 2).coerceAtLeast(11)
            )
            AsciiBoxActionRow(
                actions = listOf(
                    Triple("[/ Folder]", palette.project) { onPickFolderWithSaf() },
                    Triple("[> Share]", palette.fg) { onOpenSavedFileLocation() },
                    Triple("[x Archive]", palette.fgMuted) { onArchiveCompletedTasks() }
                ),
                fontSizeSp = (baseSp - 1).coerceAtLeast(12)
            )
            AsciiHorizontalBorder()
        }
    }
}
