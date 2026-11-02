package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
    onPickFolderWithSaf: () -> Unit,
    onOpenSavedFileLocation: () -> Unit,
    rulesText: String,
    onSaveRules: (String) -> Unit,
    onFireImmediateNotification: () -> Unit,
    onTriggerTestAlarm: () -> Unit,
    onScheduleQuickTestIn1Min: () -> Unit
) {
    val palette = LocalTickPalette.current
    val fontFamily = LocalTickFontFamily.current
    var draftRules by remember(rulesText) { mutableStateOf(rulesText) }
    var showFileInspector by remember { mutableStateOf(true) }

    var customGranularNum by remember { mutableStateOf("1.5") }
    var customGranularUnit by remember { mutableStateOf("h") }
    var customGranularType by remember { mutableStateOf("n") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. APPEARANCE, SYSTEM FONT & TYPOGRAPHY (PERSISTED)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "1. appearance, font family & size (persisted)",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = fontFamily
            )
            Text(text = "theme:", color = palette.fgMuted, fontSize = 11.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(
                    ThemeMode.SYSTEM to "system",
                    ThemeMode.DRACULA_DARK to "dracula (dark)",
                    ThemeMode.ALUCARD_LIGHT to "alucard (light)"
                ).forEach { (mode, label) ->
                    val selected = settings.themeMode == mode
                    Text(
                        text = if (selected) "[$label]" else label,
                        color = if (selected) palette.reminder else palette.fgMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable {
                            onUpdateSettings(settings.copy(themeMode = mode))
                        }
                    )
                }
            }

            Text(text = "font family:", color = palette.fgMuted, fontSize = 11.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FontMode.values().forEach { fMode ->
                    val selected = settings.fontMode == fMode
                    Text(
                        text = if (selected) "[${fMode.label}]" else fMode.label,
                        color = if (selected) palette.reminder else palette.fgMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp,
                        fontFamily = fMode.toComposeFontFamily(),
                        modifier = Modifier.clickable {
                            onUpdateSettings(settings.copy(fontMode = fMode))
                        }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "font size:", color = palette.fgMuted, fontSize = 12.sp, fontFamily = fontFamily)
                listOf(12, 13, 15, 17).forEach { sz ->
                    val selected = settings.fontSizeSp == sz
                    Text(
                        text = if (selected) "[${sz}sp]" else "${sz}sp",
                        color = if (selected) palette.reminder else palette.fgMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable {
                            onUpdateSettings(settings.copy(fontSizeSp = sz))
                        }
                    )
                }
            }
        }

        // 2. SAVED FILE LOCATION & ACTIVE FILES INSPECTOR
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "2. saved file location & active plain-text files",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "active folder: $storageFolderPath",
                color = palette.fg,
                fontSize = 11.sp,
                fontFamily = fontFamily
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "[open / share saved todo.txt]",
                    color = palette.reminder,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.clickable { onOpenSavedFileLocation() }
                )
                Text(
                    text = "[pick folder (android saf)]",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.clickable { onPickFolderWithSaf() }
                )
                Text(
                    text = if (showFileInspector) "[hide file paths]" else "[show file paths]",
                    color = palette.fgMuted,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.clickable { showFileInspector = !showFileInspector }
                )
            }
            if (showFileInspector) {
                val filesList = listOf(
                    File(baseDir, "todo.txt"),
                    File(baseDir, "done.txt"),
                    File(baseDir, "tick.rules.txt"),
                    File(baseDir, "notes")
                )
                filesList.forEach { f ->
                    val status = if (f.exists()) {
                        if (f.isDirectory) "dir (${f.listFiles()?.size ?: 0} files)" else "${f.length()} bytes"
                    } else "created on save"
                    Text(
                        text = "  • ${f.name} -> ${f.absolutePath} [$status]",
                        color = palette.fgMuted,
                        fontSize = 10.sp,
                        fontFamily = fontFamily
                    )
                }
            }
        }

        // 3. GRANULAR REMINDER SETTINGS & NOTIFICATION TEST
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "3. granular reminder defaults & notification integration",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "when you type 'do something in 1m' or 'in 1 hr' or 'in 1.5 hrs', tick.txt sets due:, duetime:, and rem:0m/n so Android fires an exact notification at that time.",
                color = palette.fgMuted,
                fontSize = 11.sp,
                fontFamily = fontFamily
            )

            Text(text = "default timed reminder offset:", color = palette.fg, fontSize = 11.sp, fontFamily = fontFamily)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("0m/n", "-1m/n", "-5m/n", "-15m/n", "-30m/n", "-1h/n", "-1.5h/n", "-2h/n", "-1d/n", "0m/a").forEach { preset ->
                    val sel = settings.defaultTimedReminder == preset
                    Text(
                        text = if (sel) "[$preset]" else preset,
                        color = if (sel) palette.reminder else palette.fgMuted,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable {
                            onUpdateSettings(settings.copy(defaultTimedReminder = preset))
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "custom default:", color = palette.fgMuted, fontSize = 11.sp, fontFamily = fontFamily)
                BasicTextField(
                    value = customGranularNum,
                    onValueChange = { customGranularNum = it },
                    textStyle = TextStyle(fontFamily = fontFamily, color = palette.fg, fontSize = 11.sp),
                    cursorBrush = SolidColor(palette.project),
                    modifier = Modifier
                        .width(48.dp)
                        .background(palette.bg)
                        .border(1.dp, palette.border)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                listOf("m", "h", "d", "w", "mo").forEach { u ->
                    val sel = customGranularUnit == u
                    Text(
                        text = if (sel) "[$u]" else u,
                        color = if (sel) palette.project else palette.fgMuted,
                        fontSize = 11.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable { customGranularUnit = u }
                    )
                }
                listOf("n" to "/n", "a" to "/a").forEach { (code, label) ->
                    val sel = customGranularType == code
                    Text(
                        text = if (sel) "[$label]" else label,
                        color = if (sel) palette.priorityA else palette.fgMuted,
                        fontSize = 11.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable { customGranularType = code }
                    )
                }
                Text(
                    text = "[save default]",
                    color = palette.reminder,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.clickable {
                        val spec = "-${customGranularNum.trim()}${customGranularUnit}/$customGranularType"
                        onUpdateSettings(settings.copy(defaultTimedReminder = spec))
                    }
                )
            }

            // Live Notification & Alarm Test Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "[send test notification now]",
                    color = palette.reminder,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.clickable { onFireImmediateNotification() }
                )
                Text(
                    text = "[schedule reminder in 1m]",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.clickable { onScheduleQuickTestIn1Min() }
                )
                Text(
                    text = "[test full-screen alarm (/a)]",
                    color = palette.priorityA,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.clickable { onTriggerTestAlarm() }
                )
            }
        }

        // 4. TICK.RULES.TXT EDITOR
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "4. tick.rules.txt (persisted on disk)",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
                Text(
                    text = "[save rules]",
                    color = palette.reminder,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.clickable { onSaveRules(draftRules) }
                )
            }
            BasicTextField(
                value = draftRules,
                onValueChange = { draftRules = it },
                textStyle = TextStyle(
                    fontFamily = fontFamily,
                    color = palette.fg,
                    fontSize = 11.sp
                ),
                cursorBrush = SolidColor(palette.project),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(palette.bg)
                    .border(1.dp, palette.border)
                    .padding(8.dp)
            )
        }
    }
}
