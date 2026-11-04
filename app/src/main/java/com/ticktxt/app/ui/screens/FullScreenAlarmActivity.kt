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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.reminders.AlarmBroadcastReceiver
import com.ticktxt.app.core.reminders.ReminderScheduleCalculator
import com.ticktxt.app.core.reminders.ReminderScheduler
import com.ticktxt.app.core.reminders.SnoozeUnit
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.ui.theme.AmoledPalette
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.TickSemanticPalette
import com.ticktxt.app.ui.theme.TickTxtTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

// Optimization #5: Pre-allocated 240-char border string so recompositions never allocate strings
private val DASH_FILL_240 = "-".repeat(240)

/**
 * Semantic syntax highlighter for task text on the AMOLED Full-Screen Notification
 * and task cards: highlights `(A)`/`(B)`/`(C)` priorities, `+project` tags, `@context` tags,
 * `due:`/`duetime:` dates, `rem:` specs, and `note:` links.
 */
fun buildSemanticTaskAnnotatedString(
    rawTitle: String,
    palette: TickSemanticPalette,
    boldBase: Boolean = true
): AnnotatedString {
    return buildAnnotatedString {
        val tokens = rawTitle.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        tokens.forEachIndexed { idx, token ->
            if (idx > 0) append(" ")
            when {
                token == "(A)" -> withStyle(SpanStyle(color = palette.priorityA, fontWeight = FontWeight.Bold)) { append(token) }
                token == "(B)" -> withStyle(SpanStyle(color = palette.priorityB, fontWeight = FontWeight.Bold)) { append(token) }
                token == "(C)" -> withStyle(SpanStyle(color = palette.priorityC, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("+") && token.length > 1 -> withStyle(SpanStyle(color = palette.project, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("@") && token.length > 1 -> withStyle(SpanStyle(color = palette.contextTag, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("due:", ignoreCase = true) || token.startsWith("duetime:", ignoreCase = true) ->
                    withStyle(SpanStyle(color = palette.priorityB, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("rem:", ignoreCase = true) ->
                    withStyle(SpanStyle(color = palette.reminder, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("note:", ignoreCase = true) ->
                    withStyle(SpanStyle(color = palette.noteLink, fontWeight = FontWeight.Bold)) { append(token) }
                else -> withStyle(
                    SpanStyle(
                        color = palette.fg,
                        fontWeight = if (boldBase) FontWeight.Bold else FontWeight.Normal
                    )
                ) { append(token) }
            }
        }
    }
}

/**
 * Uniform ASCII Design System Primitives (`+--[Title]---+`, `+---...---+`, `| ... |`).
 * Zero CSS/Compose `.border()` rectangles—100% character-grid borders that never wrap.
 */
@Composable
fun AsciiHorizontalBorder(
    corner: String = "+",
    fillChar: Char = '-',
    color: Color = LocalTickPalette.current.fgMuted
) {
    val fillText = remember(fillChar) {
        if (fillChar == '-') DASH_FILL_240 else fillChar.toString().repeat(240)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = corner,
            color = color,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = fillText,
            color = color,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = corner,
            color = color,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
fun AsciiTitledTopBorder(
    badgeText: String,
    borderColor: Color = LocalTickPalette.current.fgMuted,
    badgeColor: Color = LocalTickPalette.current.priorityB,
    rightBadgeText: String? = null,
    rightBadgeColor: Color = LocalTickPalette.current.fgMuted,
    onRightBadgeClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "+--[",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = badgeText,
            color = badgeColor,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = "]",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = DASH_FILL_240,
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f)
        )
        if (rightBadgeText != null) {
            Text(
                text = "[",
                color = borderColor,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1
            )
            Text(
                text = rightBadgeText,
                color = rightBadgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1,
                modifier = if (onRightBadgeClick != null) Modifier.clickable { onRightBadgeClick() } else Modifier
            )
            Text(
                text = "]--+",
                color = borderColor,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1
            )
        } else {
            Text(
                text = "+",
                color = borderColor,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1
            )
        }
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
    boldRight: Boolean = false,
    underlineRight: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = left,
            color = leftColor,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 2).sp,
            fontWeight = if (boldLeft) FontWeight.Bold else FontWeight.Normal,
            fontFamily = LocalTickFontFamily.current,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (right.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = right,
                color = rightColor,
                fontSize = (fontSizeSp - 1).coerceAtLeast(11).sp,
                lineHeight = (fontSizeSp + 1).sp,
                fontWeight = if (boldRight) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (underlineRight) TextDecoration.Underline else TextDecoration.None,
                fontFamily = LocalTickFontFamily.current,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = " |",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
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
    fontSizeSp: Int = 14,
    bold: Boolean = false,
    strikethrough: Boolean = false,
    singleLine: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = text,
            color = textColor,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 2).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = LocalTickFontFamily.current,
            textDecoration = if (strikethrough) TextDecoration.LineThrough else TextDecoration.None,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            softWrap = !singleLine,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = " |",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
fun AsciiBoxAnnotatedBodyRow(
    annotatedText: AnnotatedString,
    borderColor: Color = LocalTickPalette.current.fgMuted,
    fontSizeSp: Int = 14,
    singleLine: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = annotatedText,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 3).sp,
            fontFamily = LocalTickFontFamily.current,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            softWrap = !singleLine,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = " |",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> AsciiBoxOptionRow(
    label: String,
    options: List<Pair<T, String>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    activeColor: Color = LocalTickPalette.current.project,
    borderColor: Color = LocalTickPalette.current.fgMuted,
    fontSizeSp: Int = 13
) {
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (label.isNotEmpty()) {
                Text(
                    text = "$label:",
                    color = palette.fgMuted,
                    fontSize = (fontSizeSp - 1).sp,
                    fontFamily = appFont,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            options.forEach { (value, text) ->
                val isSelected = value == selectedValue
                Text(
                    text = if (isSelected) "[*$text*]" else text,
                    color = if (isSelected) activeColor else palette.fg,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = (fontSizeSp - 1).sp,
                    fontFamily = appFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { onSelect(value) }
                        .padding(vertical = 2.dp, horizontal = 2.dp)
                )
            }
        }
        Text(
            text = " |",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
fun AsciiBoxActionRow(
    actions: List<Triple<String, Color, () -> Unit>>,
    borderColor: Color = LocalTickPalette.current.fgMuted,
    fontSizeSp: Int = 13,
    underline: Boolean = false
) {
    val appFont = LocalTickFontFamily.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { (label, color, onClick) ->
                Text(
                    text = label,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = appFont,
                    textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
                    modifier = Modifier
                        .clickable { onClick() }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                )
            }
        }
        Text(
            text = " |",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

object PlainTextSportsAscii {
    val ALARM_BELL_ART = listOf(
        "   .---.   ",
        "  /  |  \\  ",
        " |   |   | ",
        " |  / \\  | ",
        " /--===--\\ ",
        "    'v'    "
    ).joinToString("\n")

    val TROPHY_ART = listOf(
        "  .---.  ",
        " | \\ / | ",
        "  '---'  ",
        "   _|_   "
    ).joinToString("\n")

    fun formatRelativeDelta(now: LocalDateTime, target: LocalDateTime): String {
        val dur = Duration.between(now, target)
        val totalSec = dur.seconds
        if (totalSec < -60) {
            val overdueMin = (-totalSec) / 60
            return if (overdueMin >= 1440) "-${overdueMin / 1440} d."
            else if (overdueMin >= 60) "-${overdueMin / 60} h."
            else "-${overdueMin} m."
        }
        if (totalSec <= 0) return "now"
        val days = totalSec / 86400
        val hours = (totalSec % 86400) / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return when {
            days > 0 && hours > 0 -> "${days} d. ${hours} h."
            days > 0 -> "${days} d."
            hours > 0 && mins > 0 -> "${hours} h. ${mins} m."
            hours > 0 -> "${hours} h."
            mins > 0 -> "${mins} m."
            else -> "${secs} s."
        }
    }
}

/**
 * Interactive 3-Column ASCII Wheel / Drum Picker Popup:
 * - When `isSnoozeMode = false` (used during Task Creation and Task Editing):
 *   Acts as a clean **Due Date & Time Wheel Picker** (`L PICK DUE DATE & TIME`) with no snooze options!
 * - When `isSnoozeMode = true` (used when tapping `[zZ Snooze]` inside the Full-Screen Notification):
 *   Shows the full Snooze controls (`From: Now | Reminder time`, `[zZ +10m]`, `[*Cal Wheel*] | [+Offset]`).
 */
@Composable
fun AsciiSnoozeWheelPopup(
    taskTitle: String,
    initialDate: LocalDate?,
    initialTime: LocalTime?,
    fontSizeSp: Int = 15,
    isSnoozeMode: Boolean = true,
    confirmLabel: String = if (isSnoozeMode) "[zZ Snooze]" else "[✓ Set Due]",
    onDismiss: () -> Unit,
    onConfirmDateTime: (LocalDate, LocalTime, Long) -> Unit
) {
    val palette = LocalTickPalette.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val monoFont = FontFamily.Monospace
    val nowDt = remember { LocalDateTime.now().withSecond(0).withNano(0) }

    var anchorToNow by remember { mutableStateOf(isSnoozeMode) }
    var wheelMode by remember { mutableStateOf("cal") } // "cal" = Date/Hour/Min wheel, "offset" = Amount/Unit wheel

    val baseDt = remember(anchorToNow, initialDate, initialTime, isSnoozeMode) {
        if (!isSnoozeMode) {
            LocalDateTime.of(
                initialDate ?: nowDt.toLocalDate(),
                initialTime ?: nowDt.plusHours(1).withMinute(0).toLocalTime()
            )
        } else if (!anchorToNow && initialDate != null) {
            LocalDateTime.of(initialDate, initialTime ?: LocalTime.of(9, 0))
        } else {
            nowDt.plusMinutes(10)
        }
    }

    // Calendar Wheel State
    var selectedDate by remember { mutableStateOf(baseDt.toLocalDate()) }
    var selectedHour by remember { mutableStateOf(baseDt.hour) }
    var selectedMinute by remember { mutableStateOf((baseDt.minute / 5) * 5) }

    // Granular Offset Wheel State (only used when isSnoozeMode == true and wheelMode == "offset")
    var offsetAmount by remember { mutableStateOf(10) }
    var offsetUnitIdx by remember { mutableStateOf(1) } // 0=sec, 1=min, 2=hr, 3=day, 4=wk, 5=mo
    val allUnits = SnoozeUnit.entries

    // Vertical drag accumulators & fling jobs for tactile momentum wheel rolling
    var dragAccumCol1 by remember { mutableStateOf(0f) }
    var dragAccumCol2 by remember { mutableStateOf(0f) }
    var dragAccumCol3 by remember { mutableStateOf(0f) }
    var flingJobCol1 by remember { mutableStateOf<Job?>(null) }
    var flingJobCol2 by remember { mutableStateOf<Job?>(null) }
    var flingJobCol3 by remember { mutableStateOf<Job?>(null) }

    fun stepCol1(delta: Int) {
        if (wheelMode == "cal") selectedDate = selectedDate.plusDays(delta.toLong())
        else offsetAmount = (offsetAmount + delta).coerceIn(1, 120)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun stepCol2(delta: Int) {
        if (wheelMode == "cal") selectedHour = (selectedHour + delta + 24) % 24
        else offsetUnitIdx = (offsetUnitIdx + delta + allUnits.size) % allUnits.size
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun stepCol3(delta: Int) {
        if (wheelMode == "cal") selectedMinute = (selectedMinute + delta * 5 + 60) % 60
        else offsetAmount = (offsetAmount + delta * 5).coerceIn(1, 120)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun launchMomentumFling(initialVelocityY: Float, onStep: (Int) -> Unit): Job {
        return coroutineScope.launch {
            var v = initialVelocityY.coerceIn(-4500f, 4500f)
            while (abs(v) > 280f) {
                onStep(if (v < 0f) 1 else -1)
                v *= 0.72f
                delay(36L)
            }
        }
    }

    val targetDateTime = remember(wheelMode, selectedDate, selectedHour, selectedMinute, offsetAmount, offsetUnitIdx, anchorToNow) {
        if (wheelMode == "cal") {
            LocalDateTime.of(selectedDate, LocalTime.of(selectedHour, selectedMinute))
        } else {
            val anchorZdt = if (!anchorToNow && initialDate != null) {
                ZonedDateTime.of(initialDate, initialTime ?: LocalTime.of(9, 0), ZonedDateTime.now().zone)
            } else {
                ZonedDateTime.now()
            }
            ReminderScheduleCalculator.computeGranularSnoozeTarget(
                anchorZdt,
                offsetAmount.toDouble(),
                allUnits[offsetUnitIdx]
            ).toLocalDateTime()
        }
    }

    val deltaLabel = remember(targetDateTime) {
        PlainTextSportsAscii.formatRelativeDelta(LocalDateTime.now(), targetDateTime)
    }

    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH) }
    val headerFmt = remember { DateTimeFormatter.ofPattern("EEE, d MMM • HH:mm", Locale.ENGLISH) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(10.dp)
        ) {
            // 1. Header Box: Due Date Picker vs Snooze Picker
            AsciiTitledTopBorder(
                badgeText = if (isSnoozeMode) "zZ SNOOZE REMINDER" else "L PICK DUE DATE & TIME",
                badgeColor = palette.project,
                rightBadgeText = deltaLabel,
                rightBadgeColor = palette.reminder
            )
            AsciiBoxAnnotatedBodyRow(
                annotatedText = buildSemanticTaskAnnotatedString(taskTitle, palette, boldBase = true),
                fontSizeSp = fontSizeSp,
                singleLine = true
            )
            AsciiBoxLeftRightRow(
                left = "${if (isSnoozeMode) "Snooze until" else "Due"}: ${targetDateTime.format(headerFmt)}",
                right = deltaLabel,
                leftColor = palette.fgMuted,
                rightColor = palette.priorityB,
                fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12),
                boldRight = true
            )
            AsciiHorizontalBorder(corner = "|")

            // 2. Snooze-only controls (Hidden during task creation / due-date picking!)
            if (isSnoozeMode) {
                AsciiBoxOptionRow(
                    label = "From",
                    options = listOf(
                        true to "Now",
                        false to "Reminder time"
                    ),
                    selectedValue = anchorToNow,
                    onSelect = { useNow ->
                        anchorToNow = useNow
                        val anchor = if (useNow) LocalDateTime.now().plusMinutes(10)
                        else LocalDateTime.of(initialDate ?: LocalDate.now(), initialTime ?: LocalTime.of(9, 0)).plusMinutes(10)
                        selectedDate = anchor.toLocalDate()
                        selectedHour = anchor.hour
                        selectedMinute = (anchor.minute / 5) * 5
                    },
                    activeColor = palette.reminder,
                    fontSizeSp = fontSizeSp
                )

                AsciiBoxActionRow(
                    actions = listOf(
                        Triple(deltaLabel, palette.reminder) {},
                        Triple("[zZ +10m]", palette.contextTag) {
                            val dt = LocalDateTime.now().plusMinutes(10)
                            onConfirmDateTime(dt.toLocalDate(), dt.toLocalTime().withSecond(0).withNano(0), 600_000L)
                        },
                        Triple(
                            if (wheelMode == "cal") "[*Cal Wheel*]" else "[Cal Wheel]",
                            if (wheelMode == "cal") palette.project else palette.fgMuted
                        ) { wheelMode = "cal" },
                        Triple(
                            if (wheelMode == "offset") "[*+Offset*]" else "[+Offset]",
                            if (wheelMode == "offset") palette.project else palette.fgMuted
                        ) { wheelMode = "offset" }
                    ),
                    fontSizeSp = (fontSizeSp - 2).coerceAtLeast(12)
                )
                AsciiHorizontalBorder(corner = "|")
            }

            // 3. 3-Column Interactive ASCII Drum / Wheel Picker (Drag, Flick with Momentum, or Tap!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "| ",
                    color = palette.fgMuted,
                    fontSize = 12.sp,
                    fontFamily = monoFont
                )

                // Column 1: Date (in Cal mode) OR Amount (in Offset mode)
                Column(
                    modifier = Modifier
                        .weight(1.5f)
                        .pointerInput(wheelMode) {
                            val velocityTracker = VelocityTracker()
                            detectVerticalDragGestures(
                                onDragStart = {
                                    flingJobCol1?.cancel()
                                    velocityTracker.resetTracking()
                                },
                                onDragEnd = {
                                    val vy = velocityTracker.calculateVelocity().y
                                    if (abs(vy) > 300f) {
                                        flingJobCol1 = launchMomentumFling(vy, ::stepCol1)
                                    }
                                }
                            ) { change, dragAmount ->
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                dragAccumCol1 += dragAmount
                                val stepThreshold = 26f
                                while (dragAccumCol1 <= -stepThreshold) {
                                    dragAccumCol1 += stepThreshold
                                    stepCol1(1)
                                }
                                while (dragAccumCol1 >= stepThreshold) {
                                    dragAccumCol1 -= stepThreshold
                                    stepCol1(-1)
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    listOf(-2, -1, 0, 1, 2).forEach { off ->
                        val isCenter = off == 0
                        val colColor = when {
                            isCenter -> palette.project
                            off == -1 || off == 1 -> palette.fgMuted
                            else -> palette.fgMuted.copy(alpha = 0.4f)
                        }
                        val label = if (wheelMode == "cal") {
                            val d = selectedDate.plusDays(off.toLong())
                            if (d == LocalDate.now()) "Today, ${d.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))}"
                            else d.format(dayFmt)
                        } else {
                            val amt = ((offsetAmount + off - 1 + 120) % 120) + 1
                            "+$amt"
                        }
                        val prefix = if (isCenter) "=[ " else "   "
                        val suffix = if (isCenter) " ]=" else "   "
                        Text(
                            text = "$prefix$label$suffix",
                            color = colColor,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            fontSize = (if (isCenter) fontSizeSp else fontSizeSp - 1).sp,
                            fontFamily = monoFont,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    flingJobCol1?.cancel()
                                    if (off != 0) stepCol1(off)
                                }
                                .padding(vertical = 3.dp)
                        )
                    }
                }

                // Column 2: Hour (in Cal mode) OR Unit sec/min/hr/day/wk/mo (in Offset mode)
                Column(
                    modifier = Modifier
                        .weight(1.0f)
                        .pointerInput(wheelMode) {
                            val velocityTracker = VelocityTracker()
                            detectVerticalDragGestures(
                                onDragStart = {
                                    flingJobCol2?.cancel()
                                    velocityTracker.resetTracking()
                                },
                                onDragEnd = {
                                    val vy = velocityTracker.calculateVelocity().y
                                    if (abs(vy) > 300f) {
                                        flingJobCol2 = launchMomentumFling(vy, ::stepCol2)
                                    }
                                }
                            ) { change, dragAmount ->
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                dragAccumCol2 += dragAmount
                                val stepThreshold = 26f
                                while (dragAccumCol2 <= -stepThreshold) {
                                    dragAccumCol2 += stepThreshold
                                    stepCol2(1)
                                }
                                while (dragAccumCol2 >= stepThreshold) {
                                    dragAccumCol2 -= stepThreshold
                                    stepCol2(-1)
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    listOf(-2, -1, 0, 1, 2).forEach { off ->
                        val isCenter = off == 0
                        val colColor = when {
                            isCenter -> palette.reminder
                            off == -1 || off == 1 -> palette.fgMuted
                            else -> palette.fgMuted.copy(alpha = 0.4f)
                        }
                        val label = if (wheelMode == "cal") {
                            val h = (selectedHour + off + 24) % 24
                            "$h h."
                        } else {
                            val u = allUnits[(offsetUnitIdx + off + allUnits.size * 2) % allUnits.size]
                            u.asciiLabel.lowercase()
                        }
                        val prefix = if (isCenter) "[ " else "  "
                        val suffix = if (isCenter) " ]" else "  "
                        Text(
                            text = "$prefix$label$suffix",
                            color = colColor,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            fontSize = (if (isCenter) fontSizeSp else fontSizeSp - 1).sp,
                            fontFamily = monoFont,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    flingJobCol2?.cancel()
                                    if (off != 0) stepCol2(off)
                                }
                                .padding(vertical = 3.dp)
                        )
                    }
                }

                // Column 3: Minute (in Cal mode) OR Quick Step (in Offset mode)
                Column(
                    modifier = Modifier
                        .weight(1.0f)
                        .pointerInput(wheelMode) {
                            val velocityTracker = VelocityTracker()
                            detectVerticalDragGestures(
                                onDragStart = {
                                    flingJobCol3?.cancel()
                                    velocityTracker.resetTracking()
                                },
                                onDragEnd = {
                                    val vy = velocityTracker.calculateVelocity().y
                                    if (abs(vy) > 300f) {
                                        flingJobCol3 = launchMomentumFling(vy, ::stepCol3)
                                    }
                                }
                            ) { change, dragAmount ->
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                dragAccumCol3 += dragAmount
                                val stepThreshold = 26f
                                while (dragAccumCol3 <= -stepThreshold) {
                                    dragAccumCol3 += stepThreshold
                                    stepCol3(1)
                                }
                                while (dragAccumCol3 >= stepThreshold) {
                                    dragAccumCol3 -= stepThreshold
                                    stepCol3(-1)
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val curUnit = allUnits[offsetUnitIdx]
                    listOf(-2, -1, 0, 1, 2).forEach { off ->
                        val isCenter = off == 0
                        val colColor = when {
                            isCenter -> palette.priorityB
                            off == -1 || off == 1 -> palette.fgMuted
                            else -> palette.fgMuted.copy(alpha = 0.4f)
                        }
                        val label = if (wheelMode == "cal") {
                            val m = (selectedMinute + off * 5 + 60) % 60
                            "%02d m.".format(m)
                        } else {
                            val qList = curUnit.quickValues
                            val qVal = qList[(off + 2 + qList.size) % qList.size]
                            "+$qVal${curUnit.code}"
                        }
                        val prefix = if (isCenter) "[ " else "  "
                        val suffix = if (isCenter) " ]" else "  "
                        Text(
                            text = "$prefix$label$suffix",
                            color = colColor,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            fontSize = (if (isCenter) fontSizeSp else fontSizeSp - 1).sp,
                            fontFamily = monoFont,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    flingJobCol3?.cancel()
                                    if (wheelMode == "cal") {
                                        if (off != 0) stepCol3(off)
                                    } else {
                                        val qList = curUnit.quickValues
                                        offsetAmount = qList[(off + 2 + qList.size) % qList.size]
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                .padding(vertical = 3.dp)
                        )
                    }
                }

                Text(
                    text = " |",
                    color = palette.fgMuted,
                    fontSize = 12.sp,
                    fontFamily = monoFont
                )
            }

            AsciiHorizontalBorder(corner = "|")

            // 4. Quick Time-of-Day Presets (08:00 | 12:00 | 16:00 | 20:00)
            AsciiBoxActionRow(
                actions = listOf(
                    Triple("[08:00]", if (wheelMode == "cal" && selectedHour == 8 && selectedMinute == 0) palette.reminder else palette.fgMuted) {
                        wheelMode = "cal"; selectedHour = 8; selectedMinute = 0
                    },
                    Triple("[12:00]", if (wheelMode == "cal" && selectedHour == 12 && selectedMinute == 0) palette.reminder else palette.fgMuted) {
                        wheelMode = "cal"; selectedHour = 12; selectedMinute = 0
                    },
                    Triple("[16:00]", if (wheelMode == "cal" && selectedHour == 16 && selectedMinute == 0) palette.reminder else palette.fgMuted) {
                        wheelMode = "cal"; selectedHour = 16; selectedMinute = 0
                    },
                    Triple("[20:00]", if (wheelMode == "cal" && selectedHour == 20 && selectedMinute == 0) palette.reminder else palette.fgMuted) {
                        wheelMode = "cal"; selectedHour = 20; selectedMinute = 0
                    }
                ),
                fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12)
            )

            AsciiHorizontalBorder(corner = "|")

            // 5. Uniform Bottom Action Bar
            AsciiBoxActionRow(
                actions = listOf(
                    Triple("[X Close]", palette.fgMuted) { onDismiss() },
                    Triple(confirmLabel, palette.reminder) {
                        val delayMs = Duration.between(LocalDateTime.now(), targetDateTime).toMillis().coerceAtLeast(1_000L)
                        onConfirmDateTime(
                            targetDateTime.toLocalDate(),
                            targetDateTime.toLocalTime().withSecond(0).withNano(0),
                            delayMs
                        )
                    }
                ),
                fontSizeSp = fontSizeSp
            )
            AsciiHorizontalBorder()
        }
    }
}

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
                        // Posts the notification to the system tray ONLY when Full-Screen is dismissed!
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
 * - Shows the 3 primary actions: `[✓ Done]`  `[zZ Snooze]`  `[X Dismiss]`.
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

        // OLED Burn-In Prevention State:
        // - `orbitZoneIdx` cycles every 2 minutes (120s) across 5 vertical screen regions
        // - `pixelShiftIdx` cycles every 45 seconds across 8 horizontal/vertical pixel offsets
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
            currentNow.format(DateTimeFormatter.ofPattern("h:mm a"))
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
                        badgeText = "! NOTIFICATION • $clockFormatted",
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
                            Triple("[✓ Done]", palette.reminder) { onMarkDone() },
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
                    fontSizeSp = fontScaleSp(fontSizeSp),
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

private fun fontScaleSp(sz: Int): Int = sz
