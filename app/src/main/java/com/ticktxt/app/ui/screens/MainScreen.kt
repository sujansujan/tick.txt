package com.ticktxt.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.nlp.RulesEngine
import com.ticktxt.app.core.nlp.SmartAddParser
import com.ticktxt.app.core.nlp.TickRulesConfig
import com.ticktxt.app.core.notes.NoteFile
import com.ticktxt.app.core.notes.NotesRepository
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.ThemeMode
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun MainScreen(
    initialTasks: List<TodoTask>,
    rules: TickRulesConfig = TickRulesConfig(),
    themeMode: ThemeMode,
    onChangeTheme: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalTickPalette.current

    var tasks by remember { mutableStateOf(initialTasks) }
    var doneLines by remember {
        mutableStateOf(listOf("x 2026-10-29 2026-10-25 set up plain text folder on android +ops @phone"))
    }
    var rulesText by remember { mutableStateOf(TickRulesConfig.DEFAULT_RULES_FILE_CONTENT) }
    val activeRules = remember(rulesText) { RulesEngine.parse(rulesText) }

    var notes by remember {
        mutableStateOf(
            listOf(
                NotesRepository.parseNoteContent(
                    "architecture.md",
                    "<!-- tick: rem:2026-11-04@09:00/n -->\n# tick.txt storage notes\n\n- todo.txt and done.txt follow the official spec.\n- tick.rules.txt holds smart-add rules.\n- .tick/alarm-state.json stores active snoozes."
                ),
                NotesRepository.parseNoteContent(
                    "packing-list.txt",
                    "@tick rem:2026-11-05@07:30/a/svr\npassport\nusb-c cable\nnotebook and pen"
                )
            )
        )
    }
    var selectedNoteName by remember { mutableStateOf("architecture.md") }
    var noteEditMode by remember { mutableStateOf(false) }
    var noteDraftText by remember { mutableStateOf("") }

    var activeTab by remember { mutableStateOf("todo.txt") }
    var activeFilter by remember { mutableStateOf("all") }
    var rawTodoMode by remember { mutableStateOf(false) }
    var rawTodoDraft by remember { mutableStateOf("") }

    var smartInput by remember { mutableStateOf("") }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var undoSnapshot by remember { mutableStateOf<List<TodoTask>?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    var storageFolderPath by remember { mutableStateOf("/storage/emulated/0/sync/tick/") }
    var fontScaleSp by remember { mutableStateOf(13) }

    val now = remember { LocalDateTime.now() }
    val parsePreview = remember(smartInput, disabledSpans, activeRules) {
        if (smartInput.isBlank()) null
        else SmartAddParser.parse(smartInput, now, activeRules, disabledSpans)
    }

    val filteredTasks = remember(tasks, activeFilter) {
        tasks.filter { task ->
            if (task.isBlankOrComment) return@filter false
            when {
                activeFilter == "open" -> !task.isCompleted
                activeFilter == "due" -> !task.isCompleted && task.dueDate != null
                activeFilter.startsWith("+") -> task.projects.contains(activeFilter.drop(1))
                activeFilter.startsWith("@") -> task.contexts.contains(activeFilter.drop(1))
                else -> true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Minimal top navigation bar (no app title, location, clock, or theme buttons)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val openCount = tasks.count { !it.isBlankOrComment && !it.isCompleted }
                listOf(
                    "todo.txt" to "todo.txt ($openCount)",
                    "notes/" to "notes/ (${notes.size})",
                    "settings" to "settings"
                ).forEach { (tabKey, tabLabel) ->
                    val selected = activeTab == tabKey
                    Text(
                        text = if (selected) "* $tabLabel" else "  $tabLabel",
                        color = if (selected) palette.reminder else palette.fgMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { activeTab = tabKey }
                    )
                }
            }
        }

        Text(
            text = "────────────────────────────────────────────────────────────────────",
            color = palette.border,
            fontSize = 11.sp,
            maxLines = 1
        )

        // Optional status / undo bar
        if (statusBanner != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(palette.bgElevated)
                    .border(1.dp, palette.border)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusBanner ?: "",
                    color = palette.fg,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (undoSnapshot != null) {
                        Text(
                            text = "[undo]",
                            color = palette.project,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                tasks = undoSnapshot!!
                                undoSnapshot = null
                                statusBanner = null
                            }
                        )
                    }
                    Text(
                        text = "[x]",
                        color = palette.fgMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { statusBanner = null }
                    )
                }
            }
        }

        when (activeTab) {
            "todo.txt" -> {
                // Filter & Raw Mode Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("all", "open", "due", "+family", "+work", "@home").forEach { f ->
                        val active = activeFilter == f && !rawTodoMode
                        Text(
                            text = if (active) "[$f]" else f,
                            color = if (active) palette.project else palette.fgMuted,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                rawTodoMode = false
                                activeFilter = f
                            }
                        )
                    }
                    Text(
                        text = if (rawTodoMode) "[raw todo.txt]" else "raw",
                        color = if (rawTodoMode) palette.reminder else palette.fgMuted,
                        fontWeight = if (rawTodoMode) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            if (!rawTodoMode) {
                                rawTodoDraft = TodoParser.serializeFile(tasks).trimEnd()
                                rawTodoMode = true
                            } else {
                                rawTodoMode = false
                            }
                        }
                    )
                    Text(
                        text = "archive-x",
                        color = palette.fgMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            val completed = tasks.filter { it.isCompleted }
                            if (completed.isNotEmpty()) {
                                undoSnapshot = tasks
                                doneLines = doneLines + completed.map { TodoParser.serializeLine(it) }
                                tasks = tasks.filterNot { it.isCompleted }
                                    .mapIndexed { idx, t -> t.copy(lineNumber = idx + 1) }
                                statusBanner = "archived ${completed.size} task(s) to done.txt"
                            } else {
                                statusBanner = "0 completed tasks to archive"
                            }
                        }
                    )
                }

                if (rawTodoMode) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "direct todo.txt buffer (atomic write on save):",
                            color = palette.fgMuted,
                            fontSize = 11.sp
                        )
                        BasicTextField(
                            value = rawTodoDraft,
                            onValueChange = { rawTodoDraft = it },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                color = palette.fg,
                                fontSize = fontScaleSp.sp
                            ),
                            cursorBrush = SolidColor(palette.project),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(palette.bgElevated)
                                .border(1.dp, palette.border)
                                .padding(8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "[save todo.txt]",
                                color = palette.reminder,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable {
                                        undoSnapshot = tasks
                                        tasks = TodoParser.parseFile(rawTodoDraft)
                                        rawTodoMode = false
                                        statusBanner = "saved todo.txt atomically"
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                } else {
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
                            items(filteredTasks, key = { "${it.lineNumber}:${it.rawLine}" }) { task ->
                                TaskRow(
                                    task = task,
                                    fontSizeSp = fontScaleSp,
                                    onToggleDone = {
                                        undoSnapshot = tasks
                                        val updated = if (task.isCompleted) {
                                            TodoParser.markIncomplete(task) to null
                                        } else {
                                            TodoParser.markCompleted(task, LocalDate.now())
                                        }
                                        val nextList = tasks.map {
                                            if (it.lineNumber == task.lineNumber) updated.first else it
                                        } + listOfNotNull(
                                            updated.second?.copy(lineNumber = tasks.size + 1)
                                        )
                                        tasks = nextList
                                        statusBanner = if (updated.second != null) {
                                            "completed & spawned recurring task"
                                        } else {
                                            "updated line ${task.lineNumber}"
                                        }
                                    },
                                    onOpenNote = { noteFilename ->
                                        val match = notes.firstOrNull {
                                            it.filename.equals(noteFilename, ignoreCase = true)
                                        }
                                        if (match != null) {
                                            selectedNoteName = match.filename
                                        }
                                        activeTab = "notes/"
                                    },
                                    onFireAlarmPreview = {
                                        val intent = Intent(context, FullScreenAlarmActivity::class.java).apply {
                                            putExtra("extra_task_title", task.description)
                                            putExtra("extra_raw_spec", task.reminderSpecs.firstOrNull() ?: "0m/a")
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }
                    }
                }

                // Live Smart-Add Preview
                if (parsePreview != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.bgElevated)
                            .border(1.dp, palette.border)
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
                                .horizontalScroll(rememberScrollState())
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
                        .border(1.dp, palette.border)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "> ", color = palette.project, fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.weight(1f)) {
                        if (smartInput.isEmpty()) {
                            Text(
                                text = "coffee with mum at 2pm Tuesday at home",
                                color = palette.fgMuted,
                                fontSize = fontScaleSp.sp,
                                maxLines = 1
                            )
                        }
                        BasicTextField(
                            value = smartInput,
                            onValueChange = {
                                smartInput = it
                                disabledSpans = emptySet()
                            },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                color = palette.fg,
                                fontSize = fontScaleSp.sp
                            ),
                            cursorBrush = SolidColor(palette.project),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = "[+add]",
                        color = palette.reminder,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontScaleSp.sp,
                        modifier = Modifier
                            .clickable {
                                val res = parsePreview ?: return@clickable
                                if (res.isNoteCapture) {
                                    val fn = NotesRepository.toHumanReadableFilename(
                                        res.noteTitle ?: "quick-note",
                                        ".md"
                                    )
                                    val newNote = NotesRepository.parseNoteContent(
                                        fn,
                                        "# ${res.strippedTitle}\n"
                                    )
                                    notes = notes + newNote
                                    selectedNoteName = fn
                                    smartInput = ""
                                    disabledSpans = emptySet()
                                    activeTab = "notes/"
                                } else {
                                    undoSnapshot = tasks
                                    val newTask = TodoParser.parseLine(res.formattedTodoLine, tasks.size + 1)
                                    tasks = tasks + newTask
                                    smartInput = ""
                                    disabledSpans = emptySet()
                                    statusBanner = "added line ${tasks.size}"
                                }
                            }
                            .padding(start = 8.dp)
                    )
                }
            }

            "notes/" -> {
                val currentNote = notes.firstOrNull { it.filename == selectedNoteName } ?: notes.first()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // File selector row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        notes.forEach { n ->
                            val sel = n.filename == currentNote.filename
                            Text(
                                text = if (sel) "[* ${n.filename}]" else n.filename,
                                color = if (sel) palette.project else palette.fgMuted,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    selectedNoteName = n.filename
                                    noteEditMode = false
                                }
                            )
                        }
                        Text(
                            text = "[+new]",
                            color = palette.reminder,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                val name = "note-${notes.size + 1}.md"
                                val created = NotesRepository.parseNoteContent(name, "# $name\n\nplain text note.")
                                notes = notes + created
                                selectedNoteName = name
                                noteDraftText = created.rawContent
                                noteEditMode = true
                            }
                        )
                    }

                    // Mode toggle & reminders info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (currentNote.reminderSpecs.isNotEmpty()) {
                                "rem:${currentNote.reminderSpecs.joinToString(" rem:")}"
                            } else {
                                "0 note reminders"
                            },
                            color = palette.reminder,
                            fontSize = 11.sp
                        )
                        Text(
                            text = if (noteEditMode) "[save file]" else "[raw edit]",
                            color = palette.project,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                if (noteEditMode) {
                                    val updated = NotesRepository.parseNoteContent(currentNote.filename, noteDraftText)
                                    notes = notes.map { if (it.filename == currentNote.filename) updated else it }
                                    noteEditMode = false
                                    statusBanner = "saved notes/${currentNote.filename}"
                                } else {
                                    noteDraftText = currentNote.rawContent
                                    noteEditMode = true
                                }
                            }
                        )
                    }

                    if (noteEditMode) {
                        BasicTextField(
                            value = noteDraftText,
                            onValueChange = { noteDraftText = it },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                color = palette.fg,
                                fontSize = fontScaleSp.sp
                            ),
                            cursorBrush = SolidColor(palette.project),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(palette.bgElevated)
                                .border(1.dp, palette.border)
                                .padding(8.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(palette.bgElevated)
                                .border(1.dp, palette.border)
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            currentNote.bodyContent.lines().forEach { line ->
                                val isHeader = line.startsWith("#")
                                Text(
                                    text = line,
                                    color = if (isHeader) palette.project else palette.fg,
                                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = fontScaleSp.sp
                                )
                            }
                        }
                    }

                    // Backlinks from todo.txt
                    val backlinks = tasks.filter { t ->
                        t.linkedNotes.any { it.equals(currentNote.filename, ignoreCase = true) }
                    }
                    Text(
                        text = "task backlinks (note:${currentNote.filename}): ${backlinks.size}",
                        color = palette.fgMuted,
                        fontSize = 11.sp
                    )
                    backlinks.forEach { bl ->
                        Text(
                            text = "  line ${bl.lineNumber}: ${bl.description}",
                            color = palette.noteLink,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { activeTab = "todo.txt" }
                        )
                    }
                }
            }

            "settings" -> {
                SettingsScreen(
                    storageFolderPath = storageFolderPath,
                    onChangeFolder = { storageFolderPath = it },
                    themeMode = themeMode,
                    onChangeTheme = onChangeTheme,
                    fontSizeSp = fontScaleSp,
                    onChangeFontSize = { fontScaleSp = it },
                    rulesText = rulesText,
                    onSaveRules = {
                        rulesText = it
                        statusBanner = "updated tick.rules.txt"
                    },
                    onTriggerTestAlarm = {
                        val intent = Intent(context, FullScreenAlarmActivity::class.java).apply {
                            putExtra("extra_task_title", "test lockscreen alarm (/a)")
                            putExtra("extra_raw_spec", "0m/a/svr")
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TodoTask,
    fontSizeSp: Int,
    onToggleDone: () -> Unit,
    onOpenNote: (String) -> Unit,
    onFireAlarmPreview: () -> Unit
) {
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
            withStyle(SpanStyle(color = palette.noteLink, textDecoration = TextDecoration.Underline)) {
                append("note:$n ")
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = annotated,
            fontSize = fontSizeSp.sp,
            modifier = Modifier
                .weight(1f)
                .clickable {
                    if (task.linkedNotes.isNotEmpty()) {
                        onToggleDone()
                    } else {
                        onToggleDone()
                    }
                }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (task.linkedNotes.isNotEmpty()) {
                Text(
                    text = "[note]",
                    color = palette.noteLink,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onOpenNote(task.linkedNotes.first()) }
                )
            }
            if (task.reminderSpecs.isNotEmpty()) {
                Text(
                    text = "[bell]",
                    color = palette.reminder,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onFireAlarmPreview() }
                )
            }
        }
    }
}
