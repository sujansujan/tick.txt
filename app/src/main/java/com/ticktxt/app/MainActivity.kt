package com.ticktxt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.ui.screens.MainScreen
import com.ticktxt.app.ui.theme.ThemeMode
import com.ticktxt.app.ui.theme.TickTxtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sampleLines = """
            (A) 2026-11-01 ship quarterly invoice to client +work @office due:2026-11-01 duetime:17:00 rem:0m/a/svr
            2026-11-01 coffee with mum +family @home due:2026-11-03 duetime:14:00 rem:-15m/n
            (B) 2026-11-01 buy milk and sourdough bread +groceries @errands due:2026-11-02 rem:09:00/n
            2026-10-28 review architecture notes +work @desk note:architecture.md
            x 2026-11-01 2026-10-30 backup syncthing folder +ops @home
        """.trimIndent()
        val initialTasks = TodoParser.parseFile(sampleLines)

        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.DRACULA_DARK) }
            TickTxtTheme(themeMode = themeMode) {
                MainScreen(
                    initialTasks = initialTasks,
                    themeMode = themeMode,
                    onChangeTheme = { themeMode = it }
                )
            }
        }
    }
}

class TickApplication : android.app.Application()
