package com.ticktxt.app.ui.ascii

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.reminders.ReminderScheduleCalculator
import com.ticktxt.app.core.reminders.SnoozeUnit
import com.ticktxt.app.ui.theme.LocalTickPalette
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
import kotlin.math.abs

/**
 * Interactive 3-Column ASCII Wheel / Drum Picker Popup:
 * - When `isSnoozeMode = false` (Task Creation & Task Editing):
 *   Clean Due Date & Time Wheel Picker (`@ PICK DUE DATE & TIME`).
 * - When `isSnoozeMode = true` (Full-Screen Notification Snooze):
 *   Full Snooze controls (`From: Now | Reminder time`, `[zZ +10m]`, `Cal Wheel | +Offset`).
 */
@Composable
fun AsciiSnoozeWheelPopup(
    taskTitle: String,
    initialDate: LocalDate?,
    initialTime: LocalTime?,
    fontSizeSp: Int = 15,
    isSnoozeMode: Boolean = true,
    confirmLabel: String = if (isSnoozeMode) "[zZ Snooze]" else "[+ Set Due]",
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
    var selectedHour by remember { mutableIntStateOf(baseDt.hour) }
    var selectedMinute by remember { mutableIntStateOf((baseDt.minute / 5) * 5) }

    // Granular Offset Wheel State (only used when isSnoozeMode == true and wheelMode == "offset")
    var offsetAmount by remember { mutableIntStateOf(10) }
    var offsetUnitIdx by remember { mutableIntStateOf(1) } // 0=sec, 1=min, 2=hr, 3=day, 4=wk, 5=mo
    val allUnits = SnoozeUnit.entries

    // Vertical drag accumulators & fling jobs for tactile momentum wheel rolling
    var dragAccumCol1 by remember { mutableFloatStateOf(0f) }
    var dragAccumCol2 by remember { mutableFloatStateOf(0f) }
    var dragAccumCol3 by remember { mutableFloatStateOf(0f) }
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
    val todayMonthDayFmt = remember { DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH) }
    val headerFmt = remember { DateTimeFormatter.ofPattern("EEE, d MMM HH:mm", Locale.ENGLISH) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.scrim)
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
                .background(palette.bgSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(10.dp)
        ) {
            // 1. Header Box: Due Date Picker vs Snooze Picker
            AsciiTitledTopBorder(
                badgeText = if (isSnoozeMode) "zZ SNOOZE REMINDER" else "@ PICK DUE DATE & TIME",
                borderColor = palette.project,
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
                            if (wheelMode == "cal") "[Cal]" else "Cal",
                            if (wheelMode == "cal") palette.project else palette.fgMuted
                        ) { wheelMode = "cal" },
                        Triple(
                            if (wheelMode == "offset") "[+Offset]" else "+Offset",
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
                    color = palette.border,
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
                            if (d == LocalDate.now()) "Today, ${d.format(todayMonthDayFmt)}"
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
                            "%02dh".format(h)
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
                            "%02dm".format(m)
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
                    color = palette.border,
                    fontSize = 12.sp,
                    fontFamily = monoFont
                )
            }

            AsciiHorizontalBorder(corner = "|")

            // 4. Quick Time-of-Day Presets (08:00 | 12:00 | 16:00 | 20:00)
            val presetKey = if (wheelMode == "cal" && selectedMinute == 0 && selectedHour in listOf(8, 12, 16, 20)) {
                selectedHour
            } else {
                -1
            }
            AsciiBoxOptionRow(
                label = "Time",
                options = listOf(8 to "08:00", 12 to "12:00", 16 to "16:00", 20 to "20:00"),
                selectedValue = presetKey,
                onSelect = { hr ->
                    wheelMode = "cal"
                    selectedHour = hr
                    selectedMinute = 0
                },
                activeColor = palette.reminder,
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
