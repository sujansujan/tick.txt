package com.ticktxt.app.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.reminders.AlarmBroadcastReceiver
import com.ticktxt.app.core.reminders.ReminderScheduleCalculator
import com.ticktxt.app.core.reminders.ReminderScheduler
import com.ticktxt.app.core.reminders.SnoozeUnit
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.TickTxtTheme
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Full-screen lock-screen & pop-up plain-text ASCII Alarm Activity.
 * Uses pure typographic ASCII components (`[!]`, `├─`, `└─`, `(•)`, `[-]`, `[+]`)
 * with generous 48dp+ touch targets and zero visual clutter.
 */
class FullScreenAlarmActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
            ?: intent.getStringExtra("extra_reminder_id") ?: "alarm_id"
        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID)
            ?: intent.getStringExtra("extra_task_id") ?: "task_id"
        val taskTitle = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE)
            ?: intent.getStringExtra("extra_task_title") ?: "scheduled task"
        val rawSpec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC)
            ?: intent.getStringExtra("extra_raw_spec") ?: "0m/a"
        val remType = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_TYPE) ?: "a"
        val notifId = intent.getIntExtra("extra_notif_id", (reminderId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100))

        startAlarmSoundAndVibration()

        val repo = TickStorageRepository(this)
        val settings = repo.loadSettings()

        setContent {
            TickTxtTheme(
                themeMode = settings.themeMode,
                fontMode = settings.fontMode
            ) {
                AsciiFullScreenAlarmSurface(
                    taskTitle = taskTitle,
                    rawSpec = rawSpec,
                    fontSizeSp = settings.fontSizeSp,
                    onMuteSound = { stopAlarmSoundAndVibration() },
                    onSnoozeWithPreset = { presetSpec ->
                        stopAlarmSoundAndVibration()
                        val snoozeIntent = Intent(this, AlarmBroadcastReceiver::class.java).apply {
                            action = ReminderScheduler.ACTION_SNOOZE_REMINDER
                            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                            putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                            putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                            putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                            putExtra(ReminderScheduler.EXTRA_REMINDER_TYPE, remType)
                            putExtra(ReminderScheduler.EXTRA_SNOOZE_PRESET, presetSpec)
                        }
                        sendBroadcast(snoozeIntent)
                        cancelNotification(notifId)
                        finish()
                    },
                    onDismiss = {
                        stopAlarmSoundAndVibration()
                        val dismissIntent = Intent(this, AlarmBroadcastReceiver::class.java).apply {
                            action = ReminderScheduler.ACTION_DISMISS_REMINDER
                            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                            putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                            putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                            putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                        }
                        sendBroadcast(dismissIntent)
                        cancelNotification(notifId)
                        finish()
                    },
                    onMarkDone = {
                        stopAlarmSoundAndVibration()
                        val doneIntent = Intent(this, AlarmBroadcastReceiver::class.java).apply {
                            action = ReminderScheduler.ACTION_MARK_DONE
                            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                            putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                            putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                            putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                        }
                        sendBroadcast(doneIntent)
                        cancelNotification(notifId)
                        finish()
                    }
                )
            }
        }
    }

    private fun startAlarmSoundAndVibration() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                play()
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator = vib
            if (vib != null && vib.hasVibrator()) {
                val pattern = longArrayOf(0, 500, 300, 500, 300, 700)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, 0)
                }
            }
        }
    }

    private fun stopAlarmSoundAndVibration() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
    }

    private fun cancelNotification(notifId: Int) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }
    }

    override fun onDestroy() {
        stopAlarmSoundAndVibration()
        super.onDestroy()
    }
}

/**
 * Pure Plain-Text + ASCII Component Full-Screen Alarm Surface.
 * Supports granular snooze across Seconds (sec), Minutes (min), Hours (hr),
 * Days (day), Weeks (wk), and Months (mo).
 */
@Composable
fun AsciiFullScreenAlarmSurface(
    taskTitle: String,
    rawSpec: String,
    fontSizeSp: Int = 16,
    onMuteSound: () -> Unit = {},
    onSnoozeWithPreset: (String) -> Unit,
    onDismiss: () -> Unit,
    onMarkDone: () -> Unit
) {
    val palette = LocalTickPalette.current
    val fontFamily = LocalTickFontFamily.current

    var selectedUnit by remember { mutableStateOf(SnoozeUnit.MINUTES) }
    var snoozeAmount by remember { mutableStateOf(5) }
    var isSoundMuted by remember { mutableStateOf(false) }
    var blink by remember { mutableStateOf(true) }
    var currentNow by remember { mutableStateOf(ZonedDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(600L)
            blink = !blink
            currentNow = ZonedDateTime.now()
        }
    }

    val previewTarget = remember(currentNow, snoozeAmount, selectedUnit) {
        ReminderScheduleCalculator.computeGranularSnoozeTarget(
            currentNow,
            snoozeAmount.toDouble(),
            selectedUnit
        )
    }
    val previewFormatted = remember(previewTarget) {
        previewTarget.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
    val clockFormatted = remember(currentNow) {
        currentNow.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // =====================================================================
        // 1. TOP TELEMETRY BAR
        // =====================================================================
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (blink) "[!] ALARM RINGING // $clockFormatted" else "[ ] ALARM RINGING // $clockFormatted",
                    color = palette.priorityA,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )

                Box(
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .border(1.dp, palette.border)
                        .clickable {
                            isSoundMuted = true
                            onMuteSound()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSoundMuted) "[sound: muted]" else "[sound: on]",
                        color = if (isSoundMuted) palette.fgMuted else palette.priorityB,
                        fontSize = 13.sp,
                        fontFamily = fontFamily
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = palette.border)
        }

        // =====================================================================
        // 2. PLAIN-TEXT TASK TREE PAYLOAD
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "┌─ task due now",
                color = palette.fgMuted,
                fontSize = 13.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "│  $taskTitle",
                color = palette.fg,
                fontWeight = FontWeight.Bold,
                fontSize = (fontSizeSp + 6).sp,
                fontFamily = fontFamily
            )
            Text(
                text = "├─ spec : rem:$rawSpec",
                color = palette.reminder,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )
            Text(
                text = "└─ wake : RTC_WAKEUP + fullScreenIntent",
                color = palette.fgMuted,
                fontSize = 13.sp,
                fontFamily = fontFamily
            )
        }

        // =====================================================================
        // 3. GRANULAR ASCII SNOOZE SELECTOR (sec | min | hr | day | wk | mo)
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "┌─ snooze unit (sec / min / hr / day / wk / mo)",
                color = palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = fontFamily
            )

            // 6 Unit Radio Controls: (•) min  ( ) sec ...
            val allUnits = SnoozeUnit.entries
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                allUnits.chunked(3).forEach { rowUnits ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowUnits.forEach { unit ->
                            val isSelected = selectedUnit == unit
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .background(palette.bg)
                                    .border(1.dp, if (isSelected) palette.project else palette.border)
                                    .clickable {
                                        selectedUnit = unit
                                        if (snoozeAmount !in unit.quickValues) {
                                            snoozeAmount = unit.quickValues.first()
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isSelected) "(•) ${unit.asciiLabel.lowercase()}" else "( ) ${unit.asciiLabel.lowercase()}",
                                    color = if (isSelected) palette.project else palette.fgMuted,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }
                    }
                }
            }

            // ASCII Stepper: [ - ]   05 min   [ + ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 48.dp)
                        .background(palette.bg)
                        .border(1.dp, palette.border)
                        .clickable { snoozeAmount = (snoozeAmount - 1).coerceAtLeast(1) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[ - ]",
                        color = palette.fg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = fontFamily
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(palette.bg)
                        .border(1.dp, palette.border),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+$snoozeAmount ${selectedUnit.fullName.lowercase()}",
                        color = palette.reminder,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        fontFamily = fontFamily
                    )
                }

                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 48.dp)
                        .background(palette.bg)
                        .border(1.dp, palette.border)
                        .clickable { snoozeAmount = (snoozeAmount + 1).coerceAtMost(365) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[ + ]",
                        color = palette.fg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = fontFamily
                    )
                }
            }

            // Quick Values Row for Selected Unit
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                selectedUnit.quickValues.forEach { qVal ->
                    val activeVal = snoozeAmount == qVal
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .background(palette.bg)
                            .border(1.dp, if (activeVal) palette.priorityB else palette.border)
                            .clickable { snoozeAmount = qVal }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (activeVal) "[*$qVal${selectedUnit.code}]" else "[+$qVal${selectedUnit.code}]",
                            color = if (activeVal) palette.priorityB else palette.fgMuted,
                            fontWeight = if (activeVal) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }

            // Confirm Snooze Trigger
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(palette.bg)
                    .border(1.dp, palette.project)
                    .clickable {
                        onSnoozeWithPreset(selectedUnit.toPresetSpec(snoozeAmount))
                    }
                    .padding(vertical = 12.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "[ snooze +$snoozeAmount${selectedUnit.code} -> $previewFormatted ]",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                }
            }

            // 1-Tap Cross-Unit Presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("10s", "30s", "1m", "5m", "15m", "1h", "1d", "1w", "1mo").forEach { spec ->
                    Box(
                        modifier = Modifier
                            .heightIn(min = 42.dp)
                            .background(palette.bg)
                            .border(1.dp, palette.border)
                            .clickable { onSnoozeWithPreset(spec) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[+$spec]",
                            color = palette.contextTag,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // =====================================================================
        // 4. BOTTOM PRIMARY ACTIONS: [ dismiss ]   &   [ x mark done ]
        // =====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp)
                    .background(palette.bgElevated)
                    .border(1.dp, palette.border)
                    .clickable { onDismiss() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ dismiss alarm ]",
                    color = palette.fgMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp)
                    .background(palette.bgElevated)
                    .border(1.dp, palette.reminder)
                    .clickable { onMarkDone() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[x mark done]",
                    color = palette.reminder,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            }
        }
    }
}
