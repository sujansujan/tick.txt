package com.ticktxt.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ticktxt.app.core.reminders.AlarmBroadcastReceiver
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.ui.screens.MainScreen
import com.ticktxt.app.ui.theme.TickTxtTheme

class MainActivity : ComponentActivity() {

    private var incomingSharedTextState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AlarmBroadcastReceiver.ensureChannel(this)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        incomingSharedTextState.value = extractSharedText(intent)
        val storageRepo = TickStorageRepository(applicationContext)

        setContent {
            var persistedSettings by remember { mutableStateOf(storageRepo.loadSettings()) }
            val incomingSharedText by incomingSharedTextState

            TickTxtTheme(
                themeMode = persistedSettings.themeMode,
                fontMode = persistedSettings.fontMode
            ) {
                MainScreen(
                    storageRepo = storageRepo,
                    settings = persistedSettings,
                    initialSharedText = incomingSharedText,
                    onConsumeSharedText = { incomingSharedTextState.value = null },
                    onUpdateSettings = { updated ->
                        persistedSettings = updated
                        storageRepo.saveSettings(updated)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedText(intent)?.let { text ->
            incomingSharedTextState.value = text
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND,
            "com.google.android.gms.actions.CREATE_NOTE" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
                when {
                    text.isNotEmpty() && subject.isNotEmpty() && !text.startsWith(subject, ignoreCase = true) -> "$subject $text"
                    text.isNotEmpty() -> text
                    subject.isNotEmpty() -> subject
                    else -> null
                }
            }
            else -> null
        }
    }
}

class TickApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmBroadcastReceiver.ensureChannel(this)
    }
}
