package com.ticktxt.app.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ticktxt.app.core.nlp.RulesEngine
import com.ticktxt.app.core.nlp.SmartAddParser
import com.ticktxt.app.core.notes.NotesRepository
import com.ticktxt.app.core.reminders.ReminderScheduleCalculator
import com.ticktxt.app.core.reminders.ReminderScheduler
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    storageRepo: TickStorageRepository,
    settings: PersistedSettings,
    onUpdateSettings: (PersistedSettings) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val palette = LocalTickPalette.current
    val fontFamily = LocalTickFontFamily.current
    val fontScaleSp = settings.fontSizeSp

    var tasks by remember { mutableStateOf(storageRepo.loadTasks()) }
    var doneLines by remember { mutableStateOf(storageRepo.loadDoneLines()) }
    var rulesText by remember { mutableStateOf(storageRepo.loadRulesText()) }
    val activeRules = remember(rulesText, settings.defaultTimedReminder, settings.defaultDateOnlyReminder) {
        RulesEngine.parse(rulesText).copy(
            timedReminderDefault = settings.defaultTimedReminder,
            dateOnlyReminderDefault = settings.defaultDateOnlyReminder
        )
    }

    var notes by remember { mutableStateOf(storageRepo.loadNotes()) }
    var selectedNoteName by remember { mutableStateOf(notes.firstOrNull()?.filename ?: "architecture.md") }
    var noteEditMode by remember { mutableStateOf(false) }
    var noteDraftName by remember { mutableStateOf("") }
    var noteDraftText by remember { mutableStateOf("") }

    val tabKeys = listOf("todo.txt", "notes/", "settings")
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabKeys.size })
    val activeTab = tabKeys[pagerState.currentPage]

    var activeFilter by remember { mutableStateOf("all") }
    var rawTodoMode by remember { mutableStateOf(false) }
    var rawTodoDraft by remember { mutableStateOf("") }
    var editingTask by remember { mutableStateOf<TodoTask?>(null) }
    var editingTaskDraft by remember { mutableStateOf("") }

    var smartInput by remember { mutableStateOf("") }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var showGranularComposer by remember { mutableStateOf(false) }
    var customOffsetAmount by remember { mutableStateOf("1.5") }
    var customOffsetUnit by remember { mutableStateOf("hrs") }
    var customExactDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var customExactTime by remember { mutableStateOf("09:00") }
    var customRemType by remember { mutableStateOf("n") }

    var undoSnapshot by remember { mutableStateOf<List<TodoTask>?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val scheduler = remember { ReminderScheduler(context) }

    fun persistAndSchedule(updatedTasks: List<TodoTask>, exactDelayMillisForLastTask: Long? = null) {
        val numbered = updatedTasks.mapIndexed { idx, t -> t.copy(lineNumber = idx + 1) }
        tasks = numbered
        storageRepo.saveTasks(numbered)

        val nowZdt = ZonedDateTime.now()
        numbered.forEachIndexed { idx, t ->
            val instances = ReminderScheduleCalculator.computeSchedulesForTask(t, nowZdt)
            instances.forEach { inst ->
                val isLastAdded = (idx == numbered.lastIndex && exactDelayMillisForLastTask != null)
                val targetMs = if (isLastAdded) {
                    System.currentTimeMillis() + exactDelayMillisForLastTask!!
                } else {
                    inst.triggerAt.toInstant().toEpochMilli()
                }
                if (targetMs > System.currentTimeMillis() - 5_000L) {
                    scheduler.scheduleInstance(inst, overrideTriggerEpochMs = targetMs)
                    // Also run an in-process timer for short reminders (<= 2 hours) so 1m / 1hr
                    // fires to the exact millisecond while the process is alive
                    val delayMs = targetMs - System.currentTimeMillis()
                    if (isLastAdded && delayMs in 1_000L..7_200_000L) {
                        coroutineScope.launch {
                            delay(delayMs)
                            scheduler.fireImmediateNotification(
                                taskTitle = t.description,
                                rawSpec = inst.spec.rawSpec,
                                typeCode = inst.spec.type.code
                            )
                        }
                    }
                }
            }
        }
    }

    // Save state automatically whenever app goes to background (ON_STOP / ON_PAUSE)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                storageRepo.saveTasks(tasks)
                storageRepo.saveDoneLines(doneLines)
                storageRepo.saveRulesText(rulesText)
                if (noteEditMode && noteDraftName.isNotBlank()) {
                    notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                tasks = storageRepo.loadTasks()
                notes = storageRepo.loadNotes()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Listen for Mark Done action from Android notification
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                tasks = storageRepo.loadTasks()
                statusBanner = "updated from notification"
            }
        }
        val filter = IntentFilter(ReminderScheduler.ACTION_TASKS_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Standard Android Back Gesture handling
    BackHandler(
        enabled = editingTask != null || showGranularComposer || rawTodoMode || noteEditMode || pagerState.currentPage != 0
    ) {
        when {
            editingTask != null -> editingTask = null
            showGranularComposer -> showGranularComposer = false
            rawTodoMode -> rawTodoMode = false
            noteEditMode -> {
                if (noteDraftName.isNotBlank()) {
                    notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                }
                noteEditMode = false
            }
            pagerState.currentPage != 0 -> {
                coroutineScope.launch { pagerState.animateScrollToPage(0) }
            }
        }
    }

    // Android SAF Folder Picker launcher with persistable permission
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            onUpdateSettings(settings.copy(customSafUri = uri.toString()))
            storageRepo.saveTasks(tasks)
            statusBanner = "saved folder: ${uri.lastPathSegment ?: uri}"
        }
    }

    val currentNow = LocalDateTime.now()
    val parsePreview = remember(smartInput, disabledSpans, activeRules) {
        if (smartInput.isBlank()) null
        else SmartAddParser.parse(smartInput, currentNow, activeRules, disabledSpans)
    }

    fun submitSmartAdd() {
        val res = parsePreview ?: return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (res.isNoteCapture) {
            val fn = NotesRepository.toHumanReadableFilename(res.noteTitle ?: "quick-note", ".md")
            notes = storageRepo.saveNote(fn, "# ${res.strippedTitle}\n")
            selectedNoteName = fn
            smartInput = ""
            disabledSpans = emptySet()
            focusManager.clearFocus()
            coroutineScope.launch { pagerState.animateScrollToPage(1) }
        } else {
            undoSnapshot = tasks
            val newTask = TodoParser.parseLine(res.formattedTodoLine, tasks.size + 1)
            val updatedList = tasks + newTask
            persistAndSchedule(updatedList, exactDelayMillisForLastTask = res.exactOffsetMillis)
            smartInput = ""
            disabledSpans = emptySet()
            showGranularComposer = false
            focusManager.clearFocus()
            val remInfo = if (newTask.reminderSpecs.isNotEmpty() && newTask.dueTime != null) {
                " • reminder set for ${newTask.dueDate} ${newTask.dueTime}"
            } else ""
            statusBanner = "saved to todo.txt$remInfo"
        }
    }

    val dynamicProjects = remember(tasks) { tasks.flatMap { it.projects }.distinct().map { "+$it" } }
    val dynamicContexts = remember(tasks) { tasks.flatMap { it.contexts }.distinct().map { "@$it" } }

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
        // Minimal top navigation bar (supports tap AND horizontal swipe via HorizontalPager)
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
                    0 to "todo.txt ($openCount)",
                    1 to "notes/ (${notes.size})",
                    2 to "settings"
                ).forEach { (pageIdx, tabLabel) ->
                    val selected = pagerState.currentPage == pageIdx
                    Text(
                        text = if (selected) "* $tabLabel" else "  $tabLabel",
                        color = if (selected) palette.reminder else palette.fgMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable {
                            coroutineScope.launch { pagerState.animateScrollToPage(pageIdx) }
                        }
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

        // Status / Undo Banner
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
                    fontFamily = fontFamily,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (undoSnapshot != null) {
                        Text(
                            text = "[undo]",
                            color = palette.project,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = fontFamily,
                            modifier = Modifier.clickable {
                                persistAndSchedule(undoSnapshot!!)
                                undoSnapshot = null
                                statusBanner = null
                            }
                        )
                    }
                    Text(
                        text = "[x]",
                        color = palette.fgMuted,
                        fontSize = 11.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable { statusBanner = null }
                    )
                }
            }
        }

        // Inline Task Editor & Granular Reminder Modal
        if (editingTask != null) {
            val target = editingTask!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(palette.bgElevated)
                    .border(1.dp, palette.project)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "edit line ${target.lineNumber} (or attach granular reminder)",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = fontFamily
                    )
                    Text(
                        text = "[cancel]",
                        color = palette.fgMuted,
                        fontSize = 12.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable { editingTask = null }
                    )
                }
                BasicTextField(
                    value = editingTaskDraft,
                    onValueChange = { editingTaskDraft = it },
                    textStyle = TextStyle(fontFamily = fontFamily, color = palette.fg, fontSize = fontScaleSp.sp),
                    cursorBrush = SolidColor(palette.project),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.bg)
                        .border(1.dp, palette.border)
                        .padding(8.dp)
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "remind in:", color = palette.fgMuted, fontSize = 11.sp, fontFamily = fontFamily)
                    listOf(
                        "1m" to 1L,
                        "5m" to 5L,
                        "15m" to 15L,
                        "30m" to 30L,
                        "1h" to 60L,
                        "1.5h" to 90L,
                        "2h" to 120L,
                        "1d" to 1440L
                    ).forEach { (label, mins) ->
                        Text(
                            text = "[+$label]",
                            color = palette.reminder,
                            fontSize = 11.sp,
                            fontFamily = fontFamily,
                            modifier = Modifier.clickable {
                                val dt = LocalDateTime.now().plusMinutes(mins)
                                val dStr = dt.toLocalDate().toString()
                                val tStr = "%02d:%02d".format(dt.hour, dt.minute)
                                var parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                                parsed = TodoParser.withUpdatedTag(parsed, "due", dStr)
                                parsed = TodoParser.withUpdatedTag(parsed, "duetime", tStr)
                                parsed = TodoParser.withReplacedMultiTag(parsed, "rem", listOf("0m/n"))
                                editingTaskDraft = TodoParser.serializeLine(parsed)
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "[delete task]",
                        color = palette.priorityA,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable {
                            undoSnapshot = tasks
                            persistAndSchedule(tasks.filterNot { it.lineNumber == target.lineNumber })
                            editingTask = null
                            statusBanner = "deleted line ${target.lineNumber}"
                        }
                    )
                    Text(
                        text = "[save changes]",
                        color = palette.reminder,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable {
                            undoSnapshot = tasks
                            val reParsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber).copy(isDirty = true)
                            persistAndSchedule(
                                tasks.map { if (it.lineNumber == target.lineNumber) reParsed else it }
                            )
                            editingTask = null
                            statusBanner = "saved line ${target.lineNumber}"
                        }
                    )
                }
            }
        }

        // Swipeable HorizontalPager across todo.txt, notes/, and settings
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { pageIndex ->
            when (pageIndex) {
                0 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Dynamic Filter Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            (listOf("all", "open", "due") + dynamicProjects + dynamicContexts).forEach { f ->
                                val active = activeFilter == f && !rawTodoMode
                                val filterColor = when {
                                    active -> palette.reminder
                                    f.startsWith("+") -> palette.project
                                    f.startsWith("@") -> palette.contextTag
                                    else -> palette.fgMuted
                                }
                                Text(
                                    text = if (active) "[$f]" else f,
                                    color = filterColor,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    fontFamily = fontFamily,
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
                                fontFamily = fontFamily,
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
                                fontFamily = fontFamily,
                                modifier = Modifier.clickable {
                                    val completed = tasks.filter { it.isCompleted }
                                    if (completed.isNotEmpty()) {
                                        undoSnapshot = tasks
                                        doneLines = doneLines + completed.map { TodoParser.serializeLine(it) }
                                        storageRepo.saveDoneLines(doneLines)
                                        persistAndSchedule(tasks.filterNot { it.isCompleted })
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
                                    text = "file: ${storageRepo.todoFile.absolutePath}",
                                    color = palette.fgMuted,
                                    fontSize = 11.sp,
                                    fontFamily = fontFamily
                                )
                                BasicTextField(
                                    value = rawTodoDraft,
                                    onValueChange = { rawTodoDraft = it },
                                    textStyle = TextStyle(
                                        fontFamily = fontFamily,
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
                                        fontFamily = fontFamily,
                                        modifier = Modifier
                                            .clickable {
                                                undoSnapshot = tasks
                                                persistAndSchedule(TodoParser.parseFile(rawTodoDraft))
                                                rawTodoMode = false
                                                statusBanner = "saved todo.txt to disk"
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
                                            text = "0 tasks. type below to add (swipe right on any task to complete, swipe left to delete).",
                                            color = palette.fgMuted,
                                            fontSize = fontScaleSp.sp,
                                            fontFamily = fontFamily,
                                            modifier = Modifier.padding(vertical = 16.dp)
                                        )
                                    }
                                } else {
                                    items(filteredTasks, key = { "${it.lineNumber}:${it.rawLine}" }) { task ->
                                        SwipeableTaskRow(
                                            task = task,
                                            fontSizeSp = fontScaleSp,
                                            onToggleDone = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                                                persistAndSchedule(nextList)
                                                statusBanner = if (updated.second != null) {
                                                    "completed & spawned recurring task"
                                                } else {
                                                    "saved line ${task.lineNumber} to todo.txt"
                                                }
                                            },
                                            onDeleteTask = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                undoSnapshot = tasks
                                                persistAndSchedule(tasks.filterNot { it.lineNumber == task.lineNumber })
                                                statusBanner = "deleted '${task.description}'"
                                            },
                                            onEditTask = {
                                                editingTask = task
                                                editingTaskDraft = TodoParser.serializeLine(task)
                                            },
                                            onOpenNote = { noteFilename ->
                                                val match = notes.firstOrNull {
                                                    it.filename.equals(noteFilename, ignoreCase = true)
                                                }
                                                if (match != null) {
                                                    selectedNoteName = match.filename
                                                }
                                                coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                            },
                                            onFireReminderNow = {
                                                val spec = task.reminderSpecs.firstOrNull() ?: "0m/n"
                                                val typeCode = if ("/a" in spec) "a" else "n"
                                                scheduler.fireImmediateNotification(
                                                    taskTitle = task.description,
                                                    rawSpec = spec,
                                                    typeCode = typeCode
                                                )
                                                statusBanner = "fired notification for '${task.description}'"
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Granular Reminder & Date/Time Composer Drawer
                        if (showGranularComposer) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(palette.bgElevated)
                                    .border(1.dp, palette.border)
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "granular reminder & date/time composer",
                                        color = palette.project,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = fontFamily
                                    )
                                    Text(
                                        text = "[close]",
                                        color = palette.fgMuted,
                                        fontSize = 11.sp,
                                        fontFamily = fontFamily,
                                        modifier = Modifier.clickable { showGranularComposer = false }
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "in 1m", "in 5m", "in 15m", "in 30m",
                                        "in 1 hr", "in 1.5 hrs", "in 2 hrs", "in 1 day", "in 1 week", "in 1 month"
                                    ).forEach { chip ->
                                        Text(
                                            text = "[$chip]",
                                            color = palette.reminder,
                                            fontSize = 11.sp,
                                            fontFamily = fontFamily,
                                            modifier = Modifier.clickable {
                                                smartInput = if (smartInput.isBlank()) chip else "${smartInput.trim()} $chip"
                                            }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "in", color = palette.fgMuted, fontSize = 11.sp, fontFamily = fontFamily)
                                    BasicTextField(
                                        value = customOffsetAmount,
                                        onValueChange = { customOffsetAmount = it },
                                        textStyle = TextStyle(fontFamily = fontFamily, color = palette.fg, fontSize = 11.sp),
                                        cursorBrush = SolidColor(palette.project),
                                        modifier = Modifier
                                            .width(48.dp)
                                            .background(palette.bg)
                                            .border(1.dp, palette.border)
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                    listOf("m", "hrs", "days", "weeks", "months").forEach { u ->
                                        val sel = customOffsetUnit == u
                                        Text(
                                            text = if (sel) "[$u]" else u,
                                            color = if (sel) palette.project else palette.fgMuted,
                                            fontSize = 11.sp,
                                            fontFamily = fontFamily,
                                            modifier = Modifier.clickable { customOffsetUnit = u }
                                        )
                                    }
                                    Text(
                                        text = "[+apply]",
                                        color = palette.reminder,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = fontFamily,
                                        modifier = Modifier.clickable {
                                            val phrase = "in ${customOffsetAmount.trim()} $customOffsetUnit"
                                            smartInput = if (smartInput.isBlank()) phrase else "${smartInput.trim()} $phrase"
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "exact:", color = palette.fgMuted, fontSize = 11.sp, fontFamily = fontFamily)
                                    BasicTextField(
                                        value = customExactDate,
                                        onValueChange = { customExactDate = it },
                                        textStyle = TextStyle(fontFamily = fontFamily, color = palette.fg, fontSize = 11.sp),
                                        cursorBrush = SolidColor(palette.project),
                                        modifier = Modifier
                                            .width(88.dp)
                                            .background(palette.bg)
                                            .border(1.dp, palette.border)
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                    BasicTextField(
                                        value = customExactTime,
                                        onValueChange = { customExactTime = it },
                                        textStyle = TextStyle(fontFamily = fontFamily, color = palette.fg, fontSize = 11.sp),
                                        cursorBrush = SolidColor(palette.project),
                                        modifier = Modifier
                                            .width(52.dp)
                                            .background(palette.bg)
                                            .border(1.dp, palette.border)
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                    listOf("n" to "notif(/n)", "a" to "alarm(/a)").forEach { (code, label) ->
                                        val sel = customRemType == code
                                        Text(
                                            text = if (sel) "[$label]" else label,
                                            color = if (sel) palette.priorityA else palette.fgMuted,
                                            fontSize = 11.sp,
                                            fontFamily = fontFamily,
                                            modifier = Modifier.clickable { customRemType = code }
                                        )
                                    }
                                    Text(
                                        text = "[+set exact]",
                                        color = palette.reminder,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = fontFamily,
                                        modifier = Modifier.clickable {
                                            val tags = "due:${customExactDate.trim()} duetime:${customExactTime.trim()} rem:0m/$customRemType"
                                            smartInput = if (smartInput.isBlank()) tags else "${smartInput.trim()} $tags"
                                        }
                                    )
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
                                    fontSize = 11.sp,
                                    fontFamily = fontFamily
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
                                            fontFamily = fontFamily,
                                            modifier = Modifier.clickable {
                                                disabledSpans = disabledSpans + span.id
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Bottom One-Handed Capture Bar with IME Done Action
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.bgElevated)
                                .border(1.dp, palette.border)
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showGranularComposer) "[-time] " else "[+time] ",
                                color = palette.priorityB,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                fontFamily = fontFamily,
                                modifier = Modifier.clickable { showGranularComposer = !showGranularComposer }
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                if (smartInput.isEmpty()) {
                                    Text(
                                        text = "do something in 1m or 1 hr...",
                                        color = palette.fgMuted,
                                        fontSize = fontScaleSp.sp,
                                        fontFamily = fontFamily,
                                        maxLines = 1
                                    )
                                }
                                BasicTextField(
                                    value = smartInput,
                                    onValueChange = {
                                        smartInput = it
                                        disabledSpans = emptySet()
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submitSmartAdd() }),
                                    textStyle = TextStyle(
                                        fontFamily = fontFamily,
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
                                fontFamily = fontFamily,
                                modifier = Modifier
                                    .clickable { submitSmartAdd() }
                                    .padding(start = 8.dp)
                            )
                        }
                    }
                }

                1 -> {
                    // NOTES TAB (100% persisted to disk in notes/)
                    val currentNote = notes.firstOrNull { it.filename == selectedNoteName } ?: notes.firstOrNull()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            notes.forEach { n ->
                                val sel = n.filename == currentNote?.filename
                                Text(
                                    text = if (sel) "[* ${n.filename}]" else n.filename,
                                    color = if (sel) palette.project else palette.fgMuted,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    fontFamily = fontFamily,
                                    modifier = Modifier.clickable {
                                        if (noteEditMode && noteDraftName.isNotBlank()) {
                                            notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                                        }
                                        selectedNoteName = n.filename
                                        noteEditMode = false
                                    }
                                )
                            }
                            Text(
                                text = "[+new note]",
                                color = palette.reminder,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = fontFamily,
                                modifier = Modifier.clickable {
                                    val name = "note-${notes.size + 1}.md"
                                    val initialBody = "# $name\n\nwrite plain text or markdown here."
                                    notes = storageRepo.saveNote(name, initialBody)
                                    selectedNoteName = name
                                    noteDraftName = name
                                    noteDraftText = initialBody
                                    noteEditMode = true
                                    statusBanner = "created & saved notes/$name"
                                }
                            )
                        }

                        if (currentNote != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "file: ${File(storageRepo.notesDir, currentNote.filename).absolutePath}",
                                    color = palette.fgMuted,
                                    fontSize = 10.sp,
                                    fontFamily = fontFamily,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (notes.size > 1) {
                                        Text(
                                            text = "[delete]",
                                            color = palette.priorityA,
                                            fontSize = 12.sp,
                                            fontFamily = fontFamily,
                                            modifier = Modifier.clickable {
                                                val updated = storageRepo.deleteNote(currentNote.filename)
                                                notes = updated
                                                selectedNoteName = updated.firstOrNull()?.filename ?: ""
                                                noteEditMode = false
                                                statusBanner = "deleted notes/${currentNote.filename}"
                                            }
                                        )
                                    }
                                    Text(
                                        text = if (noteEditMode) "[save note]" else "[edit note]",
                                        color = palette.reminder,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        fontFamily = fontFamily,
                                        modifier = Modifier.clickable {
                                            if (noteEditMode) {
                                                notes = storageRepo.saveNote(currentNote.filename, noteDraftText)
                                                noteEditMode = false
                                                statusBanner = "saved notes/${currentNote.filename} to disk"
                                            } else {
                                                noteDraftName = currentNote.filename
                                                noteDraftText = currentNote.rawContent
                                                noteEditMode = true
                                            }
                                        }
                                    )
                                }
                            }

                            if (noteEditMode) {
                                BasicTextField(
                                    value = noteDraftText,
                                    onValueChange = {
                                        noteDraftText = it
                                        // Auto-save note on every keystroke so closing the app never loses note edits!
                                        notes = storageRepo.saveNote(currentNote.filename, it)
                                    },
                                    textStyle = TextStyle(
                                        fontFamily = fontFamily,
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
                                        .clickable {
                                            noteDraftName = currentNote.filename
                                            noteDraftText = currentNote.rawContent
                                            noteEditMode = true
                                        }
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
                                            fontSize = fontScaleSp.sp,
                                            fontFamily = fontFamily
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    SettingsScreen(
                        baseDir = storageRepo.baseDir,
                        storageFolderPath = settings.customSafUri ?: storageRepo.baseDir.absolutePath,
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                        onPickFolderWithSaf = { folderPickerLauncher.launch(null) },
                        onOpenSavedFileLocation = {
                            val todoFile = storageRepo.todoFile
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "todo.txt (${todoFile.absolutePath})")
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Saved location: ${todoFile.absolutePath}\n\n" +
                                        (runCatching { todoFile.readText() }.getOrDefault(""))
                                )
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Open or export todo.txt (${storageRepo.baseDir.absolutePath})")
                                )
                            }
                            statusBanner = "active file: ${todoFile.absolutePath}"
                        },
                        rulesText = rulesText,
                        onSaveRules = {
                            rulesText = it
                            storageRepo.saveRulesText(it)
                            statusBanner = "saved tick.rules.txt to disk"
                        },
                        onFireImmediateNotification = {
                            scheduler.fireImmediateNotification(
                                taskTitle = "test notification • tick.txt",
                                rawSpec = "0m/n",
                                typeCode = "n"
                            )
                            statusBanner = "sent Android notification (check status bar)"
                        },
                        onTriggerTestAlarm = {
                            scheduler.fireImmediateNotification(
                                taskTitle = "test lockscreen alarm (/a)",
                                rawSpec = "0m/a/svr",
                                typeCode = "a"
                            )
                        },
                        onScheduleQuickTestIn1Min = {
                            val triggerLocal = LocalDateTime.now().plusMinutes(1)
                            val dStr = triggerLocal.toLocalDate().toString()
                            val tStr = "%02d:%02d".format(triggerLocal.hour, triggerLocal.minute)
                            val line = "${LocalDate.now()} check chocotat +chocotat @factory due:$dStr duetime:$tStr rem:0m/n"
                            val newTask = TodoParser.parseLine(line, tasks.size + 1)
                            persistAndSchedule(tasks + newTask, exactDelayMillisForLastTask = 60_000L)
                            statusBanner = "scheduled reminder for $dStr $tStr (fires in 60s)"
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Supports standard Android touch & swipe gestures:
 * - Swipe Right (> 110px) -> Toggle Done / Incomplete `[x]`
 * - Swipe Left (< -110px) -> Delete task (with Undo)
 * - Tap checkbox `[ ]` -> Toggle Done
 * - Tap task text -> Open inline task & reminder editor
 */
@Composable
private fun SwipeableTaskRow(
    task: TodoTask,
    fontSizeSp: Int,
    onToggleDone: () -> Unit,
    onDeleteTask: () -> Unit,
    onEditTask: () -> Unit,
    onOpenNote: (String) -> Unit,
    onFireReminderNow: () -> Unit
) {
    val palette = LocalTickPalette.current
    val fontFamily = LocalTickFontFamily.current
    var dragOffsetX by remember { mutableStateOf(0f) }

    val annotated = buildAnnotatedString {
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
            withStyle(SpanStyle(color = palette.project, fontWeight = FontWeight.Bold)) { append("+$p ") }
        }
        task.contexts.forEach { c ->
            withStyle(SpanStyle(color = palette.contextTag, fontWeight = FontWeight.Bold)) { append("@$c ") }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(task.lineNumber, task.rawLine) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragOffsetX > 110f) {
                            onToggleDone()
                        } else if (dragOffsetX < -110f) {
                            onDeleteTask()
                        }
                        dragOffsetX = 0f
                    },
                    onDragCancel = { dragOffsetX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragOffsetX = (dragOffsetX + dragAmount).coerceIn(-220f, 220f)
                    }
                )
            }
    ) {
        // Background swipe hint indicator
        if (dragOffsetX > 25f || dragOffsetX < -25f) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (dragOffsetX > 0) palette.bgElevated else palette.bgElevated)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = if (dragOffsetX > 0) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (dragOffsetX > 0) "[swipe -> complete]" else "[<- swipe delete]",
                    color = if (dragOffsetX > 0) palette.reminder else palette.priorityA,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = fontFamily
                )
            }
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(palette.bg)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = if (task.isCompleted) "[x] " else "[ ] ",
                color = if (task.isCompleted) palette.fgMuted else palette.reminder,
                fontWeight = FontWeight.Bold,
                fontSize = fontSizeSp.sp,
                fontFamily = fontFamily,
                modifier = Modifier.clickable { onToggleDone() }
            )
            Text(
                text = annotated,
                fontSize = fontSizeSp.sp,
                fontFamily = fontFamily,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEditTask() }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task.linkedNotes.isNotEmpty()) {
                    Text(
                        text = "[note]",
                        color = palette.noteLink,
                        fontSize = 11.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable { onOpenNote(task.linkedNotes.first()) }
                    )
                }
                if (task.reminderSpecs.isNotEmpty()) {
                    Text(
                        text = "[bell]",
                        color = palette.reminder,
                        fontSize = 11.sp,
                        fontFamily = fontFamily,
                        modifier = Modifier.clickable { onFireReminderNow() }
                    )
                }
            }
        }
    }
}
