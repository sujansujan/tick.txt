package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    storageFolderPath: String,
    onChangeFolder: (String) -> Unit,
    themeMode: ThemeMode,
    onChangeTheme: (ThemeMode) -> Unit,
    fontSizeSp: Int,
    onChangeFontSize: (Int) -> Unit,
    rulesText: String,
    onSaveRules: (String) -> Unit,
    onTriggerTestAlarm: () -> Unit
) {
    val palette = LocalTickPalette.current
    var draftRules by remember(rulesText) { mutableStateOf(rulesText) }
    var draftFolder by remember(storageFolderPath) { mutableStateOf(storageFolderPath) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "theme palette",
            color = palette.project,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                ThemeMode.SYSTEM to "system",
                ThemeMode.DRACULA_DARK to "dracula (dark)",
                ThemeMode.ALUCARD_LIGHT to "alucard (light)"
            ).forEach { (mode, label) ->
                val selected = themeMode == mode
                Text(
                    text = if (selected) "[$label]" else label,
                    color = if (selected) palette.reminder else palette.fgMuted,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onChangeTheme(mode) }
                )
            }
        }

        Text(
            text = "monospace font size",
            color = palette.project,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(12, 13, 15).forEach { sz ->
                val selected = fontSizeSp == sz
                Text(
                    text = if (selected) "[${sz}sp]" else "${sz}sp",
                    color = if (selected) palette.reminder else palette.fgMuted,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onChangeFontSize(sz) }
                )
            }
        }

        Text(
            text = "plain-text storage folder",
            color = palette.project,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        BasicTextField(
            value = draftFolder,
            onValueChange = {
                draftFolder = it
                onChangeFolder(it)
            },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                color = palette.fg,
                fontSize = 12.sp
            ),
            cursorBrush = SolidColor(palette.project),
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(8.dp)
        )

        Text(
            text = "reminder diagnostics & test",
            color = palette.project,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "[test full-screen alarm (/a)]",
                color = palette.priorityA,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onTriggerTestAlarm() }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "tick.rules.txt (smart-add rules)",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                text = "[save rules]",
                color = palette.reminder,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onSaveRules(draftRules) }
            )
        }
        BasicTextField(
            value = draftRules,
            onValueChange = { draftRules = it },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                color = palette.fg,
                fontSize = 11.sp
            ),
            cursorBrush = SolidColor(palette.project),
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(8.dp)
        )

        Text(
            text = "about: tick.txt v1.0.0 // offline plain-text todo.txt + notes + exact reminders",
            color = palette.fgMuted,
            fontSize = 11.sp
        )
    }
}
