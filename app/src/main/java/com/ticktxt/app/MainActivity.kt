package com.ticktxt.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.ticktxt.app.core.reminders.AlarmBroadcastReceiver
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.ui.screens.MainScreen
import com.ticktxt.app.ui.theme.TickTxtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AlarmBroadcastReceiver.ensureChannel(this)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        val storageRepo = TickStorageRepository(applicationContext)
        storageRepo.ensureInitialized()

        setContent {
            var persistedSettings by remember { mutableStateOf(storageRepo.loadSettings()) }

            TickTxtTheme(
                themeMode = persistedSettings.themeMode,
                fontMode = persistedSettings.fontMode
            ) {
                MainScreen(
                    storageRepo = storageRepo,
                    settings = persistedSettings,
                    onUpdateSettings = { updated ->
                        persistedSettings = updated
                        storageRepo.saveSettings(updated)
                    }
                )
            }
        }
    }
}

class TickApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmBroadcastReceiver.ensureChannel(this)
    }
}
