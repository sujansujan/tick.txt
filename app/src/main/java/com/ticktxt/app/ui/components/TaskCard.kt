package com.ticktxt.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.ascii.AsciiBoxActionRow
import com.ticktxt.app.ui.ascii.AsciiHorizontalBorder
import com.ticktxt.app.ui.ascii.AsciiTitledTopBorder
import com.ticktxt.app.ui.ascii.PlainTextSportsAscii
import com.ticktxt.app.ui.ascii.rememberTickHaptics
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun UsableTaskCard(
    task: TodoTask,
    currentNow: LocalDateTime = LocalDateTime.now(),
    fontSizeSp: Int = 15,
    isCompletingFlash: Boolean = false,
    isTriageExpanded: Boolean = false,
    onToggleDone: () -> Unit,
    onTapTask: () -> Unit,
    onLongPressTask: () -> Unit = {},
    onQuickBumpTomorrow: () -> Unit = {},
    onQuickSetPriority: (Char?) -> Unit = {},
    onQuickCopy: () -> Unit = {},
    onQuickDelete: () -> Unit = {},
    onOpenLinkedNote: (String) -> Unit = {}
) {
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current
    val haptics = rememberTickHaptics()
    val monoFont = FontFamily.Monospace

    val visuallyCompleted = task.isCompleted || isCompletingFlash

    val topDateAndTime = when {
        visuallyCompleted -> "DONE ${task.completionDate ?: currentNow.toLocalDate()}".trim()
        task.dueDate != null && task.dueTime != null -> "${task.dueDate} ${task.dueTime}"
        task.dueDate != null -> "${task.dueDate}"
        else -> "OPEN"
    }

    val relativeBadge = remember(task.dueDate, task.dueTime, visuallyCompleted, currentNow) {
        if (visuallyCompleted || task.dueDate == null) null
        else {
            val targetDt = LocalDateTime.of(task.dueDate, task.dueTime ?: LocalTime.of(9, 0))
            PlainTextSportsAscii.formatRelativeDelta(currentNow, targetDt)
        }
    }

    val isOverdue = !visuallyCompleted && relativeBadge?.startsWith("-") == true
    val cardBorderColor = when {
        isTriageExpanded -> palette.project
        isCompletingFlash -> palette.reminder
        task.isCompleted -> palette.border.copy(alpha = 0.55f)
        else -> palette.border
    }
    val topBorderColor = when {
        isTriageExpanded -> palette.project
        isCompletingFlash -> palette.reminder
        isOverdue -> palette.priorityA.copy(alpha = 0.72f)
        else -> cardBorderColor
    }

    val rightTagsSummary = buildString {
        task.projects.firstOrNull()?.let { append("+$it") }
        task.contexts.firstOrNull()?.let {
            if (isNotEmpty()) append(" ")
            append("@$it")
        }
        if (relativeBadge != null) {
            if (isNotEmpty()) append(" | ")
            append(relativeBadge)
        }
    }.ifEmpty { null }

    val rightBadgeColor = if (isOverdue) palette.priorityA else palette.project

    val extraProjects = task.projects.drop(1)
    val extraContexts = task.contexts.drop(1)
    val hasMetaSubRow = !visuallyCompleted && (
        extraProjects.isNotEmpty() ||
            extraContexts.isNotEmpty() ||
            task.recurrenceSpec != null ||
            task.reminderSpecs.isNotEmpty() ||
            task.linkedNotes.isNotEmpty() ||
            task.thresholdDate != null
        )

    var titleLineCount by remember(task.rawLine, fontSizeSp) { mutableIntStateOf(1) }
    val leftRail = remember(titleLineCount) { List(titleLineCount) { "| " }.joinToString("\n") }
    val rightRail = remember(titleLineCount) { List(titleLineCount) { " |" }.joinToString("\n") }
    val titleLineHeight = (fontSizeSp + 3).sp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isTriageExpanded) palette.bgSurface else palette.bg)
    ) {
        AsciiTitledTopBorder(
            badgeText = topDateAndTime,
            borderColor = topBorderColor,
            badgeColor = when {
                visuallyCompleted -> palette.reminder
                isOverdue -> palette.priorityA
                else -> palette.priorityB
            },
            rightBadgeText = rightTagsSummary,
            rightBadgeColor = rightBadgeColor,
            onRightBadgeClick = {
                haptics.tap()
                onTapTask()
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = leftRail,
                color = cardBorderColor,
                fontSize = 12.sp,
                lineHeight = titleLineHeight,
                fontFamily = monoFont,
                softWrap = false,
                modifier = Modifier.padding(top = 4.dp)
            )
            // Generous touch hitbox around `[ ]` / `[x]` without distorting visual alignment
            Text(
                text = if (visuallyCompleted) "[x] " else "[ ] ",
                color = if (visuallyCompleted) palette.reminder else palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = fontSizeSp.sp,
                lineHeight = titleLineHeight,
                fontFamily = monoFont,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onToggleDone() }
                    .padding(top = 4.dp, bottom = 6.dp, end = 2.dp)
            )
            if (task.priority != null) {
                val pColor = when (task.priority) {
                    'A' -> palette.priorityA
                    'B' -> palette.priorityB
                    else -> palette.priorityC
                }
                Text(
                    text = "(${task.priority}) ",
                    color = if (visuallyCompleted) palette.fgMuted else pColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = titleLineHeight,
                    fontFamily = appFont,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {
                                haptics.tap()
                                onTapTask()
                            },
                            onLongClick = {
                                haptics.longPress()
                                onLongPressTask()
                            }
                        )
                        .padding(top = 4.dp, bottom = 6.dp)
                )
            }
            Text(
                text = task.description.ifEmpty { task.rawLine },
                color = when {
                    isCompletingFlash -> palette.reminder
                    task.isCompleted -> palette.fgMuted
                    else -> palette.fg
                },
                fontSize = fontSizeSp.sp,
                lineHeight = titleLineHeight,
                fontWeight = if (visuallyCompleted) FontWeight.Normal else FontWeight.Bold,
                fontFamily = appFont,
                textDecoration = if (visuallyCompleted) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layoutResult ->
                    val count = layoutResult.lineCount.coerceIn(1, 2)
                    if (count != titleLineCount) titleLineCount = count
                },
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = {
                            haptics.tap()
                            onTapTask()
                        },
                        onLongClick = {
                            haptics.longPress()
                            onLongPressTask()
                        }
                    )
                    .padding(top = 4.dp, bottom = 6.dp)
            )
            Text(
                text = rightRail,
                color = cardBorderColor,
                fontSize = 12.sp,
                lineHeight = titleLineHeight,
                fontFamily = monoFont,
                softWrap = false,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (hasMetaSubRow) {
            val metaSp = (fontSizeSp - 3).coerceAtLeast(11)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "|     ",
                    color = cardBorderColor,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontFamily = monoFont,
                    softWrap = false,
                    maxLines = 1
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    extraProjects.forEach { proj ->
                        Text(
                            text = "+$proj",
                            color = palette.project,
                            fontSize = metaSp.sp,
                            fontFamily = monoFont,
                            maxLines = 1
                        )
                    }
                    extraContexts.forEach { ctx ->
                        Text(
                            text = "@$ctx",
                            color = palette.contextTag,
                            fontSize = metaSp.sp,
                            fontFamily = monoFont,
                            maxLines = 1
                        )
                    }
                    task.recurrenceSpec?.let { rec ->
                        Text(
                            text = "rec:$rec",
                            color = palette.contextTag,
                            fontSize = metaSp.sp,
                            fontFamily = monoFont,
                            maxLines = 1
                        )
                    }
                    task.reminderSpecs.firstOrNull()?.let { rem ->
                        Text(
                            text = "rem:$rem",
                            color = palette.reminder,
                            fontSize = metaSp.sp,
                            fontFamily = monoFont,
                            maxLines = 1
                        )
                    }
                    task.thresholdDate?.let { deferDate ->
                        Text(
                            text = "t:$deferDate",
                            color = palette.fgMuted,
                            fontSize = metaSp.sp,
                            fontFamily = monoFont,
                            maxLines = 1
                        )
                    }
                    task.linkedNotes.forEach { noteFile ->
                        Text(
                            text = "[note:$noteFile]",
                            color = palette.noteLink,
                            fontWeight = FontWeight.Bold,
                            fontSize = metaSp.sp,
                            fontFamily = monoFont,
                            maxLines = 1,
                            modifier = Modifier
                                .clickable {
                                    haptics.tap()
                                    onOpenLinkedNote(noteFile)
                                }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = " |",
                    color = cardBorderColor,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontFamily = monoFont,
                    softWrap = false,
                    maxLines = 1
                )
            }
        }

        // Long-press inline ASCII quick-triage bar
        if (isTriageExpanded) {
            AsciiHorizontalBorder(corner = "|", color = cardBorderColor)
            val nextPriA = if (task.priority == 'A') null else 'A'
            val nextPriB = if (task.priority == 'B') null else 'B'
            AsciiBoxActionRow(
                actions = listOf(
                    Triple("[+1d]", palette.priorityB, onQuickBumpTomorrow),
                    Triple(
                        if (task.priority == 'A') "[-A]" else "[(A)]",
                        palette.priorityA
                    ) { onQuickSetPriority(nextPriA) },
                    Triple(
                        if (task.priority == 'B') "[-B]" else "[(B)]",
                        palette.priorityB
                    ) { onQuickSetPriority(nextPriB) },
                    Triple("[Copy]", palette.contextTag, onQuickCopy),
                    Triple("[X Del]", palette.priorityA, onQuickDelete)
                ),
                borderColor = cardBorderColor,
                fontSizeSp = (fontSizeSp - 2).coerceAtLeast(11)
            )
        }

        AsciiHorizontalBorder(color = cardBorderColor)
    }
}
