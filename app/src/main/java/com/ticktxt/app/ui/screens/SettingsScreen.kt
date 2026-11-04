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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.ui.theme.FontMode
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode
import java.io.File

private fun formatReadableStoragePath(rawPath: String): String {
    val decoded = runCatching { Uri.decode(rawPath) }.getOrDefault(rawPath)
    return when {
        decoded.contains("/tree/") -> "SAF: " + decoded.substringAfter("/tree/")
        else -> decoded
    }
}

/**
 * Compact Settings screen with all options strictly inside the full-width ASCII boxes:
 * - System theme as default (`System | Dark | AMOLED | Light`)
 * - Pure Notification reminders (`! REMINDERS` with clean lead-time presets `0m | -5m | -15m | -30m | -1h`)
 * - Single-line human-readable SAF storage path + `[/ Folder]`, `[↺ Reload]`, `[> Share]`,
 *   `[✓ Archive -> done.txt]`, and `[↺ Restore Backup]`.
 */
@Composable
fun SettingsScreen(
    baseDir: File,
    storageFolderPath: String,
    settings: PersistedSettings,
    backupCount: Int = 0,
    completedCount: Int = 0,
    onUpdateSettings: (PersistedSettings) -> Unit,
    onSyncAllToGoogleCalendar: () -> Unit,
    onPickFolderWithSaf: () -> Unit,
    onReloadFromFolder: () -> Unit = {},
    onOpenSavedFileLocation: () -> Unit,
    onArchiveCompletedTasks: () -> Unit,
    onRestoreLatestBackup: () -> Unit = {},
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
                    ThemeMode.SYSTEM to "System",
                    ThemeMode.DRACULA_DARK to "Dark",
                    ThemeMode.AMOLED_BLACK to "AMOLED",
                    ThemeMode.ALUCARD_LIGHT to "Light"
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

        // 2. +--[! REMINDERS]------------------------------------------+
        Column(modifier = Modifier.fillMaxWidth()) {
            val normalizedOffset = settings.defaultTimedReminder.substringBefore("/")
            val selectedRemValue = "$normalizedOffset/n"
            AsciiTitledTopBorder(
                badgeText = "! REMINDERS",
                badgeColor = palette.priorityB,
                rightBadgeText = "rem:$normalizedOffset",
                rightBadgeColor = palette.reminder
            )
            AsciiBoxOptionRow(
                label = "Default",
                options = listOf(
                    "0m/n" to "0m",
                    "-5m/n" to "-5m",
                    "-15m/n" to "-15m",
                    "-30m/n" to "-30m",
                    "-1h/n" to "-1h"
                ),
                selectedValue = selectedRemValue,
                onSelect = { onUpdateSettings(settings.copy(defaultTimedReminder = it)) },
                activeColor = palette.reminder,
                fontSizeSp = baseSp
            )
            AsciiBoxActionRow(
                actions = listOf(
                    Triple<String, Color, () -> Unit>("[! Overlay]", palette.project) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        Unit
                    },
                    Triple<String, Color, () -> Unit>("[! Test Notification]", palette.priorityA) {
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
                    Triple<String, Color, () -> Unit>(
                        if (settings.autoSyncGoogleCalendar) "[x] Auto-Sync" else "[ ] Auto-Sync",
                        if (settings.autoSyncGoogleCalendar) palette.reminder else palette.fgMuted
                    ) {
                        onUpdateSettings(settings.copy(autoSyncGoogleCalendar = !settings.autoSyncGoogleCalendar))
                    },
                    Triple<String, Color, () -> Unit>("[# GCal Sync]", palette.contextTag) {
                        onSyncAllToGoogleCalendar()
                    }
                ),
                fontSizeSp = baseSp
            )
            AsciiHorizontalBorder()
        }

        // 4. +--[/ PLAIN TEXT STORAGE & BACKUPS]-----------------------+
        Column(modifier = Modifier.fillMaxWidth()) {
            val readablePath = remember(storageFolderPath) {
                formatReadableStoragePath(storageFolderPath)
            }
            AsciiTitledTopBorder(
                badgeText = "/ PLAIN TEXT STORAGE",
                badgeColor = palette.project,
                rightBadgeText = "bak:$backupCount"
            )
            AsciiBoxBodyRow(
                text = readablePath,
                textColor = palette.fgMuted,
                fontSizeSp = (baseSp - 2).coerceAtLeast(11),
                singleLine = true
            )
            AsciiBoxActionRow(
                actions = listOf(
                    Triple<String, Color, () -> Unit>("[/ Folder]", palette.project) { onPickFolderWithSaf() },
                    Triple<String, Color, () -> Unit>("[↺ Reload]", palette.contextTag) { onReloadFromFolder() },
                    Triple<String, Color, () -> Unit>("[> Share]", palette.fg) { onOpenSavedFileLocation() }
                ),
                fontSizeSp = (baseSp - 1).coerceAtLeast(12)
            )
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxActionRow(
                actions = listOf(
                    Triple<String, Color, () -> Unit>(
                        if (completedCount > 0) "[✓ Archive ($completedCount) -> done.txt]" else "[✓ Archive -> done.txt]",
                        if (completedCount > 0) palette.reminder else palette.fgMuted
                    ) { onArchiveCompletedTasks() },
                    Triple<String, Color, () -> Unit>(
                        if (backupCount > 0) "[↺ Restore Backup ($backupCount)]" else "[↺ No Backups]",
                        if (backupCount > 0) palette.priorityB else palette.fgMuted
                    ) { onRestoreLatestBackup() }
                ),
                fontSizeSp = (baseSp - 2).coerceAtLeast(11)
            )
            AsciiHorizontalBorder()
        }
    }
}
