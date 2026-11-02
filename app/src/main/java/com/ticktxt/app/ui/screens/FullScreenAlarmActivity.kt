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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
            text = fillChar.toString().repeat(240),
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
            text = "-".repeat(240),
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
 * Screenshot-1 Style Interactive 3-Column ASCII Wheel / Drum Snooze & Reminder Picker Popup!
 * Features:
 * - Touch-drag vertical scrolling (`detectVerticalDragGestures`) AND tap-to-step on all 3 wheel columns
 * - Mode switcher inside header: `[Cal]` (Date | Hour | Minute wheel) vs `[+Offset]` (Amount | Unit sec/min/hr/day/wk/mo | Quick)
 * - Anchor toggle: `[*Now*]` vs `Reminder time`
 * - Quick time presets (`[08:00]  [12:00]  [16:00]  [20:00]`) & 1-tap default snooze (`[zZ +10m]`)
 * - Uniform bottom actions: `[X Close]` and `[zZ Snooze]`
 */
@Composable
fun AsciiSnoozeWheelPopup(
    taskTitle: String,
    initialDate: LocalDate?,
    initialTime: LocalTime?,
    fontSizeSp: Int = 15,
    confirmLabel: String = "[zZ Snooze]",
    onDismiss: () -> Unit,
    onConfirmDateTime: (LocalDate, LocalTime, Long) -> Unit
) {
    val palette = LocalTickPalette.current
    val monoFont = FontFamily.Monospace
    val nowDt = remember { LocalDateTime.now().withSecond(0).withNano(0) }

    var anchorToNow by remember { mutableStateOf(true) }
    var wheelMode by remember { mutableStateOf("cal") } // "cal" = Date/Hour/Min wheel, "offset" = Amount/Unit wheel

    val baseDt = remember(anchorToNow, initialDate, initialTime) {
        if (!anchorToNow && initialDate != null) {
            LocalDateTime.of(initialDate, initialTime ?: LocalTime.of(9, 0))
        } else {
            nowDt.plusMinutes(10)
        }
    }

    // Calendar Wheel State
    var selectedDate by remember { mutableStateOf(baseDt.toLocalDate()) }
    var selectedHour by remember { mutableStateOf(baseDt.hour) }
    var selectedMinute by remember { mutableStateOf((baseDt.minute / 5) * 5) }

    // Granular Offset Wheel State (sec, min, hr, day, wk, mo)
    var offsetAmount by remember { mutableStateOf(10) }
    var offsetUnitIdx by remember { mutableStateOf(1) } // 0=sec, 1=min, 2=hr, 3=day, 4=wk, 5=mo
    val allUnits = SnoozeUnit.entries

    // Vertical drag accumulators for tactile wheel rolling
    var dragAccumCol1 by remember { mutableStateOf(0f) }
    var dragAccumCol2 by remember { mutableStateOf(0f) }
    var dragAccumCol3 by remember { mutableStateOf(0f) }

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
            .background(Color.Black.copy(alpha = 0.78f))
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
            // 1. Header Box: Task Title & Current Reminder Time
            AsciiTitledTopBorder(
                badgeText = "zZ SNOOZE / SCHEDULE",
                badgeColor = palette.project,
                rightBadgeText = deltaLabel,
                rightBadgeColor = palette.reminder
            )
            AsciiBoxBodyRow(
                text = taskTitle,
                textColor = palette.reminder,
                fontSizeSp = fontSizeSp,
                bold = true
            )
            AsciiBoxLeftRightRow(
                left = "Reminder: ${targetDateTime.format(headerFmt)}",
                right = deltaLabel,
                leftColor = palette.fgMuted,
                rightColor = palette.priorityB,
                fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12),
                boldRight = true
            )
            AsciiHorizontalBorder(corner = "|")

            // 2. Anchor Toggle Row: [(*) Now]  [( ) Reminder time]
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

            // 3. Mode Switcher Bar (matching Screenshot 1's `[32 d.   [+10m] [Cal] [Offset]]` bar!)
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

            // 4. 3-Column Interactive ASCII Drum / Wheel Picker (Drag vertically OR tap rows!)
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
                            detectVerticalDragGestures { _, dragAmount ->
                                dragAccumCol1 += dragAmount
                                val stepThreshold = 28f
                                while (dragAccumCol1 <= -stepThreshold) {
                                    dragAccumCol1 += stepThreshold
                                    if (wheelMode == "cal") selectedDate = selectedDate.plusDays(1)
                                    else offsetAmount = (offsetAmount + 1).coerceAtMost(120)
                                }
                                while (dragAccumCol1 >= stepThreshold) {
                                    dragAccumCol1 -= stepThreshold
                                    if (wheelMode == "cal") selectedDate = selectedDate.minusDays(1)
                                    else offsetAmount = (offsetAmount - 1).coerceAtLeast(1)
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
                                    if (wheelMode == "cal") selectedDate = selectedDate.plusDays(off.toLong())
                                    else offsetAmount = (((offsetAmount + off - 1 + 120) % 120) + 1)
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
                            detectVerticalDragGestures { _, dragAmount ->
                                dragAccumCol2 += dragAmount
                                val stepThreshold = 28f
                                while (dragAccumCol2 <= -stepThreshold) {
                                    dragAccumCol2 += stepThreshold
                                    if (wheelMode == "cal") selectedHour = (selectedHour + 1) % 24
                                    else offsetUnitIdx = (offsetUnitIdx + 1) % allUnits.size
                                }
                                while (dragAccumCol2 >= stepThreshold) {
                                    dragAccumCol2 -= stepThreshold
                                    if (wheelMode == "cal") selectedHour = (selectedHour - 1 + 24) % 24
                                    else offsetUnitIdx = (offsetUnitIdx - 1 + allUnits.size) % allUnits.size
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
                                    if (wheelMode == "cal") selectedHour = (selectedHour + off + 24) % 24
                                    else offsetUnitIdx = (offsetUnitIdx + off + allUnits.size * 2) % allUnits.size
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
                            detectVerticalDragGestures { _, dragAmount ->
                                dragAccumCol3 += dragAmount
                                val stepThreshold = 28f
                                while (dragAccumCol3 <= -stepThreshold) {
                                    dragAccumCol3 += stepThreshold
                                    if (wheelMode == "cal") selectedMinute = (selectedMinute + 5) % 60
                                    else offsetAmount = (offsetAmount + 5).coerceAtMost(120)
                                }
                                while (dragAccumCol3 >= stepThreshold) {
                                    dragAccumCol3 -= stepThreshold
                                    if (wheelMode == "cal") selectedMinute = (selectedMinute - 5 + 60) % 60
                                    else offsetAmount = (offsetAmount - 5).coerceAtLeast(1)
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
                                    if (wheelMode == "cal") {
                                        selectedMinute = (selectedMinute + off * 5 + 60) % 60
                                    } else {
                                        val qList = curUnit.quickValues
                                        offsetAmount = qList[(off + 2 + qList.size) % qList.size]
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

            // 5. Quick Time-of-Day Presets (08:00 | 12:00 | 16:00 | 20:00) from Screenshot 1
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

            // 6. Uniform Bottom Action Bar: [X Close]   &   [zZ Snooze]
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

/**
 * Clean 3-Option Full-Screen Notification / Alarm Surface:
 * Shows ONLY the ringing task and the 3 primary actions:
 *   `[✓ Done]`    `[zZ Snooze]`    `[X Dismiss]`
 * Clicking `[zZ Snooze]` opens the Screenshot-1 style `AsciiSnoozeWheelPopup` over a shaded background!
 */
@Composable
fun AsciiFullScreenAlarmSurface(
    taskTitle: String,
    rawSpec: String,
    fontSizeSp: Int = 15,
    initialAlarmMuted: Boolean = true,
    onToggleAlarmMute: (Boolean) -> Unit = {},
    onSnoozeWithPreset: (String) -> Unit,
    onDismiss: () -> Unit,
    onMarkDone: () -> Unit
) {
    val palette = LocalTickPalette.current
    var showSnoozeWheelPopup by remember { mutableStateOf(false) }
    var currentNow by remember { mutableStateOf(ZonedDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            currentNow = ZonedDateTime.now()
        }
    }

    val clockFormatted = remember(currentNow) {
        currentNow.format(DateTimeFormatter.ofPattern("h:mm a"))
    }
    val modeBadge = if (rawSpec.endsWith("/n")) "NOTIFICATION" else "ALARM"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = PlainTextSportsAscii.ALARM_BELL_ART,
                color = palette.priorityB,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Clean Full-Screen Task Box with ONLY the 3 actions: Done, Snooze, Dismiss
            Column(modifier = Modifier.fillMaxWidth()) {
                AsciiTitledTopBorder(
                    badgeText = "! $modeBadge • $clockFormatted",
                    badgeColor = palette.priorityA,
                    rightBadgeText = "rem:$rawSpec",
                    rightBadgeColor = palette.reminder
                )
                Spacer(modifier = Modifier.height(4.dp))
                AsciiBoxBodyRow(
                    text = taskTitle,
                    textColor = palette.fg,
                    fontSizeSp = fontSizeSp + 2,
                    bold = true
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

private fun fontScaleSp(sz: Int): Int = sz
