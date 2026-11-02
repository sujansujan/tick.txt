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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
 * Native Compose Full-Width ASCII Box Components (`+---...---+` and `| ... |`).
 * Uses `Row(Modifier.fillMaxWidth())` + `Modifier.weight(1f)` + `TextOverflow.Clip` (`softWrap = false`)
 * so every box fits 100% of the screen width on ANY phone and NEVER wraps `+---+` or `|` onto a new line.
 */
@Composable
fun AsciiHorizontalBorder(
    corner: String = "+",
    fillChar: Char = '-',
    color: Color = LocalTickPalette.current.fgMuted
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = corner,
            color = color,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = fillChar.toString().repeat(240),
            color = color,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = corner,
            color = color,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
fun AsciiBoxLeftRightRow(
    left: String,
    right: String,
    leftColor: Color = LocalTickPalette.current.fg,
    rightColor: Color = LocalTickPalette.current.fgMuted,
    borderColor: Color = LocalTickPalette.current.fgMuted,
    fontSizeSp: Int = 14,
    boldLeft: Boolean = false,
    boldRight: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = left,
            color = leftColor,
            fontSize = fontSizeSp.sp,
            fontWeight = if (boldLeft) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (right.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = right,
                color = rightColor,
                fontSize = (fontSizeSp - 1).coerceAtLeast(11).sp,
                fontWeight = if (boldRight) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = " |",
            color = borderColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
fun AsciiBoxBodyRow(
    text: String,
    textColor: Color = LocalTickPalette.current.fg,
    borderColor: Color = LocalTickPalette.current.fgMuted,
    fontSizeSp: Int = 15,
    bold: Boolean = false,
    strikethrough: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = text,
            color = textColor,
            fontSize = fontSizeSp.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            textDecoration = if (strikethrough) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = " |",
            color = borderColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

object PlainTextSportsAscii {
    val ALARM_BELL_ART = listOf(
        ".·´‾`·.",
        "/   |   \\",
        "|    |    |",
        "|   / \\   |",
        "/__––|––__\\",
        "`v´"
    ).joinToString("\n")

    val TROPHY_ART = listOf(
        ".·´‾`·.",
        "| \\   / |",
        "`._|_.´",
        "_|_"
    ).joinToString("\n")
}

/**
 * Shared audio controller that enforces:
 * - When Alarm is Muted (default): loud alarm (`TYPE_ALARM`) is silent, and only the standard
 *   system notification sound (`TYPE_NOTIFICATION`) plays.
 * - When Alarm is Unmuted: system notification sound is immediately silenced, and the loud
 *   alarm (`TYPE_ALARM`) + vibration plays.
 */
object AlarmAudioController {
    private var loudAlarmRingtone: Ringtone? = null
    private var notifRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun applyAudioMode(context: Context, isAlarmMuted: Boolean, notifId: Int? = null) {
        if (isAlarmMuted) {
            stopLoudAlarmOnly()
        } else {
            stopNotificationSound(context, notifId)
            startLoudAlarm(context)
        }
    }

    fun playSystemNotificationBeepOnce(context: Context) {
        runCatching {
            stopLoudAlarmOnly()
            notifRingtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            notifRingtone = RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
                play()
            }
        }
    }

    private fun startLoudAlarm(context: Context) {
        runCatching {
            notifRingtone?.stop()
            loudAlarmRingtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            loudAlarmRingtone = RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
                play()
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
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

    private fun stopNotificationSound(context: Context, notifId: Int?) {
        runCatching { notifRingtone?.stop() }
        if (notifId != null) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)
            }
        }
    }

    private fun stopLoudAlarmOnly() {
        runCatching { loudAlarmRingtone?.stop() }
        runCatching { vibrator?.cancel() }
    }

    fun stopAll() {
        runCatching { loudAlarmRingtone?.stop() }
        runCatching { notifRingtone?.stop() }
        runCatching { vibrator?.cancel() }
    }
}

class FullScreenAlarmActivity : ComponentActivity() {

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

        val repo = TickStorageRepository(this)
        val settings = repo.loadSettings()

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
        val initialMuted = intent.getBooleanExtra("extra_alarm_muted", settings.alarmMutedByDefault)

        AlarmAudioController.applyAudioMode(this, initialMuted, notifId)

        setContent {
            TickTxtTheme(
                themeMode = settings.themeMode,
                fontMode = settings.fontMode
            ) {
                AsciiFullScreenAlarmSurface(
                    taskTitle = taskTitle,
                    rawSpec = rawSpec,
                    fontSizeSp = settings.fontSizeSp,
                    initialAlarmMuted = initialMuted,
                    onToggleAlarmMute = { mutedNow ->
                        if (mutedNow) {
                            AlarmAudioController.playSystemNotificationBeepOnce(this)
                        } else {
                            AlarmAudioController.applyAudioMode(this, isAlarmMuted = false, notifId = notifId)
                        }
                    },
                    onSnoozeWithPreset = { presetSpec ->
                        AlarmAudioController.stopAll()
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
                        AlarmAudioController.stopAll()
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
                        AlarmAudioController.stopAll()
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

    private fun cancelNotification(notifId: Int) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }
    }

    override fun onDestroy() {
        AlarmAudioController.stopAll()
        super.onDestroy()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsciiFullScreenAlarmSurface(
    taskTitle: String,
    rawSpec: String,
    fontSizeSp: Int = 16,
    initialAlarmMuted: Boolean = true,
    onToggleAlarmMute: (Boolean) -> Unit = {},
    onSnoozeWithPreset: (String) -> Unit,
    onDismiss: () -> Unit,
    onMarkDone: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current

    var selectedUnit by remember { mutableStateOf(SnoozeUnit.MINUTES) }
    var snoozeAmount by remember { mutableStateOf(5) }
    var isAlarmMuted by remember { mutableStateOf(initialAlarmMuted) }
    var currentNow by remember { mutableStateOf(ZonedDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
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
        previewTarget.format(DateTimeFormatter.ofPattern("MMM d, HH:mm:ss"))
    }
    val clockFormatted = remember(currentNow) {
        currentNow.format(DateTimeFormatter.ofPattern("h:mm:ss a"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top bar: Clock on left, Audio Mode toggle on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Triggered: $clockFormatted",
                color = palette.fgMuted,
                fontSize = (fontSizeSp - 2).sp,
                fontFamily = appFont
            )
            Text(
                text = if (isAlarmMuted) "Muted (Notif Sound) • Unmute" else "Loud Alarm (Notif Silenced) • Mute",
                color = if (isAlarmMuted) palette.reminder else palette.priorityA,
                fontWeight = FontWeight.Bold,
                fontSize = (fontSizeSp - 2).sp,
                fontFamily = appFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable {
                        val nextMuted = !isAlarmMuted
                        isAlarmMuted = nextMuted
                        onToggleAlarmMute(nextMuted)
                        if (nextMuted) {
                            AlarmAudioController.playSystemNotificationBeepOnce(context)
                        } else {
                            AlarmAudioController.applyAudioMode(context, isAlarmMuted = false)
                        }
                    }
                    .padding(vertical = 8.dp)
            )
        }

        // Centered ASCII Bell Art
        Text(
            text = PlainTextSportsAscii.ALARM_BELL_ART,
            color = if (isAlarmMuted) palette.priorityB else palette.priorityA,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // Native Full-Width Task Scorecard Box (Never wraps!)
        Column(modifier = Modifier.fillMaxWidth()) {
            AsciiHorizontalBorder()
            AsciiBoxLeftRightRow(
                left = "TASK ALARM",
                right = "rem:$rawSpec",
                leftColor = palette.priorityA,
                rightColor = palette.reminder,
                boldLeft = true
            )
            AsciiBoxLeftRightRow(
                left = "AUDIO",
                right = if (isAlarmMuted) "Muted (Notif Sound)" else "Loud (Notif Silenced)",
                leftColor = palette.fgMuted,
                rightColor = if (isAlarmMuted) palette.reminder else palette.priorityA
            )
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxBodyRow(
                text = taskTitle,
                textColor = palette.fg,
                fontSizeSp = fontSizeSp,
                bold = true
            )
            AsciiHorizontalBorder()
        }

        // Primary Action Links distributed cleanly
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[x] Mark Completed",
                color = palette.reminder,
                fontWeight = FontWeight.Bold,
                fontSize = fontSizeSp.sp,
                fontFamily = appFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onMarkDone() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Text(
                text = "Dismiss Alarm",
                color = palette.fgMuted,
                fontSize = fontSizeSp.sp,
                fontFamily = appFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }

        // Granular Snooze Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Snooze Task",
                color = palette.fg,
                fontWeight = FontWeight.Bold,
                fontSize = fontSizeSp.sp,
                fontFamily = appFont
            )

            // Wrapping Unit Links: sec  min  hr  day  wk  mo
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Units: ",
                    color = palette.fgMuted,
                    fontSize = (fontSizeSp - 1).sp,
                    fontFamily = appFont,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                )
                SnoozeUnit.entries.forEach { unit ->
                    val isSel = selectedUnit == unit
                    Text(
                        text = unit.asciiLabel.lowercase(),
                        color = if (isSel) palette.project else palette.fg,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = fontSizeSp.sp,
                        fontFamily = appFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                selectedUnit = unit
                                if (snoozeAmount !in unit.quickValues) {
                                    snoozeAmount = unit.quickValues.first()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            // PlainTextSports Angle-Bracket Stepper: < - 1     5 minutes     + 1 >
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "< - 1",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    fontFamily = appFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { snoozeAmount = (snoozeAmount - 1).coerceAtLeast(1) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                Text(
                    text = "$snoozeAmount ${selectedUnit.fullName.lowercase()}",
                    color = palette.fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    fontFamily = appFont
                )

                Text(
                    text = "+ 1 >",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    fontFamily = appFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { snoozeAmount = (snoozeAmount + 1).coerceAtMost(365) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Quick values for current unit
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                selectedUnit.quickValues.forEach { qv ->
                    val isSel = snoozeAmount == qv
                    Text(
                        text = "+$qv${selectedUnit.code}",
                        color = if (isSel) palette.priorityB else palette.fgMuted,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = (fontSizeSp - 1).sp,
                        fontFamily = appFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { snoozeAmount = qv }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Native Full-Width Snooze Scorecard Box (Never wraps!)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSnoozeWithPreset(selectedUnit.toPresetSpec(snoozeAmount)) }
                    .padding(vertical = 4.dp)
            ) {
                AsciiHorizontalBorder(color = palette.reminder)
                AsciiBoxLeftRightRow(
                    left = "Snooze +$snoozeAmount ${selectedUnit.asciiLabel.lowercase()}",
                    right = "Apply Snooze >",
                    leftColor = palette.reminder,
                    rightColor = palette.reminder,
                    borderColor = palette.reminder,
                    boldLeft = true,
                    boldRight = true
                )
                AsciiBoxLeftRightRow(
                    left = "Wake Time",
                    right = previewFormatted,
                    leftColor = palette.fgMuted,
                    rightColor = palette.fg,
                    borderColor = palette.reminder
                )
                AsciiHorizontalBorder(color = palette.reminder)
            }

            // 1-Tap Cross-Unit Underlined Links
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Quick: ",
                    color = palette.fgMuted,
                    fontSize = (fontSizeSp - 2).sp,
                    fontFamily = appFont,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                )
                listOf("10s", "30s", "1m", "5m", "15m", "1h", "1d", "1w", "1mo").forEach { spec ->
                    Text(
                        text = "+$spec",
                        color = palette.contextTag,
                        fontSize = (fontSizeSp - 1).sp,
                        fontFamily = appFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onSnoozeWithPreset(spec) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
