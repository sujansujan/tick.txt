package com.ticktxt.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.nlp.SmartAddParser
import com.ticktxt.app.core.nlp.TickRulesConfig
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Minimalist homepage:
 * - No app title, folder path, date/time, or font/theme chrome at the top.
 * - Starts directly with the plain-text navigation strip (`todo.txt`, `notes/`, `settings`)
 *   and quick filters (`all`, `open`, `due`, `+project`, `@context`).
 * - All configuration (folder path, theme, font size, rules, reminder diagnostics) lives in `settings`.
 */
@Composable
fun MainScreen(
    initialTasks: List<TodoTask>,
    rules: TickRulesConfig = TickRulesConfig(),
    themeMode: ThemeMode,
    onChangeTheme: (ThemeMode) -> Unit
) {
    val palette = LocalTickPalette.current
    var tasks by remember { mutableStateOf(initialTasks) }
    var activeTab by remember { mutableStateOf("todo.txt") }
    var activeFilter by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var smartInput by remember { mutableStateOf("") }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var storageFolderPath by remember { mutableStateOf("/storage/emulated/0/sync/tick/") }
    var fontScaleSp by remember { mutableStateOf(13) }

    val now = remember { LocalDateTime.now() }
    val parsePreview = remember(smartInput, disabledSpans) {
        if (smartInput.isBlank()) null
        else SmartAddParser.parse(smartInput, now, rules, disabledSpans)
    }

    val filteredTasks = remember(tasks, activeFilter, searchQuery) {
        tasks.filter { task ->
            if (task.isBlankOrComment) return@filter false
            val matchesFilter = when {
                activeFilter == "open" -> !task.isCompleted
                activeFilter == "due" -> !task.isCompleted && task.dueDate != null
                activeFilter.startsWith("+") -> task.projects.contains(activeFilter.drop(1))
                activeFilter.startsWith("@") -> task.contexts.contains(activeFilter.drop(1))
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                task.rawLine.contains(searchQuery.trim(), ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Minimal top navigation bar (no app title, clock, location, or theme clutter)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("todo.txt", "notes/", "settings").forEach { tab ->
                    val selected = activeTab == tab
                    Text(
                        text = if (selected) "* $tab" else "  $tab",
                        color = if (selected) palette.reminder else palette.fgMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { activeTab = tab }
                    )
                }
            }
        }

        Text(
            text = "────────────────────────────────────────────────────────────",
            color = palette.border,
            fontSize = 11.sp,
            maxLines = 1
        )

        when (activeTab) {
            "todo.txt" -> {
                // Simple filter strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf("all", "open", "due", "+family", "+work", "@home").forEach { f ->
                        val active = activeFilter == f
                        Text(
                            text = if (active) "[$f]" else f,
                            color = if (active) palette.project else palette.fgMuted,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { activeFilter = f }
                        )
                    }
                }

                // Task list body
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (filteredTasks.isEmpty()) {
                        item {
                            Text(
                                text = "0 tasks.",
                                color = palette.fgMuted,
                                fontSize = fontScaleSp.sp,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    } else {
                        items(filteredTasks, key = { it.lineNumber to it.rawLine }) { task ->
                            TaskRow(
                                task = task,
                                fontSizeSp = fontScaleSp,
                                onToggleDone = {
                                    val updated = if (task.isCompleted) {
                                        TodoParser.markIncomplete(task) to null
                                    } else {
                                        TodoParser.markCompleted(task, LocalDate.now())
                                    }
                                    tasks = tasks.map {
                                        if (it.lineNumber == task.lineNumber) updated.first else it
                                    } + listOfNotNull(updated.second)
                                }
                            )
                        }
                    }
                }

                // Live Smart-Add Preview (only shown while typing)
                if (parsePreview != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.bgElevated)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "-> ${parsePreview.formattedTodoLine}",
                            color = palette.fgMuted,
                            fontSize = 11.sp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            parsePreview.recognizedSpans.forEach { span ->
                                Text(
                                    text = "[${span.resolvedToken} x]",
                                    color = palette.reminder,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        disabledSpans = disabledSpans + span.id
                                    }
                                )
                            }
                        }
                    }
                }

                // Bottom One-Handed Capture Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.bgElevated)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "> ", color = palette.project, fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.weight(1f)) {
                        if (smartInput.isEmpty()) {
                            Text(
                                text = "add task or 'note: ...'",
                                color = palette.fgMuted,
                                fontSize = fontScaleSp.sp
                            )
                        }
                        BasicTextField(
                            value = smartInput,
                            onValueChange = {
                                smartInput = it
                                disabledSpans = emptySet()
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                color = palette.fg,
                                fontSize = fontScaleSp.sp
                            ),
                            cursorBrush = SolidColor(palette.project),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = "[add]",
                        color = palette.reminder,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontScaleSp.sp,
                        modifier = Modifier
                            .clickable {
                                val res = parsePreview ?: return@clickable
                                val newTask = TodoParser.parseLine(res.formattedTodoLine, tasks.size + 1)
                                tasks = tasks + newTask
                                smartInput = ""
                                disabledSpans = emptySet()
                            }
                            .padding(start = 8.dp)
                    )
                }
            }

            "settings" -> {
                SettingsScreen(
                    storageFolderPath = storageFolderPath,
                    onChangeFolder = { storageFolderPath = it },
                    themeMode = themeMode,
                    onChangeTheme = onChangeTheme,
                    fontSizeSp = fontScaleSp,
                    onChangeFontSize = { fontScaleSp = it }
                )
            }

            else -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        text = "notes/ directory",
                        color = palette.fg,
                        fontSize = fontScaleSp.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TodoTask, fontSizeSp: Int, onToggleDone: () -> Unit) {
    val palette = LocalTickPalette.current
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = if (task.isCompleted) palette.fgMuted else palette.reminder)) {
            append(if (task.isCompleted) "[x] " else "[ ] ")
        }
        if (task.priority != null) {
            val pColor = when (task.priority) {
                'A' -> palette.priorityA
                'B' -> palette.priorityB
                else -> palette.priorityC
            }
            withStyle(SpanStyle(color = pColor, fontWeight = FontWeight.Bold)) {
                append("(${task.priority}) ")
            }
        }
        val bodyStyle = SpanStyle(
            color = if (task.isCompleted) palette.fgMuted else palette.fg,
            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
        )
        withStyle(bodyStyle) {
            append(task.description)
            append(" ")
        }
        task.projects.forEach { p ->
            withStyle(SpanStyle(color = palette.project)) { append("+$p ") }
        }
        task.contexts.forEach { c ->
            withStyle(SpanStyle(color = palette.contextTag)) { append("@$c ") }
        }
        task.dueDate?.let { d ->
            val dStr = if (task.dueTime != null) "due:$d ${task.dueTime}" else "due:$d"
            withStyle(SpanStyle(color = palette.priorityB)) { append("$dStr ") }
        }
        task.reminderSpecs.forEach { r ->
            withStyle(SpanStyle(color = palette.reminder)) { append("rem:$r ") }
        }
        task.linkedNotes.forEach { n ->
            withStyle(SpanStyle(color = palette.noteLink)) { append("note:$n ") }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleDone() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = annotated, fontSize = fontSizeSp.sp)
    }
}
