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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.reminders.AlarmBroadcastReceiver
import com.ticktxt.app.core.reminders.ReminderScheduleCalculator
import com.ticktxt.app.core.reminders.ReminderScheduler
import com.ticktxt.app.core.reminders.SnoozeUnit
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.TickTxtTheme
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Character-grid ASCII box builder modeled directly after https://plaintextsports.com/
 * Every line is padded to an exact fixed column width (`INNER_WIDTH = 36`, total width = 40 chars)
 * so `+--------------------------------------+` and `| ... |` align in a strict character grid
 * without any CSS/Compose borders or card backgrounds.
 */
object PlainTextSportsAscii {
    const val INNER_WIDTH = 36

    val TOP_BORDER: String = "+${"-".repeat(INNER_WIDTH + 2)}+"
    val MID_DIVIDER: String = "|${"-".repeat(INNER_WIDTH + 2)}|"
    val BOT_BORDER: String = TOP_BORDER

    fun row(text: String, width: Int = INNER_WIDTH): String {
        val clean = text.replace("\n", " ").trimEnd()
        if (clean.length <= width) {
            return "| ${clean.padEnd(width)} |"
        }
        return wrapRows(clean, width).joinToString("\n")
    }

    fun wrapRows(text: String, width: Int = INNER_WIDTH): List<String> {
        val words = text.trim().split(Regex("\\s+"))
        if (words.isEmpty() || words.first().isEmpty()) {
            return listOf("| ${"".padEnd(width)} |")
        }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            if (word.length > width) {
                if (current.isNotEmpty()) {
                    lines.add("| ${current.toString().padEnd(width)} |")
                    current = StringBuilder()
                }
                word.chunked(width).forEach { chunk ->
                    lines.add("| ${chunk.padEnd(width)} |")
                }
            } else if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= width) {
                current.append(' ').append(word)
            } else {
                lines.add("| ${current.toString().padEnd(width)} |")
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) {
            lines.add("| ${current.toString().padEnd(width)} |")
        }
        return lines
    }

    fun rowLeftRight(left: String, right: String, width: Int = INNER_WIDTH): String {
        val cleanRight = right.trim()
        val maxLeft = (width - cleanRight.length - 1).coerceAtLeast(1)
        val cleanLeft = left.trim().take(maxLeft)
        val spaces = (width - cleanLeft.length - cleanRight.length).coerceAtLeast(1)
        return "| $cleanLeft${" ".repeat(spaces)}$cleanRight |"
    }

    val ALARM_BELL_ART: String = listOf(
        "            .·´‾`·.            ",
        "           /   |   \\           ",
        "          |    |    |          ",
        "          |   / \\   |          ",
        "         /__––|––__\\         ",
        "              `v´              "
    ).joinToString("\n")

    val TROPHY_ART: String = listOf(
        "            .·´‾`·.            ",
        "           | \\   / |           ",
        "            `._|_.´            ",
        "              _|_              "
    ).joinToString("\n")
}

/**
 * Full-screen lock-screen & pop-up alarm in https://plaintextsports.com/ style:
 * - Pure character-grid `+--------------------------------------+` scorecard box
 * - `< -   5 min   + >` angle-bracket stepper and underlined text links
 * - Granular snooze across Seconds, Minutes, Hours, Days, Weeks, and Months.
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
    val monoFont = FontFamily.Monospace

    var selectedUnit by remember { mutableStateOf(SnoozeUnit.MINUTES) }
    var snoozeAmount by remember { mutableStateOf(5) }
    var isSoundMuted by remember { mutableStateOf(false) }
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top status line (PlainTextSports header style)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Alarm triggered: $clockFormatted",
                color = palette.fgMuted,
                fontSize = 12.sp,
                fontFamily = monoFont
            )
            Text(
                text = if (isSoundMuted) "Sound: Muted" else "Mute Sound",
                color = if (isSoundMuted) palette.fgMuted else palette.priorityB,
                fontSize = 13.sp,
                fontFamily = monoFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable {
                        isSoundMuted = true
                        onMuteSound()
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }

        // Centered ASCII Bell Art (like PlainTextSports championship art)
        Text(
            text = PlainTextSportsAscii.ALARM_BELL_ART,
            color = palette.priorityA,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontFamily = monoFont
        )

        // Task Scorecard Box (+--------------------------------------+)
        val taskBoxText = remember(taskTitle, rawSpec, clockFormatted) {
            buildString {
                appendLine(PlainTextSportsAscii.TOP_BORDER)
                appendLine(PlainTextSportsAscii.rowLeftRight("ALARM RINGING", "rem:$rawSpec"))
                appendLine(PlainTextSportsAscii.MID_DIVIDER)
                PlainTextSportsAscii.wrapRows(taskTitle).forEach { appendLine(it) }
                append(PlainTextSportsAscii.BOT_BORDER)
            }
        }
        Text(
            text = taskBoxText,
            color = palette.fg,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontFamily = monoFont
        )

        // Primary Underlined Action Links (Mark Completed  |  Dismiss Alarm)
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[x] Mark Completed",
                color = palette.reminder,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = monoFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onMarkDone() }
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            )
            Text(
                text = "Dismiss Alarm",
                color = palette.fgMuted,
                fontSize = 16.sp,
                fontFamily = monoFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            )
        }

        // Granular Snooze Section (PlainTextSports < -  5 min  + > stepper & underlined unit links)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Snooze Task",
                color = palette.fg,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                fontFamily = monoFont
            )

            // Unit Links: Units: sec  min  hr  day  wk  mo
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Units:",
                    color = palette.fgMuted,
                    fontSize = 13.sp,
                    fontFamily = monoFont
                )
                SnoozeUnit.entries.forEach { unit ->
                    val isSel = selectedUnit == unit
                    Text(
                        text = unit.asciiLabel.lowercase(),
                        color = if (isSel) palette.project else palette.fg,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                selectedUnit = unit
                                if (snoozeAmount !in unit.quickValues) {
                                    snoozeAmount = unit.quickValues.first()
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 10.dp)
                    )
                }
            }

            // Angle-bracket stepper: < - 1      5 minutes      + 1 >
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "< - 1",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { snoozeAmount = (snoozeAmount - 1).coerceAtLeast(1) }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                )

                Text(
                    text = "$snoozeAmount ${selectedUnit.fullName.lowercase()}",
                    color = palette.fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    fontFamily = monoFont
                )

                Text(
                    text = "+ 1 >",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { snoozeAmount = (snoozeAmount + 1).coerceAtMost(365) }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }

            // Quick values for current unit
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedUnit.quickValues.forEach { qv ->
                    val isSel = snoozeAmount == qv
                    Text(
                        text = "+$qv${selectedUnit.code}",
                        color = if (isSel) palette.priorityB else palette.fgMuted,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { snoozeAmount = qv }
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                    )
                }
            }

            // Snooze Scorecard Box (Tappable to confirm custom snooze)
            val snoozeBoxText = remember(snoozeAmount, selectedUnit, previewFormatted) {
                buildString {
                    appendLine(PlainTextSportsAscii.TOP_BORDER)
                    appendLine(
                        PlainTextSportsAscii.rowLeftRight(
                            "Snooze +$snoozeAmount ${selectedUnit.asciiLabel.lowercase()}",
                            "Apply >"
                        )
                    )
                    appendLine(PlainTextSportsAscii.row("Until: $previewFormatted"))
                    append(PlainTextSportsAscii.BOT_BORDER)
                }
            }
            Text(
                text = snoozeBoxText,
                color = palette.reminder,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = monoFont,
                modifier = Modifier
                    .clickable { onSnoozeWithPreset(selectedUnit.toPresetSpec(snoozeAmount)) }
                    .padding(vertical = 4.dp)
            )

            // 1-Tap Cross-Unit Underlined Links
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick:",
                    color = palette.fgMuted,
                    fontSize = 13.sp,
                    fontFamily = monoFont
                )
                listOf("10s", "30s", "1m", "5m", "15m", "1h", "1d", "1w", "1mo").forEach { spec ->
                    Text(
                        text = "+$spec",
                        color = palette.contextTag,
                        fontSize = 13.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { onSnoozeWithPreset(spec) }
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
