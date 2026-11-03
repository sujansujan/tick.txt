package com.ticktxt.app.ui.screens

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ticktxt.app.core.calendar.GoogleCalendarSyncManager
import com.ticktxt.app.core.nlp.RulesEngine
import com.ticktxt.app.core.nlp.SmartAddParser
import com.ticktxt.app.core.notes.NoteFile
import com.ticktxt.app.core.notes.NotesRepository
import com.ticktxt.app.core.notes.PlainTextSearchIndex
import com.ticktxt.app.core.reminders.ReminderScheduleCalculator
import com.ticktxt.app.core.reminders.ReminderScheduler
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.TickSemanticPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Optimization #5: Pre-compiled Regexes
private val DUE_TAG_REGEX = Regex("\\bdue:\\S+")
private val DUETIME_TAG_REGEX = Regex("\\bduetime:\\S+")
private val CHECKBOX_LINE_REGEX = Regex("^(\\s*[-*]\\s+\\[)([ xX])(\\]\\s+.*)$")
private val INLINE_MD_TOKEN_REGEX = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`)")

private fun toggleTagInText(rawText: String, tag: String): String {
    val tokens = rawText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
    if (tokens.contains(tag)) {
        tokens.removeAll { it == tag }
    } else {
        tokens.add(tag)
    }
    return tokens.joinToString(" ")
}

private fun toggleMarkdownCheckboxAtLine(rawMarkdown: String, targetLineIndex: Int): String {
    val lines = rawMarkdown.lines().toMutableList()
    if (targetLineIndex !in lines.indices) return rawMarkdown
    val line = lines[targetLineIndex]
    val match = CHECKBOX_LINE_REGEX.matchEntire(line)
    if (match != null) {
        val currentMark = match.groupValues[2]
        val nextMark = if (currentMark.equals("x", ignoreCase = true)) " " else "x"
        lines[targetLineIndex] = "${match.groupValues[1]}$nextMark${match.groupValues[3]}"
    }
    return lines.joinToString("\n")
}

private fun renderInlineMarkdown(
    text: String,
    palette: TickSemanticPalette,
    baseColor: Color = palette.fg
): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        for (match in INLINE_MD_TOKEN_REGEX.findAll(text)) {
            if (match.range.first > cursor) {
                withStyle(SpanStyle(color = baseColor)) {
                    append(text.substring(cursor, match.range.first))
                }
            }
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                    withStyle(SpanStyle(color = palette.fg, fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("*") && token.endsWith("*") && token.length > 2 -> {
                    withStyle(SpanStyle(color = palette.fg, fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                    withStyle(
                        SpanStyle(
                            color = palette.reminder,
                            background = palette.bgElevated,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(" " + token.substring(1, token.length - 1) + " ")
                    }
                }
                else -> {
                    withStyle(SpanStyle(color = baseColor)) {
                        append(token)
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            withStyle(SpanStyle(color = baseColor)) {
                append(text.substring(cursor))
            }
        }
    }
}

data class ActiveAlarmPayload(
    val reminderId: String,
    val taskId: String,
    val taskTitle: String,
    val rawSpec: String,
    val notifId: Int = 100
)

/**
 * Main screen following a strict, uniform ASCII UX Design System:
 * - 4-Layer Offline Project (`+`) & Context (`@`) Inference Engine (`RulesEngine.withHistoricalLearning`)
 * - Google Keep-Style 2-Column Masonry Grid of Markdown-only (`.md`) Notes + Shaded Markdown Note Popup
 * - Pure Full-Screen + Persistent Shade Notifications.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    val appFont = LocalTickFontFamily.current
    val monoFont = FontFamily.Monospace
    val fontScaleSp = settings.fontSizeSp

    var tasks by remember { mutableStateOf(storageRepo.loadTasks()) }
    var doneLines by remember { mutableStateOf(storageRepo.loadDoneLines()) }
    var rulesText by remember { mutableStateOf(storageRepo.loadRulesText()) }

    // 4-Layer Offline Inference Config: combines `tick.rules.txt` with historical co-occurrences
    // and known vocabulary learned from `todo.txt` + `done.txt`!
    val activeRules = remember(rulesText, settings.defaultTimedReminder, settings.defaultDateOnlyReminder, tasks, doneLines) {
        val base = RulesEngine.parse(rulesText).copy(
            timedReminderDefault = settings.defaultTimedReminder,
            dateOnlyReminderDefault = settings.defaultDateOnlyReminder
        )
        RulesEngine.withHistoricalLearning(base, tasks, doneLines)
    }

    var notes by remember { mutableStateOf(storageRepo.loadNotes()) }
    // Shaded Popup state for Google Keep-style Markdown Notes
    var activeNotePopupFilename by remember { mutableStateOf<String?>(null) }
    var noteEditMode by remember { mutableStateOf(false) }
    var noteDraftName by remember { mutableStateOf("") }
    var noteDraftText by remember { mutableStateOf("") }

    // Optimization #2: 300ms debounced background I/O for Markdown Notes editor typing
    LaunchedEffect(noteDraftText, noteDraftName, noteEditMode, activeNotePopupFilename) {
        if (activeNotePopupFilename != null && noteEditMode && noteDraftName.isNotBlank()) {
            delay(300L)
            val safeMdName = NotesRepository.toHumanReadableFilename(noteDraftName, ".md")
            val saved = withContext(Dispatchers.IO) {
                storageRepo.saveNote(safeMdName, noteDraftText)
            }
            notes = saved
        }
    }

    // Pager for swiping between tabs (0 = Tasks, 1 = Notes, 2 = Settings)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var activeFilter by remember { mutableStateOf("open") }

    // Optimization #8: Inline Search Bar (`[/]`) powered by PlainTextSearchIndex
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val searchIndex = remember(tasks, notes) {
        PlainTextSearchIndex().apply { rebuildFromFiles(tasks, notes) }
    }
    val searchHits = remember(searchIndex, searchQuery) {
        if (searchQuery.isBlank()) emptyList() else searchIndex.search(searchQuery)
    }
    val matchedNoteHits = remember(searchHits) {
        searchHits.filter { it.kind == "note" }
    }

    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            delay(80L)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    // Navigation history for Back Gesture:
    var lastCommittedPage by remember { mutableStateOf(0) }
    var previousPlace by remember { mutableStateOf<Pair<Int, String>?>(null) }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != lastCommittedPage) {
            previousPlace = Pair(lastCommittedPage, activeFilter)
            lastCommittedPage = pagerState.currentPage
        }
    }

    fun updateFilterWithHistory(newFilter: String) {
        if (newFilter != activeFilter) {
            previousPlace = Pair(pagerState.currentPage, activeFilter)
            activeFilter = newFilter
        }
    }

    // Popups with shaded background
    var editingTask by remember { mutableStateOf<TodoTask?>(null) }
    var editingTaskDraft by remember { mutableStateOf("") }
    var showEditSnoozeWheel by remember { mutableStateOf(false) }

    var showAddTaskPopup by remember { mutableStateOf(false) }
    var smartInput by remember { mutableStateOf("") }
    var addTaskPriority by remember { mutableStateOf<Char?>(null) }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var showAddSnoozeWheel by remember { mutableStateOf(false) }

    // Optimization #7: Auto-Focus Keyboard when `[+ Add]` Popup opens
    val addInputFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showAddTaskPopup, showAddSnoozeWheel) {
        if (showAddTaskPopup && !showAddSnoozeWheel) {
            delay(80L)
            runCatching { addInputFocusRequester.requestFocus() }
        }
    }

    var showTagsPopup by remember { mutableStateOf(false) }

    var activeAlarmOverlay by remember { mutableStateOf<ActiveAlarmPayload?>(null) }

    var undoSnapshot by remember { mutableStateOf<List<TodoTask>?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    // Optimization #3: Lifecycle-Aware Minute-Boundary Clock Tick
    var isAppResumed by remember { mutableStateOf(true) }
    var currentNow by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(isAppResumed) {
        while (isAppResumed) {
            currentNow = LocalDateTime.now()
            val delayToNextMinuteMs = (60_000L - (System.currentTimeMillis() % 60_000L)).coerceIn(1_000L, 60_000L)
            delay(delayToNextMinuteMs)
        }
    }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(3500L)
            statusBanner = null
        }
    }

    val scheduler = remember { ReminderScheduler(context) }
    val gcalManager = remember { GoogleCalendarSyncManager(context) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            coroutineScope.launch(Dispatchers.IO) {
                val (syncedList, count) = gcalManager.syncAllDueTasks(tasks)
                storageRepo.saveTasks(syncedList)
                withContext(Dispatchers.Main) {
                    tasks = syncedList
                    statusBanner = "[# GCal] Synced $count task(s)"
                }
            }
        } else {
            statusBanner = "[!] Calendar permission needed"
        }
    }

    fun cancelShadeNotification(notifId: Int) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }
    }

    fun triggerFullNotification(
        taskTitle: String,
        rawSpec: String = "0m/n",
        taskId: String = "immediate"
    ) {
        val remId = "$taskId|$rawSpec"
        val notifId = (remId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100)
        scheduler.fireImmediateNotification(
            taskTitle = taskTitle,
            rawSpec = rawSpec,
            typeCode = "n",
            taskId = taskId
        )
        activeAlarmOverlay = ActiveAlarmPayload(
            reminderId = remId,
            taskId = taskId,
            taskTitle = taskTitle,
            rawSpec = rawSpec,
            notifId = notifId
        )
    }

    fun scheduleExactDelayNotification(task: TodoTask, delayMs: Long, rawSpec: String = "0m/n") {
        val safeDelay = delayMs.coerceAtLeast(1_000L)
        val targetEpochMs = System.currentTimeMillis() + safeDelay
        scheduler.scheduleRaw(
            reminderId = "${task.stableId}|$rawSpec",
            taskStableId = task.stableId,
            rawSpec = rawSpec,
            taskDescription = task.description,
            typeCode = "n",
            triggerEpochMs = targetEpochMs
        )
        if (safeDelay <= 7_200_000L) {
            coroutineScope.launch {
                delay(safeDelay)
                triggerFullNotification(
                    taskTitle = task.description,
                    rawSpec = rawSpec,
                    taskId = task.stableId
                )
            }
        }
    }

    fun learnRuleFromSavedTask(task: TodoTask) {
        val updatedRules = RulesEngine.learnFromTask(rulesText, task)
        if (updatedRules != rulesText) {
            rulesText = updatedRules
            coroutineScope.launch(Dispatchers.IO) {
                storageRepo.saveRulesText(updatedRules)
            }
        }
    }

    // Optimization #1 & #2: Asynchronous Disk I/O + Incremental Diff-Based Reminder Scheduling
    fun persistAndSchedule(
        updatedTasks: List<TodoTask>,
        exactDelayMillisForLastTask: Long? = null,
        lastTaskRawSpec: String = "0m/n",
        syncLastToCalendar: Boolean = false
    ) {
        val numbered = updatedTasks.mapIndexed { idx, t -> t.copy(lineNumber = idx + 1) }
        tasks = numbered

        if (exactDelayMillisForLastTask != null && numbered.isNotEmpty()) {
            scheduleExactDelayNotification(numbered.last(), exactDelayMillisForLastTask, lastTaskRawSpec)
        }

        coroutineScope.launch(Dispatchers.IO) {
            var finalTasks = numbered
            if (syncLastToCalendar && settings.autoSyncGoogleCalendar && finalTasks.isNotEmpty()) {
                val last = finalTasks.last()
                if (last.dueDate != null && gcalManager.hasCalendarPermissions()) {
                    val (syncedLast, _) = gcalManager.syncTaskToGoogleCalendar(last)
                    finalTasks = finalTasks.dropLast(1) + syncedLast
                    withContext(Dispatchers.Main) {
                        tasks = finalTasks
                    }
                }
            }
            storageRepo.saveTasks(finalTasks)
            val tasksToSync = if (exactDelayMillisForLastTask != null && finalTasks.isNotEmpty()) {
                finalTasks.dropLast(1)
            } else {
                finalTasks
            }
            scheduler.syncTaskSchedules(tasksToSync, ZonedDateTime.now())
        }
    }

    fun reloadAllFromSafOrDisk(customSafUriStr: String? = null, switchToTasksTab: Boolean = false) {
        coroutineScope.launch(Dispatchers.IO) {
            val res = storageRepo.loadAllFromSafFolder(customSafUriStr)
            scheduler.syncTaskSchedules(res.tasks, ZonedDateTime.now(), forceAll = true)
            withContext(Dispatchers.Main) {
                tasks = res.tasks
                doneLines = res.doneLines
                rulesText = res.rulesText
                notes = res.notes
                activeNotePopupFilename = null
                noteEditMode = false
                statusBanner = "[/ Folder] Loaded ${res.tasks.size} task(s), ${res.notes.size} note(s)"
                if (switchToTasksTab) {
                    pagerState.animateScrollToPage(0)
                }
            }
        }
    }

    fun openNoteInPopup(note: NoteFile, startInEditMode: Boolean = false) {
        activeNotePopupFilename = note.filename
        noteDraftName = note.filename
        noteDraftText = note.bodyContent
        noteEditMode = startInEditMode
    }

    fun createAndOpenNewMarkdownNote() {
        val name = "note-${notes.size + 1}.md"
        val initialBody = "# Note ${notes.size + 1}\n\n- [ ] "
        activeNotePopupFilename = name
        noteDraftName = name
        noteDraftText = initialBody
        noteEditMode = true
        coroutineScope.launch(Dispatchers.IO) {
            val saved = storageRepo.saveNote(name, initialBody)
            withContext(Dispatchers.Main) { notes = saved }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                isAppResumed = false
                val snapshotTasks = tasks
                val snapshotDone = doneLines
                val snapshotRules = rulesText
                val shouldSaveNote = activeNotePopupFilename != null && noteEditMode && noteDraftName.isNotBlank()
                val draftName = noteDraftName
                val draftText = noteDraftText
                coroutineScope.launch(Dispatchers.IO) {
                    storageRepo.saveTasks(snapshotTasks)
                    storageRepo.saveDoneLines(snapshotDone)
                    storageRepo.saveRulesText(snapshotRules)
                    if (shouldSaveNote) {
                        storageRepo.saveNote(draftName, draftText)
                    }
                }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                isAppResumed = true
                currentNow = LocalDateTime.now()
                coroutineScope.launch(Dispatchers.IO) {
                    val res = storageRepo.loadAllFromSafFolder()
                    withContext(Dispatchers.Main) {
                        tasks = res.tasks
                        doneLines = res.doneLines
                        rulesText = res.rulesText
                        notes = res.notes
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ReminderScheduler.ACTION_TASKS_UPDATED -> {
                        tasks = storageRepo.loadTasks()
                        val msg = intent.getStringExtra("extra_status_msg") ?: "Updated todo.txt"
                        statusBanner = msg
                    }
                    ReminderScheduler.ACTION_ALARM_FIRED_UI -> {
                        val remId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: "notif"
                        val tId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: "task"
                        val spec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC) ?: "0m/n"
                        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE) ?: "scheduled task"
                        val nId = intent.getIntExtra("extra_notif_id", (remId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100))
                        activeAlarmOverlay = ActiveAlarmPayload(remId, tId, title, spec, nId)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ReminderScheduler.ACTION_TASKS_UPDATED)
            addAction(ReminderScheduler.ACTION_ALARM_FIRED_UI)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Back gesture state machine:
    // 1. Closes any open wheel / popup / note modal / search
    // 2. Returns to previousPlace (tab/filter) if set
    // 3. Goes to Todo tab (page 0, "open") if not already there
    // 4. Closes the app
    BackHandler(enabled = true) {
        when {
            showEditSnoozeWheel -> showEditSnoozeWheel = false
            showAddSnoozeWheel -> showAddSnoozeWheel = false
            activeAlarmOverlay != null -> {
                AlarmAudioController.stopAll()
                activeAlarmOverlay = null
            }
            editingTask != null -> editingTask = null
            showAddTaskPopup -> showAddTaskPopup = false
            activeNotePopupFilename != null -> {
                if (noteEditMode && noteDraftName.isNotBlank()) {
                    val dName = NotesRepository.toHumanReadableFilename(noteDraftName, ".md")
                    val dText = noteDraftText
                    coroutineScope.launch(Dispatchers.IO) {
                        val saved = storageRepo.saveNote(dName, dText)
                        withContext(Dispatchers.Main) { notes = saved }
                    }
                }
                noteEditMode = false
                activeNotePopupFilename = null
            }
            showTagsPopup -> showTagsPopup = false
            showSearchBar -> {
                searchQuery = ""
                showSearchBar = false
            }
            previousPlace != null && (previousPlace!!.first != pagerState.currentPage || previousPlace!!.second != activeFilter) -> {
                val (prevPage, prevFilter) = previousPlace!!
                previousPlace = null
                activeFilter = prevFilter
                lastCommittedPage = prevPage
                coroutineScope.launch { pagerState.animateScrollToPage(prevPage) }
            }
            pagerState.currentPage != 0 || activeFilter != "open" -> {
                previousPlace = null
                activeFilter = "open"
                lastCommittedPage = 0
                coroutineScope.launch { pagerState.animateScrollToPage(0) }
            }
            else -> {
                (context as? Activity)?.finish()
            }
        }
    }

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
            val uriStr = uri.toString()
            onUpdateSettings(settings.copy(customSafUri = uriStr))
            reloadAllFromSafOrDisk(customSafUriStr = uriStr, switchToTasksTab = true)
        }
    }

    val parsePreview = remember(smartInput, disabledSpans, activeRules, currentNow) {
        if (smartInput.isBlank()) null
        else SmartAddParser.parse(smartInput, currentNow, activeRules, disabledSpans)
    }

    fun submitSmartAdd() {
        val res = parsePreview ?: return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (res.isNoteCapture) {
            val fn = NotesRepository.toHumanReadableFilename(res.noteTitle ?: "quick-note", ".md")
            val content = "# ${res.strippedTitle}\n"
            coroutineScope.launch(Dispatchers.IO) {
                val saved = storageRepo.saveNote(fn, content)
                withContext(Dispatchers.Main) {
                    notes = saved
                }
            }
            smartInput = ""
            addTaskPriority = null
            disabledSpans = emptySet()
            showAddTaskPopup = false
            focusManager.clearFocus()
            statusBanner = null
            coroutineScope.launch { pagerState.animateScrollToPage(1) }
        } else {
            undoSnapshot = tasks
            var newTask = TodoParser.parseLine(res.formattedTodoLine, tasks.size + 1)
            if (addTaskPriority != null && newTask.priority == null) {
                newTask = newTask.copy(priority = addTaskPriority, isDirty = true)
            }
            val chosenSpec = "${settings.defaultTimedReminder.substringBefore("/")}/n"
            if (newTask.dueDate != null || newTask.reminderSpecs.isNotEmpty() || res.exactOffsetMillis != null) {
                newTask = TodoParser.withReplacedMultiTag(newTask, "rem", listOf(chosenSpec))
            }
            learnRuleFromSavedTask(newTask)
            val updatedList = tasks + newTask
            persistAndSchedule(
                updatedList,
                exactDelayMillisForLastTask = res.exactOffsetMillis,
                lastTaskRawSpec = chosenSpec,
                syncLastToCalendar = true
            )
            smartInput = ""
            addTaskPriority = null
            disabledSpans = emptySet()
            showAddTaskPopup = false
            focusManager.clearFocus()
            statusBanner = "[+ Added] ${newTask.description}"
        }
    }

    val dynamicProjects = remember(tasks, activeRules) {
        (tasks.flatMap { it.projects } + activeRules.knownProjects).distinct().map { "+$it" }
    }
    val dynamicContexts = remember(tasks, activeRules) {
        (tasks.flatMap { it.contexts } + activeRules.knownContexts).distinct().map { "@$it" }
    }
    val quickTagChips = remember(dynamicProjects, dynamicContexts) {
        (dynamicProjects + dynamicContexts).take(8)
    }

    // Smart sorting + live search filtering
    val filteredTasks = remember(tasks, activeFilter, searchQuery, searchHits) {
        val matchedTaskIds = if (searchQuery.isBlank()) null else searchHits.filter { it.kind == "task" }.map { it.id }.toSet()
        tasks.filter { task ->
            if (task.isBlankOrComment) return@filter false
            if (matchedTaskIds != null && task.stableId !in matchedTaskIds) return@filter false
            when {
                matchedTaskIds != null -> true
                activeFilter == "open" -> !task.isCompleted
                activeFilter == "due" -> !task.isCompleted && task.dueDate != null
                activeFilter == "done" -> task.isCompleted
                activeFilter.startsWith("+") -> task.projects.contains(activeFilter.drop(1))
                activeFilter.startsWith("@") -> task.contexts.contains(activeFilter.drop(1))
                else -> true
            }
        }.sortedWith(
            compareBy<TodoTask> { it.isCompleted }
                .thenBy { it.priority ?: '[' }
                .thenBy { it.dueDate ?: LocalDate.MAX }
                .thenBy { it.dueTime ?: LocalTime.MAX }
                .thenBy { it.lineNumber }
        )
    }

    // =========================================================================
    // FULL-SCREEN 3-OPTION NOTIFICATION OVERLAY
    // =========================================================================
    if (activeAlarmOverlay != null) {
        val alarm = activeAlarmOverlay!!
        AsciiFullScreenAlarmSurface(
            taskTitle = alarm.taskTitle,
            rawSpec = alarm.rawSpec,
            fontSizeSp = fontScaleSp,
            onSnoozeWithPreset = { presetSpec ->
                AlarmAudioController.stopAll()
                cancelShadeNotification(alarm.notifId)
                val targetZdt = ReminderScheduleCalculator.computeSnoozeTarget(ZonedDateTime.now(), presetSpec)
                val delayMs = (targetZdt.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1_000L)
                val matchingTask = tasks.firstOrNull {
                    it.stableId == alarm.taskId || it.description.equals(alarm.taskTitle, ignoreCase = true)
                }
                if (matchingTask != null) {
                    scheduleExactDelayNotification(matchingTask, delayMs, alarm.rawSpec)
                } else {
                    scheduler.scheduleRaw(
                        reminderId = alarm.reminderId,
                        taskStableId = alarm.taskId,
                        rawSpec = alarm.rawSpec,
                        taskDescription = alarm.taskTitle,
                        typeCode = "n",
                        triggerEpochMs = targetZdt.toInstant().toEpochMilli()
                    )
                    if (delayMs <= 7_200_000L) {
                        coroutineScope.launch {
                            delay(delayMs)
                            triggerFullNotification(alarm.taskTitle, alarm.rawSpec, alarm.taskId)
                        }
                    }
                }
                val fmtTime = targetZdt.format(DateTimeFormatter.ofPattern("HH:mm"))
                statusBanner = "[zZ Snooze] Until $fmtTime"
                activeAlarmOverlay = null
            },
            onDismiss = {
                AlarmAudioController.stopAll()
                statusBanner = "[!] Kept in notification shade"
                activeAlarmOverlay = null
            },
            onMarkDone = {
                AlarmAudioController.stopAll()
                cancelShadeNotification(alarm.notifId)
                undoSnapshot = tasks
                val nextList = tasks.map { t ->
                    if (!t.isCompleted && (t.stableId == alarm.taskId || t.description.equals(alarm.taskTitle, ignoreCase = true))) {
                        TodoParser.markCompleted(t, LocalDate.now()).first
                    } else {
                        t
                    }
                }
                persistAndSchedule(nextList)
                statusBanner = "[✓ Done] ${alarm.taskTitle}"
                activeAlarmOverlay = null
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // =================================================================
            // SWIPEABLE HORIZONTAL PAGER FOR TABS (Tasks <-> Notes <-> Settings)
            // =================================================================
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { pageIndex ->
                when (pageIndex) {
                    0 -> {
                        // TAB 0: TASKS (Todo Tab)
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                // 1. Single-Line Top Filter Bar: Open | All | Due | Done | [+@ Tags] | [/]
                                val isTagFiltered = activeFilter.startsWith("+") || activeFilter.startsWith("@")
                                val tagButtonLabel = if (isTagFiltered) "[*$activeFilter*]" else "[+@ Tags]"

                                AsciiHorizontalBorder()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "| ",
                                        color = palette.fgMuted,
                                        fontSize = 12.sp,
                                        fontFamily = monoFont
                                    )
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf(
                                            "open" to "Open",
                                            "all" to "All",
                                            "due" to "Due",
                                            "done" to "Done"
                                        ).forEach { (fKey, fLabel) ->
                                            val active = activeFilter == fKey && searchQuery.isBlank()
                                            Text(
                                                text = if (active) "[*$fLabel*]" else fLabel,
                                                color = if (active) palette.project else palette.fg,
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                                fontFamily = appFont,
                                                modifier = Modifier
                                                    .clickable {
                                                        searchQuery = ""
                                                        updateFilterWithHistory(fKey)
                                                    }
                                                    .padding(vertical = 2.dp, horizontal = 2.dp)
                                            )
                                        }

                                        Text(
                                            text = tagButtonLabel,
                                            color = if (isTagFiltered) palette.contextTag else palette.fgMuted,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = appFont,
                                            modifier = Modifier
                                                .clickable { showTagsPopup = true }
                                                .padding(vertical = 2.dp, horizontal = 2.dp)
                                        )

                                        Text(
                                            text = if (showSearchBar || searchQuery.isNotBlank()) "[*/Search*]" else "[/]",
                                            color = if (showSearchBar || searchQuery.isNotBlank()) palette.reminder else palette.fgMuted,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = monoFont,
                                            modifier = Modifier
                                                .clickable {
                                                    showSearchBar = !showSearchBar
                                                    if (!showSearchBar) searchQuery = ""
                                                }
                                                .padding(vertical = 2.dp, horizontal = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = " |",
                                        color = palette.fgMuted,
                                        fontSize = 12.sp,
                                        fontFamily = monoFont
                                    )
                                }

                                // Optimization #8: Expandable Inline ASCII Search Row across Tasks & Notes
                                if (showSearchBar) {
                                    AsciiHorizontalBorder(corner = "|")
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "| / ",
                                            color = palette.reminder,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            fontFamily = monoFont
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(vertical = 2.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    text = "Search tasks & notes...",
                                                    color = palette.fgMuted,
                                                    fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                                    fontFamily = monoFont,
                                                    maxLines = 1
                                                )
                                            }
                                            BasicTextField(
                                                value = searchQuery,
                                                onValueChange = { searchQuery = it },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                                textStyle = TextStyle(
                                                    fontFamily = monoFont,
                                                    color = palette.fg,
                                                    fontSize = (fontScaleSp - 1).coerceAtLeast(13).sp
                                                ),
                                                cursorBrush = SolidColor(palette.reminder),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .focusRequester(searchFocusRequester)
                                            )
                                        }
                                        Text(
                                            text = "[X Close]",
                                            color = palette.fgMuted,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            fontFamily = monoFont,
                                            modifier = Modifier
                                                .clickable {
                                                    searchQuery = ""
                                                    showSearchBar = false
                                                    focusManager.clearFocus()
                                                }
                                                .padding(horizontal = 4.dp)
                                        )
                                        Text(
                                            text = " |",
                                            color = palette.fgMuted,
                                            fontSize = 12.sp,
                                            fontFamily = monoFont
                                        )
                                    }
                                    if (matchedNoteHits.isNotEmpty()) {
                                        AsciiBoxOptionRow(
                                            label = "[/ Notes]",
                                            options = matchedNoteHits.map { it.id to it.title },
                                            selectedValue = "",
                                            onSelect = { noteFile ->
                                                val targetNote = notes.firstOrNull { it.filename == noteFile }
                                                showSearchBar = false
                                                searchQuery = ""
                                                coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                                if (targetNote != null) {
                                                    openNoteInPopup(targetNote, startInEditMode = false)
                                                }
                                            },
                                            activeColor = palette.project,
                                            fontSizeSp = (fontScaleSp - 2).coerceAtLeast(12)
                                        )
                                    }
                                }

                                // Status / Undo Row
                                if (statusBanner != null) {
                                    AsciiHorizontalBorder(corner = "|")
                                    AsciiBoxLeftRightRow(
                                        left = statusBanner ?: "",
                                        right = if (undoSnapshot != null) "[↺ Undo]" else "[X Close]",
                                        leftColor = palette.reminder,
                                        rightColor = palette.project,
                                        fontSizeSp = (fontScaleSp - 2).coerceAtLeast(12),
                                        boldRight = true,
                                        onClick = {
                                            if (undoSnapshot != null) {
                                                persistAndSchedule(undoSnapshot!!)
                                                undoSnapshot = null
                                            }
                                            statusBanner = null
                                        }
                                    )
                                }
                                AsciiHorizontalBorder()

                                Spacer(modifier = Modifier.height(4.dp))

                                // 2. Dense 3-Line Task Cards List with Swipe Right = Done, Swipe Left = Delete
                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 58.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (filteredTasks.isEmpty()) {
                                        item {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 12.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AsciiTitledTopBorder(badgeText = "EMPTY", badgeColor = palette.fgMuted)
                                                AsciiBoxLeftRightRow(
                                                    left = if (searchQuery.isNotBlank()) "No matches for \"$searchQuery\"" else "No tasks in [$activeFilter]",
                                                    right = "[+ Add]",
                                                    leftColor = palette.fgMuted,
                                                    rightColor = palette.reminder,
                                                    boldRight = true,
                                                    onClick = { showAddTaskPopup = true }
                                                )
                                                AsciiHorizontalBorder()
                                            }
                                        }
                                    } else {
                                        items(filteredTasks, key = { "${it.lineNumber}:${it.rawLine}" }) { task ->
                                            val dismissState = rememberSwipeToDismissBoxState(
                                                confirmValueChange = { targetValue ->
                                                    when (targetValue) {
                                                        SwipeToDismissBoxValue.StartToEnd -> {
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
                                                            statusBanner = if (task.isCompleted) "[↺ Reopen] ${task.description}" else "[✓ Done] ${task.description}"
                                                            false
                                                        }
                                                        SwipeToDismissBoxValue.EndToStart -> {
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            undoSnapshot = tasks
                                                            persistAndSchedule(tasks.filterNot { it.lineNumber == task.lineNumber })
                                                            statusBanner = "[X Delete] ${task.description}"
                                                            true
                                                        }
                                                        SwipeToDismissBoxValue.Settled -> false
                                                    }
                                                }
                                            )

                                            SwipeToDismissBox(
                                                state = dismissState,
                                                backgroundContent = {
                                                    val dir = dismissState.dismissDirection
                                                    val bgText = when (dir) {
                                                        SwipeToDismissBoxValue.StartToEnd -> "| [✓ Done] >>>"
                                                        SwipeToDismissBoxValue.EndToStart -> "<<< [X Delete] |"
                                                        else -> ""
                                                    }
                                                    val bgColor = when (dir) {
                                                        SwipeToDismissBoxValue.StartToEnd -> palette.reminder
                                                        SwipeToDismissBoxValue.EndToStart -> palette.priorityA
                                                        else -> palette.fgMuted
                                                    }
                                                    val align = when (dir) {
                                                        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                                        else -> Alignment.CenterEnd
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(palette.bgElevated)
                                                            .padding(horizontal = 12.dp),
                                                        contentAlignment = align
                                                    ) {
                                                        Text(
                                                            text = bgText,
                                                            color = bgColor,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = fontScaleSp.sp,
                                                            fontFamily = monoFont
                                                        )
                                                    }
                                                },
                                                content = {
                                                    UsableTaskCard(
                                                        task = task,
                                                        currentNow = currentNow,
                                                        fontSizeSp = fontScaleSp,
                                                        fontScaleSp = fontScaleSp,
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
                                                        },
                                                        onTapTask = {
                                                            editingTask = task
                                                            editingTaskDraft = TodoParser.serializeLine(task)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 3. Uniform Pure-ASCII `[+ Add]` Box at Bottom-Right
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 6.dp)
                                    .background(palette.bg)
                                    .clickable { showAddTaskPopup = true },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "+---------+",
                                    color = palette.fgMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 13.sp,
                                    fontFamily = monoFont
                                )
                                Text(
                                    text = "| [+ Add] |",
                                    color = palette.reminder,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp,
                                    fontFamily = monoFont
                                )
                                Text(
                                    text = "+---------+",
                                    color = palette.fgMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 13.sp,
                                    fontFamily = monoFont
                                )
                            }
                        }
                    }

                    1 -> {
                        // TAB 1: GOOGLE KEEP-STYLE 2-COLUMN STAGGERED MARKDOWN NOTES BOARD
                        val leftColumnNotes = remember(notes) { notes.filterIndexed { index, _ -> index % 2 == 0 } }
                        val rightColumnNotes = remember(notes) { notes.filterIndexed { index, _ -> index % 2 == 1 } }

                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                // Top Bar matching Tasks tab style
                                AsciiTitledTopBorder(
                                    badgeText = "/ MARKDOWN NOTES (${notes.size})",
                                    badgeColor = palette.project,
                                    rightBadgeText = "+ Add",
                                    rightBadgeColor = palette.reminder,
                                    onRightBadgeClick = { createAndOpenNewMarkdownNote() }
                                )
                                AsciiBoxLeftRightRow(
                                    left = "Tap any card to open • Tap [ ] to check items",
                                    right = ".md only",
                                    leftColor = palette.fgMuted,
                                    rightColor = palette.contextTag,
                                    fontSizeSp = (fontScaleSp - 3).coerceAtLeast(11)
                                )
                                AsciiHorizontalBorder()

                                Spacer(modifier = Modifier.height(6.dp))

                                // Google Keep-Style 2-Column Masonry Grid
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = 58.dp)
                                ) {
                                    if (notes.isEmpty()) {
                                        AsciiTitledTopBorder(badgeText = "EMPTY", badgeColor = palette.fgMuted)
                                        AsciiBoxLeftRightRow(
                                            left = "No Markdown (.md) notes yet",
                                            right = "[+ Add]",
                                            leftColor = palette.fgMuted,
                                            rightColor = palette.reminder,
                                            boldRight = true,
                                            onClick = { createAndOpenNewMarkdownNote() }
                                        )
                                        AsciiHorizontalBorder()
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            // Left Masonry Column
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                leftColumnNotes.forEach { note ->
                                                    KeepAsciiMarkdownCard(
                                                        note = note,
                                                        fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12),
                                                        onTapCard = { openNoteInPopup(note, startInEditMode = false) },
                                                        onToggleChecklistLine = { lineIdx ->
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            val toggledBody = toggleMarkdownCheckboxAtLine(note.bodyContent, lineIdx)
                                                            coroutineScope.launch(Dispatchers.IO) {
                                                                val saved = storageRepo.saveNote(note.filename, toggledBody)
                                                                withContext(Dispatchers.Main) { notes = saved }
                                                            }
                                                        }
                                                    )
                                                }
                                            }

                                            // Right Masonry Column
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                rightColumnNotes.forEach { note ->
                                                    KeepAsciiMarkdownCard(
                                                        note = note,
                                                        fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12),
                                                        onTapCard = { openNoteInPopup(note, startInEditMode = false) },
                                                        onToggleChecklistLine = { lineIdx ->
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            val toggledBody = toggleMarkdownCheckboxAtLine(note.bodyContent, lineIdx)
                                                            coroutineScope.launch(Dispatchers.IO) {
                                                                val saved = storageRepo.saveNote(note.filename, toggledBody)
                                                                withContext(Dispatchers.Main) { notes = saved }
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Uniform Pure-ASCII `[+ Add]` Box at Bottom-Right (Identical to Tasks tab!)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 6.dp)
                                    .background(palette.bg)
                                    .clickable { createAndOpenNewMarkdownNote() },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "+---------+",
                                    color = palette.fgMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 13.sp,
                                    fontFamily = monoFont
                                )
                                Text(
                                    text = "| [+ Add] |",
                                    color = palette.reminder,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp,
                                    fontFamily = monoFont
                                )
                                Text(
                                    text = "+---------+",
                                    color = palette.fgMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 13.sp,
                                    fontFamily = monoFont
                                )
                            }
                        }
                    }

                    else -> {
                        // TAB 2: SETTINGS
                        SettingsScreen(
                            baseDir = storageRepo.baseDir,
                            storageFolderPath = settings.customSafUri ?: storageRepo.baseDir.absolutePath,
                            settings = settings,
                            onUpdateSettings = onUpdateSettings,
                            onSyncAllToGoogleCalendar = {
                                if (gcalManager.hasCalendarPermissions()) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val (syncedList, count) = gcalManager.syncAllDueTasks(tasks)
                                        withContext(Dispatchers.Main) {
                                            persistAndSchedule(syncedList)
                                            statusBanner = "[# GCal] Synced $count task(s)"
                                        }
                                    }
                                } else {
                                    calendarPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.READ_CALENDAR,
                                            Manifest.permission.WRITE_CALENDAR
                                        )
                                    )
                                }
                                Unit
                            },
                            onPickFolderWithSaf = { folderPickerLauncher.launch(null) },
                            onReloadFromFolder = {
                                reloadAllFromSafOrDisk(switchToTasksTab = false)
                            },
                            onOpenSavedFileLocation = {
                                val todoFile = storageRepo.todoFile
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "todo.txt")
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        runCatching { todoFile.readText() }.getOrDefault("")
                                    )
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share or open todo.txt")
                                    )
                                }
                                Unit
                            },
                            onArchiveCompletedTasks = {
                                val completed = tasks.filter { it.isCompleted }
                                if (completed.isNotEmpty()) {
                                    undoSnapshot = tasks
                                    val newDone = doneLines + completed.map { TodoParser.serializeLine(it) }
                                    doneLines = newDone
                                    coroutineScope.launch(Dispatchers.IO) {
                                        storageRepo.saveDoneLines(newDone)
                                    }
                                    persistAndSchedule(tasks.filterNot { it.isCompleted })
                                }
                            },
                            onFireImmediateNotification = {
                                triggerFullNotification(
                                    taskTitle = "check chocotat +chocotat @factory",
                                    rawSpec = "0m/n",
                                    taskId = "test_notif"
                                )
                            }
                        )
                    }
                }
            }

            // =================================================================
            // BOTTOM NAVIGATION TABS BOX (`[*Tasks (1)*] | Notes (2) | Settings`)
            // =================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bg)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                val openCount = tasks.count { !it.isBlankOrComment && !it.isCompleted }
                AsciiHorizontalBorder()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "| ",
                        color = palette.fgMuted,
                        fontSize = 12.sp,
                        fontFamily = monoFont
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            0 to "Tasks ($openCount)",
                            1 to "Notes (${notes.size})",
                            2 to "Settings"
                        ).forEach { (tabIdx, tabLabel) ->
                            val selected = pagerState.currentPage == tabIdx
                            Text(
                                text = if (selected) "[*$tabLabel*]" else tabLabel,
                                color = if (selected) palette.project else palette.fg,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = (fontScaleSp - 1).coerceAtLeast(13).sp,
                                fontFamily = appFont,
                                textDecoration = if (selected) TextDecoration.Underline else TextDecoration.None,
                                modifier = Modifier
                                    .clickable {
                                        statusBanner = null
                                        editingTask = null
                                        showAddTaskPopup = false
                                        activeNotePopupFilename = null
                                        showTagsPopup = false
                                        coroutineScope.launch { pagerState.animateScrollToPage(tabIdx) }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp)
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
                AsciiHorizontalBorder()
            }
        }

        // =====================================================================
        // POPUP 0: SHADED GOOGLE KEEP-STYLE MARKDOWN NOTE VIEWER / EDITOR
        // =====================================================================
        if (activeNotePopupFilename != null) {
            val currentNote = notes.firstOrNull { it.filename == activeNotePopupFilename }
            val displayLines = remember(noteDraftText, currentNote?.bodyContent, noteEditMode) {
                if (noteEditMode) noteDraftText.lines()
                else (currentNote?.bodyContent ?: noteDraftText).lines()
            }

            fun saveAndCloseNotePopup() {
                if (noteDraftName.isNotBlank()) {
                    val oldName = activeNotePopupFilename
                    val safeNewName = NotesRepository.toHumanReadableFilename(noteDraftName, ".md")
                    val textToSave = noteDraftText
                    coroutineScope.launch(Dispatchers.IO) {
                        if (oldName != null && oldName != safeNewName) {
                            storageRepo.deleteNote(oldName)
                        }
                        val saved = storageRepo.saveNote(safeNewName, textToSave)
                        withContext(Dispatchers.Main) { notes = saved }
                    }
                }
                noteEditMode = false
                activeNotePopupFilename = null
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { saveAndCloseNotePopup() }
                    .padding(horizontal = 14.dp, vertical = 24.dp),
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
                    AsciiTitledTopBorder(
                        badgeText = "/ MARKDOWN NOTE (.md)",
                        badgeColor = palette.project,
                        rightBadgeText = "X Close",
                        rightBadgeColor = palette.fgMuted,
                        onRightBadgeClick = { saveAndCloseNotePopup() }
                    )

                    // Filename Row (`| # architecture.md |`)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "| # ",
                            color = palette.project,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = monoFont
                        )
                        BasicTextField(
                            value = noteDraftName,
                            onValueChange = {
                                noteDraftName = it
                                noteEditMode = true
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontFamily = monoFont,
                                color = palette.project,
                                fontWeight = FontWeight.Bold,
                                fontSize = fontScaleSp.sp
                            ),
                            cursorBrush = SolidColor(palette.project),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp)
                        )
                        Text(
                            text = " |",
                            color = palette.fgMuted,
                            fontSize = 12.sp,
                            fontFamily = monoFont
                        )
                    }
                    AsciiHorizontalBorder(corner = "|")

                    // 1-Tap Markdown Formatting Toolbar inside the ASCII box
                    AsciiBoxActionRow(
                        actions = listOf(
                            Triple<String, Color, () -> Unit>("[+ # H1]", palette.project) {
                                noteEditMode = true
                                noteDraftText = if (noteDraftText.endsWith("\n") || noteDraftText.isEmpty()) {
                                    "${noteDraftText}# "
                                } else {
                                    "$noteDraftText\n# "
                                }
                            },
                            Triple<String, Color, () -> Unit>("[+ [ ]]", palette.reminder) {
                                noteEditMode = true
                                noteDraftText = if (noteDraftText.endsWith("\n") || noteDraftText.isEmpty()) {
                                    "${noteDraftText}- [ ] "
                                } else {
                                    "$noteDraftText\n- [ ] "
                                }
                            },
                            Triple<String, Color, () -> Unit>("[+ •]", palette.contextTag) {
                                noteEditMode = true
                                noteDraftText = if (noteDraftText.endsWith("\n") || noteDraftText.isEmpty()) {
                                    "${noteDraftText}- "
                                } else {
                                    "$noteDraftText\n- "
                                }
                            },
                            Triple<String, Color, () -> Unit>("[**B**]", palette.priorityB) {
                                noteEditMode = true
                                noteDraftText = "$noteDraftText **bold**"
                            },
                            Triple<String, Color, () -> Unit>("[`code`]", palette.fgMuted) {
                                noteEditMode = true
                                noteDraftText = "$noteDraftText `code`"
                            }
                        ),
                        fontSizeSp = (fontScaleSp - 2).coerceAtLeast(11)
                    )
                    AsciiHorizontalBorder(corner = "|")

                    // Note Body: Edit Mode vs Interactive Rendered Markdown Preview
                    if (noteEditMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 340.dp)
                        ) {
                            Text(
                                text = "| ",
                                color = palette.fgMuted,
                                fontSize = 12.sp,
                                fontFamily = monoFont
                            )
                            BasicTextField(
                                value = noteDraftText,
                                onValueChange = { noteDraftText = it },
                                textStyle = TextStyle(
                                    fontFamily = monoFont,
                                    color = palette.fg,
                                    fontSize = fontScaleSp.sp,
                                    lineHeight = (fontScaleSp + 4).sp
                                ),
                                cursorBrush = SolidColor(palette.project),
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 6.dp)
                            )
                            Text(
                                text = " |",
                                color = palette.fgMuted,
                                fontSize = 12.sp,
                                fontFamily = monoFont
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 340.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            displayLines.forEachIndexed { idx, line ->
                                MarkdownFormattedRow(
                                    rawLine = line,
                                    fontSizeSp = fontScaleSp,
                                    onTapCheckbox = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val toggled = toggleMarkdownCheckboxAtLine(noteDraftText, idx)
                                        noteDraftText = toggled
                                        val safeName = NotesRepository.toHumanReadableFilename(noteDraftName, ".md")
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val saved = storageRepo.saveNote(safeName, toggled)
                                            withContext(Dispatchers.Main) { notes = saved }
                                        }
                                    },
                                    onTapLineToEdit = {
                                        noteEditMode = true
                                    }
                                )
                            }
                        }
                    }

                    AsciiHorizontalBorder(corner = "|")

                    // Uniform Bottom Action Row
                    val popupActions: List<Triple<String, Color, () -> Unit>> = buildList {
                        add(
                            Triple<String, Color, () -> Unit>(
                                if (noteEditMode) "[✓ Preview]" else "[/ Edit]",
                                palette.project
                            ) {
                                if (noteEditMode) {
                                    val safeName = NotesRepository.toHumanReadableFilename(noteDraftName, ".md")
                                    val textToSave = noteDraftText
                                    noteEditMode = false
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val saved = storageRepo.saveNote(safeName, textToSave)
                                        withContext(Dispatchers.Main) { notes = saved }
                                    }
                                } else {
                                    noteEditMode = true
                                }
                                Unit
                            }
                        )
                        add(
                            Triple<String, Color, () -> Unit>("[✓ Save]", palette.reminder) {
                                saveAndCloseNotePopup()
                            }
                        )
                        if (notes.size > 1) {
                            add(
                                Triple<String, Color, () -> Unit>("[X Delete]", palette.priorityA) {
                                    val targetToDelete = activeNotePopupFilename ?: noteDraftName
                                    noteEditMode = false
                                    activeNotePopupFilename = null
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val updated = storageRepo.deleteNote(targetToDelete)
                                        withContext(Dispatchers.Main) { notes = updated }
                                    }
                                    Unit
                                }
                            )
                        }
                        add(
                            Triple<String, Color, () -> Unit>("[X Close]", palette.fgMuted) {
                                saveAndCloseNotePopup()
                            }
                        )
                    }
                    AsciiBoxActionRow(
                        actions = popupActions,
                        fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                    )
                    AsciiHorizontalBorder()
                }
            }
        }

        // =====================================================================
        // POPUP 1: PROJECTS (+) & CONTEXTS (@) FILTER MODAL
        // =====================================================================
        if (showTagsPopup) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showTagsPopup = false }
                    .padding(horizontal = 16.dp),
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
                        .padding(12.dp)
                ) {
                    AsciiTitledTopBorder(
                        badgeText = "+@ PROJECTS & CONTEXTS",
                        badgeColor = palette.project,
                        rightBadgeText = "X Close",
                        rightBadgeColor = palette.fgMuted,
                        onRightBadgeClick = { showTagsPopup = false }
                    )
                    if (dynamicProjects.isEmpty() && dynamicContexts.isEmpty()) {
                        AsciiBoxBodyRow(
                            text = "No +projects or @contexts found in tasks.",
                            textColor = palette.fgMuted,
                            fontSizeSp = fontScaleSp
                        )
                    } else {
                        if (dynamicProjects.isNotEmpty()) {
                            AsciiBoxOptionRow(
                                label = "[+] Proj",
                                options = dynamicProjects.map { it to it },
                                selectedValue = activeFilter,
                                onSelect = {
                                    updateFilterWithHistory(it)
                                    showTagsPopup = false
                                },
                                activeColor = palette.project,
                                fontSizeSp = fontScaleSp
                            )
                        }
                        if (dynamicContexts.isNotEmpty()) {
                            AsciiBoxOptionRow(
                                label = "[@] Ctx",
                                options = dynamicContexts.map { it to it },
                                selectedValue = activeFilter,
                                onSelect = {
                                    updateFilterWithHistory(it)
                                    showTagsPopup = false
                                },
                                activeColor = palette.contextTag,
                                fontSizeSp = fontScaleSp
                            )
                        }
                    }
                    AsciiHorizontalBorder(corner = "|")
                    AsciiBoxActionRow(
                        actions = listOf(
                            Triple("[*] Show All Open", palette.reminder) {
                                updateFilterWithHistory("open")
                                showTagsPopup = false
                            },
                            Triple("[X Close]", palette.fgMuted) {
                                showTagsPopup = false
                            }
                        ),
                        fontSizeSp = fontScaleSp
                    )
                    AsciiHorizontalBorder()
                }
            }
        }

        // =====================================================================
        // POPUP 2: SCREENSHOT-1 STYLE "EDIT REMINDER" POPUP + WHEEL SNOOZE
        // =====================================================================
        if (editingTask != null) {
            val target = editingTask!!
            val parsedDraft = remember(editingTaskDraft, target.lineNumber) {
                TodoParser.parseLine(editingTaskDraft, target.lineNumber)
            }
            val isTargetGcalSynced = parsedDraft.getTagValue("gcal") != null
            val targetDt = remember(parsedDraft.dueDate, parsedDraft.dueTime, currentNow) {
                val d = parsedDraft.dueDate ?: currentNow.toLocalDate()
                val t = parsedDraft.dueTime ?: LocalTime.of(9, 0)
                LocalDateTime.of(d, t)
            }
            val deltaStr = remember(targetDt, currentNow) {
                PlainTextSportsAscii.formatRelativeDelta(currentNow, targetDt)
            }
            val formattedDateLine = remember(targetDt) {
                targetDt.format(DateTimeFormatter.ofPattern("EEE, d MMM • HH:mm", Locale.ENGLISH))
            }

            if (showEditSnoozeWheel) {
                AsciiSnoozeWheelPopup(
                    taskTitle = parsedDraft.description.ifEmpty { target.description },
                    initialDate = parsedDraft.dueDate,
                    initialTime = parsedDraft.dueTime,
                    fontSizeSp = fontScaleSp,
                    confirmLabel = "[zZ Snooze]",
                    onDismiss = { showEditSnoozeWheel = false },
                    onConfirmDateTime = { chosenDate, chosenTime, delayMs ->
                        val dStr = chosenDate.toString()
                        val tStr = "%02d:%02d".format(chosenTime.hour, chosenTime.minute)
                        val specStr = "${settings.defaultTimedReminder.substringBefore("/")}/n"
                        var updated = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                        updated = TodoParser.withUpdatedTag(updated, "due", dStr)
                        updated = TodoParser.withUpdatedTag(updated, "duetime", tStr)
                        updated = TodoParser.withReplacedMultiTag(updated, "rem", listOf(specStr))
                        editingTaskDraft = TodoParser.serializeLine(updated)

                        undoSnapshot = tasks
                        val updatedTasks = tasks.map { if (it.lineNumber == target.lineNumber) updated else it }
                        persistAndSchedule(updatedTasks)
                        scheduleExactDelayNotification(updated, delayMs, specStr)
                        showEditSnoozeWheel = false
                        editingTask = null
                        statusBanner = "[zZ Snooze] $dStr $tStr"
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.78f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { editingTask = null }
                        .padding(horizontal = 14.dp),
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
                        AsciiTitledTopBorder(
                            badgeText = "/ EDIT REMINDER",
                            badgeColor = palette.reminder,
                            rightBadgeText = "X Close",
                            rightBadgeColor = palette.fgMuted,
                            onRightBadgeClick = { editingTask = null }
                        )

                        // Row 1: Editable Task Title Box
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "| > ",
                                color = palette.project,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = monoFont
                            )
                            BasicTextField(
                                value = editingTaskDraft,
                                onValueChange = { editingTaskDraft = it },
                                textStyle = TextStyle(fontFamily = monoFont, color = palette.fg, fontSize = fontScaleSp.sp),
                                cursorBrush = SolidColor(palette.project),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp)
                            )
                            Text(
                                text = " |",
                                color = palette.fgMuted,
                                fontSize = 12.sp,
                                fontFamily = monoFont
                            )
                        }
                        AsciiHorizontalBorder(corner = "|")

                        // Optimization #4: 1-Tap `+Project` & `@Context` Quick-Toggle Chips
                        if (quickTagChips.isNotEmpty()) {
                            AsciiBoxOptionRow(
                                label = "[+@ Tag]",
                                options = quickTagChips.map { it to it },
                                selectedValue = "",
                                onSelect = { tag ->
                                    editingTaskDraft = toggleTagInText(editingTaskDraft, tag)
                                },
                                activeColor = palette.project,
                                fontSizeSp = (fontScaleSp - 2).coerceAtLeast(11)
                            )
                            AsciiHorizontalBorder(corner = "|")
                        }

                        // Row 2: 1-Tap Priority Selector (`None | (A) | (B) | (C)`)
                        val currentPriKey = parsedDraft.priority?.toString() ?: ""
                        AsciiBoxOptionRow(
                            label = "[!] Priority",
                            options = listOf(
                                "" to "None",
                                "A" to "(A)",
                                "B" to "(B)",
                                "C" to "(C)"
                            ),
                            selectedValue = currentPriKey,
                            onSelect = { priCode ->
                                val newPri = priCode.firstOrNull()
                                val updated = parsedDraft.copy(priority = newPri, isDirty = true)
                                editingTaskDraft = TodoParser.serializeLine(updated)
                            },
                            activeColor = when (currentPriKey) {
                                "A" -> palette.priorityA
                                "B" -> palette.priorityB
                                "C" -> palette.priorityC
                                else -> palette.fgMuted
                            },
                            fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                        )
                        AsciiHorizontalBorder(corner = "|")

                        // Row 3: Date & Time + Relative Delta (Tapping opens the 3-Column Wheel Picker!)
                        AsciiBoxLeftRightRow(
                            left = "(L) $formattedDateLine",
                            right = "$deltaStr [zZ Snooze]",
                            leftColor = palette.fg,
                            rightColor = palette.reminder,
                            fontSizeSp = fontScaleSp,
                            boldLeft = true,
                            boldRight = true,
                            underlineRight = true,
                            onClick = { showEditSnoozeWheel = true }
                        )
                        AsciiHorizontalBorder(corner = "|")

                        // Row 4: Uniform Actions Bar: [zZ Snooze]  [# GCal]  [✓ Save]  [X Delete]
                        AsciiBoxActionRow(
                            actions = listOf(
                                Triple("[zZ Snooze]", palette.project) {
                                    showEditSnoozeWheel = true
                                },
                                Triple(if (isTargetGcalSynced) "[# Synced]" else "[# GCal]", palette.contextTag) {
                                    val parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                                    if (gcalManager.hasCalendarPermissions()) {
                                        val (synced, ok) = gcalManager.syncTaskToGoogleCalendar(parsed)
                                        if (ok) {
                                            persistAndSchedule(
                                                tasks.map { if (it.lineNumber == target.lineNumber) synced else it }
                                            )
                                            editingTask = null
                                            statusBanner = "[# GCal] Synced"
                                        } else {
                                            gcalManager.openInGoogleCalendarApp(parsed)
                                        }
                                    } else {
                                        gcalManager.openInGoogleCalendarApp(parsed)
                                    }
                                },
                                Triple("[✓ Save]", palette.reminder) {
                                    undoSnapshot = tasks
                                    val reParsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber).copy(isDirty = true)
                                    learnRuleFromSavedTask(reParsed)
                                    persistAndSchedule(
                                        tasks.map { if (it.lineNumber == target.lineNumber) reParsed else it }
                                    )
                                    editingTask = null
                                    statusBanner = "[✓ Save] Updated task"
                                },
                                Triple("[X Delete]", palette.priorityA) {
                                    undoSnapshot = tasks
                                    persistAndSchedule(tasks.filterNot { it.lineNumber == target.lineNumber })
                                    editingTask = null
                                    statusBanner = "[X Delete] Removed task"
                                }
                            ),
                            fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                        )
                        AsciiHorizontalBorder()
                    }
                }
            }
        }

        // =====================================================================
        // POPUP 3: ADD TASK POPUP + WHEEL SNOOZE PICKER
        // =====================================================================
        if (showAddTaskPopup) {
            if (showAddSnoozeWheel) {
                AsciiSnoozeWheelPopup(
                    taskTitle = smartInput.ifBlank { "New Task" },
                    initialDate = parsePreview?.dueDate ?: currentNow.toLocalDate(),
                    initialTime = parsePreview?.dueTime ?: LocalTime.now().plusMinutes(10).withSecond(0).withNano(0),
                    fontSizeSp = fontScaleSp,
                    confirmLabel = "[✓ Save]",
                    onDismiss = { showAddSnoozeWheel = false },
                    onConfirmDateTime = { chosenDate, chosenTime, _ ->
                        val dStr = chosenDate.toString()
                        val tStr = "%02d:%02d".format(chosenTime.hour, chosenTime.minute)
                        val cleaned = smartInput
                            .replace(DUE_TAG_REGEX, "")
                            .replace(DUETIME_TAG_REGEX, "")
                            .trim()
                        smartInput = "$cleaned due:$dStr duetime:$tStr".trim()
                        showAddSnoozeWheel = false
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.78f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showAddTaskPopup = false }
                        .padding(horizontal = 14.dp),
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
                        AsciiTitledTopBorder(
                            badgeText = "+ ADD REMINDER / TASK",
                            badgeColor = palette.reminder,
                            rightBadgeText = "X Close",
                            rightBadgeColor = palette.fgMuted,
                            onRightBadgeClick = { showAddTaskPopup = false }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "| > ",
                                color = palette.reminder,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = monoFont
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (smartInput.isEmpty()) {
                                    Text(
                                        text = "check chocotat at factory in 1m...",
                                        color = palette.fgMuted,
                                        fontSize = 13.sp,
                                        fontFamily = monoFont,
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
                                        fontFamily = monoFont,
                                        color = palette.fg,
                                        fontSize = fontScaleSp.sp
                                    ),
                                    cursorBrush = SolidColor(palette.reminder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(addInputFocusRequester)
                                )
                            }
                            Text(
                                text = " |",
                                color = palette.fgMuted,
                                fontSize = 12.sp,
                                fontFamily = monoFont
                            )
                        }

                        if (parsePreview != null && parsePreview.recognizedSpans.isNotEmpty()) {
                            AsciiHorizontalBorder(corner = "|")
                            AsciiBoxOptionRow(
                                label = "[* Inferred]",
                                options = parsePreview.recognizedSpans.map { it.id to "${it.resolvedToken}[x]" },
                                selectedValue = "",
                                onSelect = { spanId -> disabledSpans = disabledSpans + spanId },
                                activeColor = palette.reminder,
                                fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                            )
                        }

                        // Optimization #4: 1-Tap `+Project` & `@Context` Chips in Add Popup
                        if (quickTagChips.isNotEmpty()) {
                            AsciiHorizontalBorder(corner = "|")
                            AsciiBoxOptionRow(
                                label = "[+@ Tag]",
                                options = quickTagChips.map { it to it },
                                selectedValue = "",
                                onSelect = { tag ->
                                    smartInput = toggleTagInText(smartInput, tag)
                                },
                                activeColor = palette.project,
                                fontSizeSp = (fontScaleSp - 2).coerceAtLeast(11)
                            )
                        }

                        AsciiHorizontalBorder(corner = "|")
                        // 1-Tap Priority Selector (`None | (A) | (B) | (C)`)
                        val addPriKey = addTaskPriority?.toString() ?: ""
                        AsciiBoxOptionRow(
                            label = "[!] Priority",
                            options = listOf(
                                "" to "None",
                                "A" to "(A)",
                                "B" to "(B)",
                                "C" to "(C)"
                            ),
                            selectedValue = addPriKey,
                            onSelect = { addTaskPriority = it.firstOrNull() },
                            activeColor = when (addPriKey) {
                                "A" -> palette.priorityA
                                "B" -> palette.priorityB
                                "C" -> palette.priorityC
                                else -> palette.fgMuted
                            },
                            fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                        )

                        AsciiHorizontalBorder(corner = "|")
                        // Date & Time Wheel Trigger Row
                        val previewDateText = if (parsePreview?.dueDate != null) {
                            "${parsePreview.dueDate} ${parsePreview.dueTime ?: ""}".trim()
                        } else {
                            "Tap to pick Date & Time"
                        }
                        AsciiBoxLeftRightRow(
                            left = "(L) $previewDateText",
                            right = "[zZ Snooze]",
                            leftColor = palette.fg,
                            rightColor = palette.project,
                            fontSizeSp = fontScaleSp,
                            boldRight = true,
                            underlineRight = true,
                            onClick = { showAddSnoozeWheel = true }
                        )
                        AsciiHorizontalBorder(corner = "|")

                        // Uniform Bottom Action Bar: [X Close]   &   [+ Add]
                        AsciiBoxActionRow(
                            actions = listOf(
                                Triple("[X Close]", palette.fgMuted) { showAddTaskPopup = false },
                                Triple("[+ Add]", palette.reminder) { submitSmartAdd() }
                            ),
                            fontSizeSp = fontScaleSp
                        )
                        AsciiHorizontalBorder()
                    }
                }
            }
        }
    }
}

/**
 * Google Keep-Style Compact ASCII Markdown Note Card for the 2-Column Staggered Board!
 * Supports:
 * - Markdown headings (`# H1`, `## H2`, `### H3`)
 * - Interactive Markdown checklists (`- [ ]` / `- [x]`) right on the card
 * - Bullet lists (`• item`), blockquotes (`> quote`), `**bold**`, `*italic*`, and `` `code` ``.
 */
@Composable
private fun KeepAsciiMarkdownCard(
    note: NoteFile,
    fontSizeSp: Int,
    onTapCard: () -> Unit,
    onToggleChecklistLine: (Int) -> Unit
) {
    val palette = LocalTickPalette.current
    val lines = remember(note.bodyContent) { note.bodyContent.lines() }
    val nonEmptyIndexed = remember(lines) {
        lines.mapIndexedNotNull { idx, raw ->
            if (raw.isBlank()) null else idx to raw
        }
    }
    val previewIndexed = remember(nonEmptyIndexed) { nonEmptyIndexed.take(6) }
    val remainingCount = (nonEmptyIndexed.size - previewIndexed.size).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bg)
            .clickable { onTapCard() }
    ) {
        AsciiTitledTopBorder(
            badgeText = "# ${note.filename}",
            badgeColor = palette.project
        )

        if (previewIndexed.isEmpty()) {
            AsciiBoxBodyRow(
                text = "(Empty Markdown note)",
                textColor = palette.fgMuted,
                fontSizeSp = (fontSizeSp - 1).coerceAtLeast(11),
                singleLine = true,
                onClick = { onTapCard() }
            )
        } else {
            previewIndexed.forEach { (originalIdx, rawLine) ->
                MarkdownFormattedRow(
                    rawLine = rawLine,
                    fontSizeSp = fontSizeSp,
                    singleLine = true,
                    onTapCheckbox = { onToggleChecklistLine(originalIdx) },
                    onTapLineToEdit = { onTapCard() }
                )
            }
            if (remainingCount > 0) {
                AsciiBoxBodyRow(
                    text = "... (+$remainingCount more)",
                    textColor = palette.fgMuted,
                    fontSizeSp = (fontSizeSp - 2).coerceAtLeast(10),
                    singleLine = true,
                    onClick = { onTapCard() }
                )
            }
        }

        AsciiHorizontalBorder()
    }
}

/**
 * Renders a single Markdown line inside an ASCII box (`| ... |`) with support for:
 * - `# `, `## `, `### ` headings
 * - `- [ ]` and `- [x]` interactive checkboxes
 * - `- ` and `* ` bullets
 * - `> ` blockquotes
 * - Inline `**bold**`, `*italic*`, and `` `inline code` ``.
 */
@Composable
private fun MarkdownFormattedRow(
    rawLine: String,
    fontSizeSp: Int,
    singleLine: Boolean = false,
    onTapCheckbox: () -> Unit,
    onTapLineToEdit: () -> Unit
) {
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current
    val monoFont = FontFamily.Monospace
    val trimmed = rawLine.trim()

    val checkboxMatch = CHECKBOX_LINE_REGEX.matchEntire(rawLine)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = palette.fgMuted,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = monoFont,
            softWrap = false,
            maxLines = 1
        )

        when {
            checkboxMatch != null -> {
                val isChecked = checkboxMatch.groupValues[2].equals("x", ignoreCase = true)
                val itemText = checkboxMatch.groupValues[3].removePrefix("]").trim()
                Text(
                    text = if (isChecked) "[x] " else "[ ] ",
                    color = if (isChecked) palette.reminder else palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = monoFont,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable { onTapCheckbox() }
                        .padding(vertical = 1.dp)
                )
                Text(
                    text = renderInlineMarkdown(
                        itemText,
                        palette,
                        if (isChecked) palette.fgMuted else palette.fg
                    ),
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = appFont,
                    textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }

            trimmed.startsWith("#") -> {
                val headingText = trimmed.trimStart('#').trim()
                Text(
                    text = "# $headingText",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = appFont,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }

            trimmed.startsWith(">") -> {
                val quoteText = trimmed.removePrefix(">").trim()
                Text(
                    text = renderInlineMarkdown("| $quoteText", palette, palette.contextTag),
                    fontStyle = FontStyle.Italic,
                    fontSize = (fontSizeSp - 1).coerceAtLeast(11).sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = appFont,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val bulletBody = trimmed.substring(2).trim()
                Text(
                    text = "• ",
                    color = palette.priorityB,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = monoFont,
                    softWrap = false,
                    maxLines = 1
                )
                Text(
                    text = renderInlineMarkdown(bulletBody, palette, palette.fg),
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = appFont,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }

            else -> {
                Text(
                    text = renderInlineMarkdown(rawLine.ifEmpty { " " }, palette, palette.fg),
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = appFont,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }
        }

        Text(
            text = " |",
            color = palette.fgMuted,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = monoFont,
            softWrap = false,
            maxLines = 1
        )
    }
}

/**
 * 3-Line Task Card with Live Relative Countdown Badge:
 * Line 1: `+--[2026-11-04 09:30]-----------------[+trial • 18 h. 4 m.]--+`
 * Line 2: `| [ ] (A) chocotat with color                                |`
 * Line 3: `+------------------------------------------------------------+`
 */
@Composable
private fun UsableTaskCard(
    task: TodoTask,
    currentNow: LocalDateTime = LocalDateTime.now(),
    fontSizeSp: Int = 15,
    fontScaleSp: Int = fontSizeSp,
    onToggleDone: () -> Unit,
    onTapTask: () -> Unit
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

    val rightTagsSummary = buildString {
        task.projects.firstOrNull()?.let { append("+$it") }
        task.contexts.firstOrNull()?.let {
            if (isNotEmpty()) append(" ")
            append("@$it")
        }
        if (relativeBadge != null) {
            if (isNotEmpty()) append(" • ")
            append(relativeBadge)
        }
    }.ifEmpty { null }

    val rightBadgeColor = if (relativeBadge?.startsWith("-") == true) palette.priorityA else palette.project

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bg)
    ) {
        AsciiTitledTopBorder(
            badgeText = topDateAndTime,
            badgeColor = if (task.isCompleted) palette.reminder else palette.priorityB,
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
                color = palette.fgMuted,
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
                fontSize = fontScaleSp.sp,
                lineHeight = (fontScaleSp + 2).sp,
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
                    fontSize = fontScaleSp.sp,
                    lineHeight = (fontScaleSp + 2).sp,
                    fontFamily = appFont,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.clickable { onTapTask() }
                )
            }
            Text(
                text = task.description.ifEmpty { task.rawLine },
                color = if (task.isCompleted) palette.fgMuted else palette.fg,
                fontSize = fontScaleSp.sp,
                lineHeight = (fontScaleSp + 2).sp,
                fontWeight = FontWeight.Bold,
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
                color = palette.fgMuted,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontFamily = monoFont,
                softWrap = false,
                maxLines = 1
            )
        }

        AsciiHorizontalBorder()
    }
}
