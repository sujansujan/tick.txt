package com.ticktxt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.ascii.AsciiHorizontalBorder
import com.ticktxt.app.ui.ascii.AsciiTitledTopBorder
import com.ticktxt.app.ui.ascii.PlainTextSportsAscii
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsableTaskCard(
    task: TodoTask,
    currentNow: LocalDateTime = LocalDateTime.now(),
    fontSizeSp: Int = 15,
    onToggleDone: () -> Unit,
    onTapTask: () -> Unit,
    onOpenLinkedNote: (String) -> Unit = {}
) {
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current
    val monoFont = FontFamily.Monospace

    val topDateAndTime = when {
        task.isCompleted -> "DONE ${task.completionDate ?: ""}".trim()
        task.dueDate != null && task.dueTime != null -> "${task.dueDate} ${task.dueTime}"
        task.dueDate != null -> "${task.dueDate}"
        else -> "OPEN"
    }

    val relativeBadge = remember(task.dueDate, task.dueTime, task.isCompleted, currentNow) {
        if (task.isCompleted || task.dueDate == null) null
        else {
            val targetDt = LocalDateTime.of(task.dueDate, task.dueTime ?: LocalTime.of(9, 0))
            PlainTextSportsAscii.formatRelativeDelta(currentNow, targetDt)
        }
    }

    val isOverdue = !task.isCompleted && relativeBadge?.startsWith("-") == true
    val cardBorderColor = when {
        task.isCompleted -> palette.border.copy(alpha = 0.55f)
        else -> palette.border
    }
    val topBorderColor = if (isOverdue) palette.priorityA.copy(alpha = 0.72f) else cardBorderColor

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
    val hasMetaSubRow = !task.isCompleted && (
        extraProjects.isNotEmpty() ||
            extraContexts.isNotEmpty() ||
            task.recurrenceSpec != null ||
            task.reminderSpecs.isNotEmpty() ||
            task.linkedNotes.isNotEmpty() ||
            task.thresholdDate != null
        )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bg)
    ) {
        AsciiTitledTopBorder(
            badgeText = topDateAndTime,
            borderColor = topBorderColor,
            badgeColor = when {
                task.isCompleted -> palette.reminder
                isOverdue -> palette.priorityA
                else -> palette.priorityB
            },
            rightBadgeText = rightTagsSummary,
            rightBadgeColor = rightBadgeColor,
            onRightBadgeClick = { onTapTask() }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "| ",
                color = cardBorderColor,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontFamily = monoFont,
                softWrap = false,
                maxLines = 1
            )
            Text(
                text = if (task.isCompleted) "[x] " else "[ ] ",
                color = if (task.isCompleted) palette.reminder else palette.project,
                fontWeight = FontWeight.Bold,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp + 2).sp,
                fontFamily = monoFont,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onToggleDone() }
                    .padding(vertical = 2.dp)
            )
            if (task.priority != null) {
                val pColor = when (task.priority) {
                    'A' -> palette.priorityA
                    'B' -> palette.priorityB
                    else -> palette.priorityC
                }
                Text(
                    text = "(${task.priority}) ",
                    color = pColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = appFont,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.clickable { onTapTask() }
                )
            }
            Text(
                text = task.description.ifEmpty { task.rawLine },
                color = if (task.isCompleted) palette.fgMuted else palette.fg,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp + 2).sp,
                fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.Bold,
                fontFamily = appFont,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTapTask() }
                    .padding(vertical = 2.dp)
            )
            Text(
                text = " |",
                color = cardBorderColor,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontFamily = monoFont,
                softWrap = false,
                maxLines = 1
            )
        }

        if (hasMetaSubRow) {
            val metaSp = (fontSizeSp - 3).coerceAtLeast(11)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
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
                            modifier = Modifier.clickable { onOpenLinkedNote(noteFile) }
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

        AsciiHorizontalBorder(color = cardBorderColor)
    }
}
