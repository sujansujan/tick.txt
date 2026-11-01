package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onChangeFontSize: (Int) -> Unit
) {
    val palette = LocalTickPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "storage folder",
            color = palette.project,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Text(
            text = "path: $storageFolderPath",
            color = palette.fg,
            fontSize = 12.sp
        )

        Text(
            text = "theme",
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
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onChangeTheme(mode) }
                )
            }
        }

        Text(
            text = "typography & density",
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
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onChangeFontSize(sz) }
                )
            }
        }

        Text(
            text = "about: tick.txt v1.0.0 (offline plain-text todo.txt + notes + reminders)",
            color = palette.fgMuted,
            fontSize = 11.sp
        )
    }
}
