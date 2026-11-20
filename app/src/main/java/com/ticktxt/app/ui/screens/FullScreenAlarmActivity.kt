package com.ticktxt.app.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.reminders.AlarmBroadcastReceiver
import com.ticktxt.app.core.reminders.ReminderScheduler
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.ui.ascii.AsciiBoxActionRow
import com.ticktxt.app.ui.ascii.AsciiBoxAnnotatedBodyRow
import com.ticktxt.app.ui.ascii.AsciiHorizontalBorder
import com.ticktxt.app.ui.ascii.AsciiSnoozeWheelPopup
import com.ticktxt.app.ui.ascii.AsciiTitledTopBorder
import com.ticktxt.app.ui.ascii.PlainTextSportsAscii
import com.ticktxt.app.ui.ascii.buildSemanticTaskAnnotatedString
import com.ticktxt.app.ui.theme.AmoledPalette
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.TickTxtTheme
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

object AlarmAudioController {
    private var notifRingtone: Ringtone? = null

    fun playSystemNotificationBeepOnce(context: Context) {
        runCatching {
            notifRingtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            notifRingtone = RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
                play()
            }
        }
    }

    fun stopAll() {
        runCatching { notifRingtone?.stop() }
    }
}

class FullScreenAlarmActivity : ComponentActivity() {

    private var activeNotifId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCurrentlyVisible.set(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        renderFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isCurrentlyVisible.set(true)
        renderFromIntent(intent)
    }

    private fun renderFromIntent(currentIntent: Intent) {
        val repo = TickStorageRepository(this)
        val settings = repo.loadSettings()

        val reminderId = currentIntent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
            ?: currentIntent.getStringExtra("extra_reminder_id") ?: "alarm_id"
        val taskId = currentIntent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID)
            ?: currentIntent.getStringExtra("extra_task_id") ?: "task_id"
        val taskTitle = currentIntent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE)
            ?: currentIntent.getStringExtra("extra_task_title") ?: "scheduled task"
        val rawSpec = currentIntent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC)
            ?: currentIntent.getStringExtra("extra_raw_spec") ?: "0m/n"
        val notifId = currentIntent.getIntExtra("extra_notif_id", (reminderId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100))
        activeNotifId = notifId

        // Ensure the notification tray is empty while the Full-Screen Notification is open!
        cancelNotification(notifId)

        fun dismissFullScreenToTray() {
            AlarmAudioController.stopAll()
            AlarmBroadcastReceiver.postTrayNotificationOnDismiss(
                context = this,
                reminderId = reminderId,
                taskId = taskId,
                rawSpec = rawSpec,
                taskTitle = taskTitle,
                notifId = notifId
            )
            finish()
        }

        setContent {
            TickTxtTheme(
                themeMode = settings.themeMode,
                fontMode = settings.fontMode
            ) {
                BackHandler(enabled = true) {
                    dismissFullScreenToTray()
                }
                AsciiFullScreenAlarmSurface(
                    taskTitle = taskTitle,
                    rawSpec = rawSpec,
                    fontSizeSp = settings.fontSizeSp,
                    onSnoozeWithPreset = { presetSpec ->
                        AlarmAudioController.stopAll()
                        val snoozeIntent = Intent(this, AlarmBroadcastReceiver::class.java).apply {
                            action = ReminderScheduler.ACTION_SNOOZE_REMINDER
                            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                            putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                            putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                            putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                            putExtra(ReminderScheduler.EXTRA_REMINDER_TYPE, "n")
                            putExtra(ReminderScheduler.EXTRA_SNOOZE_PRESET, presetSpec)
                            putExtra("extra_notif_id", notifId)
                        }
                        sendBroadcast(snoozeIntent)
                        cancelNotification(notifId)
                        finish()
                    },
                    onDismiss = {
                        dismissFullScreenToTray()
                    },
                    onMarkDone = {
                        AlarmAudioController.stopAll()
                        val doneIntent = Intent(this, AlarmBroadcastReceiver::class.java).apply {
                            action = ReminderScheduler.ACTION_MARK_DONE
                            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                            putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                            putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                            putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                            putExtra("extra_notif_id", notifId)
                        }
                        sendBroadcast(doneIntent)
                        cancelNotification(notifId)
                        finish()
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isCurrentlyVisible.set(true)
        if (activeNotifId > 0) cancelNotification(activeNotifId)
    }

    override fun onResume() {
        super.onResume()
        isCurrentlyVisible.set(true)
        if (activeNotifId > 0) cancelNotification(activeNotifId)
    }

    override fun onStop() {
        isCurrentlyVisible.set(false)
        super.onStop()
    }

    private fun cancelNotification(notifId: Int) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }
    }

    override fun onDestroy() {
        isCurrentlyVisible.set(false)
        AlarmAudioController.stopAll()
        super.onDestroy()
    }

    companion object {
        /**
         * Tracks whether `FullScreenAlarmActivity` is currently visible on screen.
         * Used by `AlarmBroadcastReceiver` to verify lock-screen activity launch within 320ms.
         */
        val isCurrentlyVisible = AtomicBoolean(false)
    }
}

/**
 * Always-AMOLED (`#000000`) Burn-In-Protected Full-Screen Notification Surface:
 * - Always renders on a pure `#000000` pitch-black AMOLED canvas (`AmoledPalette`) regardless of app theme.
 * - Moves smoothly across 5 vertical screen zones every 2 minutes + subtle 8-point pixel drift
 *   every 45 seconds so no ASCII line or character stays on the same physical OLED subpixels.
 * - Highlights `(A)`/`(B)`/`(C)`, `+project`, `@context`, and `due:` tokens semantically.
 * - Shows the 3 primary actions: `[x Done]`  `[zZ Snooze]`  `[X Dismiss]`.
 */
@Composable
fun AsciiFullScreenAlarmSurface(
    taskTitle: String,
    rawSpec: String,
    fontSizeSp: Int = 15,
    onSnoozeWithPreset: (String) -> Unit,
    onDismiss: () -> Unit,
    onMarkDone: () -> Unit
) {
    CompositionLocalProvider(LocalTickPalette provides AmoledPalette) {
        val palette = LocalTickPalette.current
        var showSnoozeWheelPopup by remember { mutableStateOf(false) }
        var currentNow by remember { mutableStateOf(ZonedDateTime.now()) }
        var elapsedSeconds by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(1000L)
                currentNow = ZonedDateTime.now()
                elapsedSeconds += 1
            }
        }

        val verticalZoneBiases = remember { floatArrayOf(0.0f, -0.42f, 0.42f, -0.74f, 0.74f) }
        val driftOffsetsX = remember { intArrayOf(0, -6, 5, -4, 6, -5, 4, -3) }
        val driftOffsetsY = remember { intArrayOf(0, -7, 6, -5, 8, -6, 5, -4) }

        val orbitZoneIdx = (elapsedSeconds / 120) % verticalZoneBiases.size
        val pixelShiftIdx = (elapsedSeconds / 45) % driftOffsetsX.size

        val animatedVerticalBias by animateFloatAsState(
            targetValue = verticalZoneBiases[orbitZoneIdx],
            animationSpec = tween(durationMillis = 1800),
            label = "amoledVerticalBias"
        )
        val animatedDx by animateDpAsState(
            targetValue = driftOffsetsX[pixelShiftIdx].dp,
            animationSpec = tween(durationMillis = 1800),
            label = "amoledDriftX"
        )
        val animatedDy by animateDpAsState(
            targetValue = driftOffsetsY[pixelShiftIdx].dp,
            animationSpec = tween(durationMillis = 1800),
            label = "amoledDriftY"
        )

        val clockFormatted = remember(currentNow) {
            currentNow.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        val cleanSpec = remember(rawSpec) { rawSpec.substringBefore("/") }
        val semanticTaskText = remember(taskTitle, palette) {
            buildSemanticTaskAnnotatedString(taskTitle, palette, boldBase = true)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(BiasAlignment(0f, animatedVerticalBias))
                    .offset(x = animatedDx, y = animatedDy)
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = PlainTextSportsAscii.ALARM_BELL_ART,
                    color = palette.priorityB.copy(alpha = 0.88f),
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                ) {
                    AsciiTitledTopBorder(
                        badgeText = "! NOTIFICATION $clockFormatted",
                        badgeColor = palette.priorityA,
                        rightBadgeText = "rem:$cleanSpec",
                        rightBadgeColor = palette.reminder
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AsciiBoxAnnotatedBodyRow(
                        annotatedText = semanticTaskText,
                        fontSizeSp = fontSizeSp + 2
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AsciiHorizontalBorder(corner = "|")
                    AsciiBoxActionRow(
                        actions = listOf(
                            Triple("[x Done]", palette.reminder) { onMarkDone() },
                            Triple("[zZ Snooze]", palette.project) { showSnoozeWheelPopup = true },
                            Triple("[X Dismiss]", palette.fgMuted) { onDismiss() }
                        ),
                        fontSizeSp = fontSizeSp
                    )
                    AsciiHorizontalBorder()
                }
            }

            if (showSnoozeWheelPopup) {
                AsciiSnoozeWheelPopup(
                    taskTitle = taskTitle,
                    initialDate = LocalDate.now(),
                    initialTime = LocalTime.now().withSecond(0).withNano(0),
                    fontSizeSp = fontSizeSp,
                    isSnoozeMode = true,
                    confirmLabel = "[zZ Snooze]",
                    onDismiss = { showSnoozeWheelPopup = false },
                    onConfirmDateTime = { _, _, delayMs ->
                        showSnoozeWheelPopup = false
                        val totalSecs = (delayMs / 1000L).coerceAtLeast(1L)
                        val presetStr = if (totalSecs < 60L || totalSecs % 60L != 0L) {
                            "${totalSecs}s"
                        } else {
                            "${totalSecs / 60L}m"
                        }
                        onSnoozeWithPreset(presetStr)
                    }
                )
            }
        }
    }
}
