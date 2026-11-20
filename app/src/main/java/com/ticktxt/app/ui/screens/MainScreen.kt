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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ticktxt.app.core.calendar.GoogleCalendarSyncManager
import com.ticktxt.app.core.nlp.RulesEngine
import com.ticktxt.app.core.nlp.SmartAddParser
import com.ticktxt.app.core.nlp.TickRulesConfig
import com.ticktxt.app.core.notes.NoteFile
import com.ticktxt.app.core.notes.NotesRepository
import com.ticktxt.app.core.notes.PlainTextSearchIndex
import com.ticktxt.app.core.reminders.AlarmBroadcastReceiver
import com.ticktxt.app.core.reminders.ReminderScheduleCalculator
import com.ticktxt.app.core.reminders.ReminderScheduler
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.core.todotxt.RawToken
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.ascii.AsciiBoxBodyRow
import com.ticktxt.app.ui.ascii.AsciiBoxLeftRightRow
import com.ticktxt.app.ui.ascii.AsciiBoxOptionRow
import com.ticktxt.app.ui.ascii.AsciiFloatingAddButton
import com.ticktxt.app.ui.ascii.AsciiHorizontalBorder
import com.ticktxt.app.ui.ascii.AsciiTitledTopBorder
import com.ticktxt.app.ui.ascii.PlainTextSportsAscii
import com.ticktxt.app.ui.ascii.toggleMarkdownCheckboxAtLine
import com.ticktxt.app.ui.components.AddTaskDialog
import com.ticktxt.app.ui.components.EditTaskDialog
import com.ticktxt.app.ui.components.KeepAsciiMarkdownCard
import com.ticktxt.app.ui.components.NoteEditorDialog
import com.ticktxt.app.ui.components.TagsFilterDialog
import com.ticktxt.app.ui.components.UsableTaskCard
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val NEW_NOTE_SENTINEL = "__NEW_NOTE__"

data class ActiveAlarmPayload(
    val reminderId: String,
    val taskId: String,
    val taskTitle: String,
    val rawSpec: String,
    val notifId: Int = 100
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    storageRepo: TickStorageRepository,
    settings: PersistedSettings,
    initialSharedText: String? = null,
    onConsumeSharedText: () -> Unit = {},
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

    // Loaded asynchronously off the main thread in LaunchedEffect(Unit)
    var tasks by remember { mutableStateOf<List<TodoTask>>(emptyList()) }
    var doneLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var rulesText by remember { mutableStateOf(TickRulesConfig.DEFAULT_RULES_FILE_CONTENT) }
    var notes by remember { mutableStateOf<List<NoteFile>>(emptyList()) }
    var backupCount by remember { mutableIntStateOf(0) }
    var isInitialLoadDone by remember { mutableStateOf(false) }

    val activeRules = remember(rulesText, settings.defaultTimedReminder, settings.defaultDateOnlyReminder, tasks, doneLines) {
        val base = RulesEngine.parse(rulesText).copy(
            timedReminderDefault = settings.defaultTimedReminder,
            dateOnlyReminderDefault = settings.defaultDateOnlyReminder
        )
        RulesEngine.withHistoricalLearning(base, tasks, doneLines)
    }

    var notesTwoColumnMode by remember { mutableStateOf(true) }
    var activeNotePopupFilename by remember { mutableStateOf<String?>(null) }
    var noteEditMode by remember { mutableStateOf(false) }
    var noteDraftName by remember { mutableStateOf("") }
    var noteDraftText by remember { mutableStateOf("") }
    var undoDeletedNote by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Pager for swiping between tabs (0 = Tasks, 1 = Notes, 2 = Settings)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var activeFilter by remember { mutableStateOf("open") }

    // Inline Search Bar (`[/]`) powered by PlainTextSearchIndex
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

    // Navigation history for Back Gesture
    var lastCommittedPage by remember { mutableIntStateOf(0) }
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

    // Modal state
    var editingTask by remember { mutableStateOf<TodoTask?>(null) }
    var editingTaskDraft by remember { mutableStateOf("") }
    var showEditDueDateWheel by remember { mutableStateOf(false) }

    var showAddTaskPopup by remember { mutableStateOf(false) }
    var smartInput by remember { mutableStateOf("") }
    var addTaskPriority by remember { mutableStateOf<Char?>(null) }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var dismissedInferredTags by remember { mutableStateOf(setOf<String>()) }
    var showAddDueDateWheel by remember { mutableStateOf(false) }

    // Handle incoming Android Share / CREATE_NOTE intents
    LaunchedEffect(initialSharedText) {
        if (!initialSharedText.isNullOrBlank()) {
            smartInput = initialSharedText
            showAddTaskPopup = true
            onConsumeSharedText()
        }
    }

    // Auto-Focus Keyboard when `[+ Add]` Popup opens
    val addInputFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showAddTaskPopup, showAddDueDateWheel) {
        if (showAddTaskPopup && !showAddDueDateWheel) {
            delay(80L)
            runCatching { addInputFocusRequester.requestFocus() }
        }
    }

    var showTagsPopup by remember { mutableStateOf(false) }
    var activeAlarmOverlay by remember { mutableStateOf<ActiveAlarmPayload?>(null) }
    var undoSnapshot by remember { mutableStateOf<List<TodoTask>?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    // Lifecycle-Aware Minute-Boundary Clock Tick
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

    // Off-main-thread initial hydration
    LaunchedEffect(Unit) {
        val (res, latestBaks) = withContext(Dispatchers.IO) {
            val loaded = storageRepo.loadAllFromSafFolder()
            val baks = storageRepo.getTodoBackupCount()
            scheduler.syncTaskSchedules(loaded.tasks, ZonedDateTime.now())
            loaded to baks
        }
        tasks = res.tasks
        doneLines = res.doneLines
        rulesText = res.rulesText
        notes = res.notes
        backupCount = latestBaks
        isInitialLoadDone = true
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            coroutineScope.launch(Dispatchers.IO) {
                val (syncedList, count) = gcalManager.syncAllDueTasks(tasks)
                val merged = storageRepo.saveTasks(syncedList)
                withContext(Dispatchers.Main) {
                    tasks = merged
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
        cancelShadeNotification(notifId)
        AlarmAudioController.playSystemNotificationBeepOnce(context)
        activeAlarmOverlay = ActiveAlarmPayload(
            reminderId = remId,
            taskId = taskId,
            taskTitle = taskTitle,
            rawSpec = rawSpec,
            notifId = notifId
        )
    }

    // Single source of trigger truth: AlarmManager -> AlarmBroadcastReceiver -> ACTION_ALARM_FIRED_UI
    fun scheduleExactDelayNotification(task: TodoTask, delayMs: Long, rawSpec: String = "0m/n") {
        val safeDelay = delayMs.coerceAtLeast(1_000L)
        val targetEpochMs = System.currentTimeMillis() + safeDelay
        val typeCode = rawSpec.substringAfter("/", "n").take(1).ifEmpty { "n" }
        scheduler.scheduleRaw(
            reminderId = "${task.stableId}|$rawSpec",
            taskStableId = task.stableId,
            rawSpec = rawSpec,
            taskDescription = task.description,
            typeCode = typeCode,
            triggerEpochMs = targetEpochMs
        )
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

    // Asynchronous Disk/SAF I/O (with 3-way ConflictMerger) + Incremental Diff-Based Reminder Scheduling
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
                }
            }
            val mergedTasks = storageRepo.saveTasks(finalTasks)
            val latestBaks = storageRepo.getTodoBackupCount()
            val tasksToSync = if (exactDelayMillisForLastTask != null && mergedTasks.isNotEmpty()) {
                mergedTasks.dropLast(1)
            } else {
                mergedTasks
            }
            scheduler.syncTaskSchedules(tasksToSync, ZonedDateTime.now())
            withContext(Dispatchers.Main) {
                if (mergedTasks != tasks) {
                    tasks = mergedTasks
                }
                backupCount = latestBaks
            }
        }
    }

    fun reloadAllFromSafOrDisk(customSafUriStr: String? = null, switchToTasksTab: Boolean = false) {
        coroutineScope.launch(Dispatchers.IO) {
            val res = storageRepo.loadAllFromSafFolder(customSafUriStr)
            val latestBaks = storageRepo.getTodoBackupCount()
            scheduler.syncTaskSchedules(res.tasks, ZonedDateTime.now(), forceAll = true)
            withContext(Dispatchers.Main) {
                tasks = res.tasks
                doneLines = res.doneLines
                rulesText = res.rulesText
                notes = res.notes
                backupCount = latestBaks
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

    fun openLinkedNoteByName(rawNoteRef: String) {
        val normalized = NotesRepository.toHumanReadableFilename(rawNoteRef, ".md")
        val target = notes.firstOrNull {
            it.filename.equals(rawNoteRef, ignoreCase = true) ||
                it.filename.equals(normalized, ignoreCase = true)
        }
        if (target != null) {
            openNoteInPopup(target, startInEditMode = false)
        } else {
            // Create a new note pre-titled with the linked filename
            activeNotePopupFilename = NEW_NOTE_SENTINEL
            noteDraftName = normalized
            noteDraftText = "# ${normalized.substringBeforeLast('.')}\n\n"
            noteEditMode = true
        }
    }

    fun createAndOpenNewMarkdownNote() {
        activeNotePopupFilename = NEW_NOTE_SENTINEL
        noteDraftName = ""
        noteDraftText = ""
        noteEditMode = true
    }

    fun commitNoteDraftAndClose(closePopup: Boolean = true) {
        val currentOrigin = activeNotePopupFilename ?: return
        val trimmedTitle = noteDraftName.trim()
        val trimmedBody = noteDraftText.trim()

        if (currentOrigin == NEW_NOTE_SENTINEL && trimmedTitle.isEmpty() && trimmedBody.isEmpty()) {
            if (closePopup) {
                noteEditMode = false
                activeNotePopupFilename = null
            }
            return
        }

        val derivedNameSource = when {
            trimmedTitle.isNotEmpty() -> trimmedTitle
            currentOrigin != NEW_NOTE_SENTINEL -> currentOrigin
            else -> {
                trimmedBody.lines().firstOrNull { it.isNotBlank() }
                    ?.trimStart('#', '-', '*', '>', ' ')
                    ?.take(28)
                    ?.ifBlank { "note-${notes.size + 1}" }
                    ?: "note-${notes.size + 1}"
            }
        }
        val safeTargetName = NotesRepository.toHumanReadableFilename(derivedNameSource, ".md")
        val bodyToSave = noteDraftText

        if (closePopup) {
            noteEditMode = false
            activeNotePopupFilename = null
        } else {
            noteEditMode = false
            activeNotePopupFilename = safeTargetName
            noteDraftName = safeTargetName
        }

        coroutineScope.launch(Dispatchers.IO) {
            if (currentOrigin != NEW_NOTE_SENTINEL && !currentOrigin.equals(safeTargetName, ignoreCase = true)) {
                storageRepo.deleteNote(currentOrigin)
            }
            val saved = storageRepo.saveNote(safeTargetName, bodyToSave)
            withContext(Dispatchers.Main) {
                notes = saved
            }
        }
    }

    // Lifecycle sync:
    // - Never blindly overwrite SAF todo.txt on ON_PAUSE/ON_STOP (tasks already persist on every mutation).
    // - Flush only an open unsaved note draft once on ON_STOP.
    // - Reload external edits on ON_RESUME after initial hydration.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                isAppResumed = false
                val originNote = activeNotePopupFilename
                val shouldSaveNote = originNote != null && (noteDraftName.isNotBlank() || noteDraftText.isNotBlank())
                if (shouldSaveNote && originNote != null) {
                    val draftName = noteDraftName
                    val draftText = noteDraftText
                    coroutineScope.launch(Dispatchers.IO) {
                        val targetName = NotesRepository.toHumanReadableFilename(
                            draftName.ifBlank { if (originNote != NEW_NOTE_SENTINEL) originNote else "note" },
                            ".md"
                        )
                        if (originNote != NEW_NOTE_SENTINEL && !originNote.equals(targetName, ignoreCase = true)) {
                            storageRepo.deleteNote(originNote)
                        }
                        storageRepo.saveNote(targetName, draftText)
                    }
                }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                isAppResumed = true
                currentNow = LocalDateTime.now()
                if (isInitialLoadDone) {
                    coroutineScope.launch(Dispatchers.IO) {
                        val res = storageRepo.loadAllFromSafFolder()
                        val latestBaks = storageRepo.getTodoBackupCount()
                        scheduler.syncTaskSchedules(res.tasks, ZonedDateTime.now())
                        withContext(Dispatchers.Main) {
                            tasks = res.tasks
                            doneLines = res.doneLines
                            rulesText = res.rulesText
                            notes = res.notes
                            backupCount = latestBaks
                        }
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
                        val msg = intent.getStringExtra("extra_status_msg") ?: "Updated todo.txt"
                        coroutineScope.launch(Dispatchers.IO) {
                            val freshTasks = storageRepo.loadTasks()
                            withContext(Dispatchers.Main) {
                                tasks = freshTasks
                                statusBanner = msg
                            }
                        }
                    }
                    ReminderScheduler.ACTION_ALARM_FIRED_UI -> {
                        val remId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: "notif"
                        val tId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: "task"
                        val spec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC) ?: "0m/n"
                        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE) ?: "scheduled task"
                        val nId = intent.getIntExtra("extra_notif_id", (remId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100))
                        cancelShadeNotification(nId)
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

    BackHandler(enabled = true) {
        when {
            showEditDueDateWheel -> showEditDueDateWheel = false
            showAddDueDateWheel -> showAddDueDateWheel = false
            activeAlarmOverlay != null -> {
                val alarm = activeAlarmOverlay!!
                AlarmAudioController.stopAll()
                AlarmBroadcastReceiver.postTrayNotificationOnDismiss(
                    context = context,
                    reminderId = alarm.reminderId,
                    taskId = alarm.taskId,
                    rawSpec = alarm.rawSpec,
                    taskTitle = alarm.taskTitle,
                    notifId = alarm.notifId
                )
                activeAlarmOverlay = null
            }
            editingTask != null -> editingTask = null
            showAddTaskPopup -> showAddTaskPopup = false
            activeNotePopupFilename != null -> commitNoteDraftAndClose(closePopup = true)
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
            else -> (context as? Activity)?.finish()
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
            dismissedInferredTags = emptySet()
            showAddTaskPopup = false
            focusManager.clearFocus()
            coroutineScope.launch { pagerState.animateScrollToPage(1) }
        } else {
            var newTask = TodoParser.parseLine(res.formattedTodoLine, tasks.size + 1)
            if (dismissedInferredTags.isNotEmpty()) {
                val filteredTokens = newTask.bodyTokens.filterNot { tok ->
                    when (tok) {
                        is RawToken.Project -> "+${tok.name}" in dismissedInferredTags || tok.rawText in dismissedInferredTags
                        is RawToken.ContextTag -> "@${tok.name}" in dismissedInferredTags || tok.rawText in dismissedInferredTags
                        else -> tok.rawText in dismissedInferredTags
                    }
                }
                newTask = newTask.copy(bodyTokens = filteredTokens, isDirty = true)
            }
            if (addTaskPriority != null && newTask.priority == null) {
                newTask = newTask.copy(priority = addTaskPriority, isDirty = true)
            }
            // Preserve explicit/NLP-parsed `rem:` specs and respect `don't remind me` suppression
            val chosenSpec = if (newTask.reminderSpecs.isNotEmpty()) {
                newTask.reminderSpecs.first()
            } else {
                val fallback = if (newTask.dueTime != null) settings.defaultTimedReminder else settings.defaultDateOnlyReminder
                if (!res.suppressReminder && (newTask.dueDate != null || res.exactOffsetMillis != null)) {
                    newTask = TodoParser.withReplacedMultiTag(newTask, "rem", listOf(fallback))
                }
                fallback
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
            dismissedInferredTags = emptySet()
            showAddTaskPopup = false
            focusManager.clearFocus()
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

    val todayDate = currentNow.toLocalDate()
    val filteredTasks = remember(tasks, activeFilter, searchQuery, searchHits, todayDate) {
        val matchedTaskIds = if (searchQuery.isBlank()) null else searchHits.filter { it.kind == "task" }.map { it.id }.toSet()
        tasks.filter { task ->
            if (task.isBlankOrComment) return@filter false
            if (matchedTaskIds != null && task.stableId !in matchedTaskIds) return@filter false
            val isDeferred = task.thresholdDate != null && task.thresholdDate!!.isAfter(todayDate)
            when {
                matchedTaskIds != null -> true
                activeFilter == "open" -> !task.isCompleted && !isDeferred
                activeFilter == "due" -> !task.isCompleted && !isDeferred && task.dueDate != null
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

    val filteredNotes = remember(notes, searchQuery, matchedNoteHits) {
        if (searchQuery.isBlank()) {
            notes
        } else {
            val matchedNames = matchedNoteHits.map { it.id }.toSet()
            notes.filter { it.filename in matchedNames }
        }
    }

    val noteBacklinkCounts = remember(tasks) {
        val counts = HashMap<String, Int>()
        for (t in tasks) {
            if (t.isBlankOrComment) continue
            for (linked in t.linkedNotes) {
                val key = NotesRepository.toHumanReadableFilename(linked, ".md").lowercase()
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        counts
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
                }
                val fmtTime = targetZdt.format(DateTimeFormatter.ofPattern("HH:mm"))
                statusBanner = "[zZ Snooze] Until $fmtTime"
                activeAlarmOverlay = null
            },
            onDismiss = {
                AlarmAudioController.stopAll()
                AlarmBroadcastReceiver.postTrayNotificationOnDismiss(
                    context = context,
                    reminderId = alarm.reminderId,
                    taskId = alarm.taskId,
                    rawSpec = alarm.rawSpec,
                    taskTitle = alarm.taskTitle,
                    notifId = alarm.notifId
                )
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
                statusBanner = "[x Done] ${alarm.taskTitle}"
                activeAlarmOverlay = null
            }
        )
        return
    }

    val latestTasks by rememberUpdatedState(tasks)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
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
                        // TAB 0: TASKS
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                val isTagFiltered = activeFilter.startsWith("+") || activeFilter.startsWith("@")
                                val tagButtonLabel = if (isTagFiltered) " $activeFilter " else "[+@]"

                                AsciiHorizontalBorder()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "| ",
                                        color = palette.border,
                                        fontSize = 12.sp,
                                        fontFamily = monoFont
                                    )
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val openTabCount = tasks.count {
                                            !it.isBlankOrComment && !it.isCompleted &&
                                                (it.thresholdDate == null || !it.thresholdDate!!.isAfter(todayDate))
                                        }
                                        val allTabCount = tasks.count { !it.isBlankOrComment }
                                        val dueTabCount = tasks.count {
                                            !it.isBlankOrComment && !it.isCompleted && it.dueDate != null &&
                                                (it.thresholdDate == null || !it.thresholdDate!!.isAfter(todayDate))
                                        }
                                        val doneTabCount = tasks.count { !it.isBlankOrComment && it.isCompleted }

                                        listOf(
                                            "open" to (if (openTabCount > 0) "Open($openTabCount)" else "Open"),
                                            "all" to (if (allTabCount > 0) "All($allTabCount)" else "All"),
                                            "due" to (if (dueTabCount > 0) "Due($dueTabCount)" else "Due"),
                                            "done" to (if (doneTabCount > 0) "Done($doneTabCount)" else "Done")
                                        ).forEach { (fKey, fLabel) ->
                                            val active = activeFilter == fKey && searchQuery.isBlank()
                                            Text(
                                                text = if (active) " $fLabel " else fLabel,
                                                color = if (active) palette.bg else palette.fg,
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                                fontFamily = appFont,
                                                modifier = Modifier
                                                    .then(if (active) Modifier.background(palette.project) else Modifier)
                                                    .clickable {
                                                        searchQuery = ""
                                                        updateFilterWithHistory(fKey)
                                                    }
                                                    .padding(vertical = 2.dp, horizontal = 2.dp)
                                            )
                                        }

                                        Text(
                                            text = tagButtonLabel,
                                            color = if (isTagFiltered) palette.bg else palette.fgMuted,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = appFont,
                                            modifier = Modifier
                                                .then(if (isTagFiltered) Modifier.background(palette.contextTag) else Modifier)
                                                .clickable { showTagsPopup = true }
                                                .padding(vertical = 2.dp, horizontal = 2.dp)
                                        )

                                        val searchActive = showSearchBar || searchQuery.isNotBlank()
                                        Text(
                                            text = if (searchActive) " / " else "[/]",
                                            color = if (searchActive) palette.bg else palette.fgMuted,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = monoFont,
                                            modifier = Modifier
                                                .then(if (searchActive) Modifier.background(palette.reminder) else Modifier)
                                                .clickable {
                                                    showSearchBar = !showSearchBar
                                                    if (!showSearchBar) searchQuery = ""
                                                }
                                                .padding(vertical = 2.dp, horizontal = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = " |",
                                        color = palette.border,
                                        fontSize = 12.sp,
                                        fontFamily = monoFont
                                    )
                                }

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
                                            text = "[X]",
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
                                            color = palette.border,
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
                                val completedInTodoCount = tasks.count { !it.isBlankOrComment && it.isCompleted }
                                if (activeFilter == "done" && completedInTodoCount > 0) {
                                    AsciiHorizontalBorder(corner = "|")
                                    AsciiBoxLeftRightRow(
                                        left = "$completedInTodoCount completed in todo.txt",
                                        right = "[x Archive -> done.txt]",
                                        leftColor = palette.fgMuted,
                                        rightColor = palette.reminder,
                                        fontSizeSp = (fontScaleSp - 2).coerceAtLeast(11),
                                        boldRight = true,
                                        onClick = {
                                            undoSnapshot = tasks
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val (remaining, newDone, count) = storageRepo.archiveCompletedTasks(tasks, doneLines)
                                                scheduler.syncTaskSchedules(remaining, ZonedDateTime.now())
                                                val bCount = storageRepo.getTodoBackupCount()
                                                withContext(Dispatchers.Main) {
                                                    tasks = remaining
                                                    doneLines = newDone
                                                    backupCount = bCount
                                                    statusBanner = "[x Archive] Moved $count task(s) to done.txt"
                                                }
                                            }
                                        }
                                    )
                                }
                                AsciiHorizontalBorder()

                                Spacer(modifier = Modifier.height(4.dp))

                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 58.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (filteredTasks.isEmpty() && isInitialLoadDone) {
                                        item {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 14.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AsciiTitledTopBorder(
                                                    badgeText = "ALL CLEAR",
                                                    badgeColor = palette.reminder,
                                                    rightBadgeText = activeFilter.uppercase()
                                                )
                                                if (searchQuery.isBlank()) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "|\n|\n|\n|",
                                                            color = palette.border,
                                                            fontSize = 12.sp,
                                                            lineHeight = 15.sp,
                                                            fontFamily = monoFont
                                                        )
                                                        Text(
                                                            text = PlainTextSportsAscii.TROPHY_ART,
                                                            color = palette.priorityB,
                                                            fontSize = 12.sp,
                                                            lineHeight = 15.sp,
                                                            fontFamily = monoFont,
                                                            textAlign = TextAlign.Center,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        Text(
                                                            text = "|\n|\n|\n|",
                                                            color = palette.border,
                                                            fontSize = 12.sp,
                                                            lineHeight = 15.sp,
                                                            fontFamily = monoFont
                                                        )
                                                    }
                                                }
                                                AsciiBoxBodyRow(
                                                    text = if (searchQuery.isNotBlank()) {
                                                        "No tasks matching \"$searchQuery\""
                                                    } else {
                                                        "Zero pending items in [$activeFilter] -- plain text is clean."
                                                    },
                                                    textColor = palette.fgMuted,
                                                    fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                                                )
                                                AsciiHorizontalBorder(corner = "|")
                                                AsciiBoxLeftRightRow(
                                                    left = "Create a new task",
                                                    right = "[+ Add Task]",
                                                    leftColor = palette.fg,
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
                                                    val currentTasks = latestTasks
                                                    when (targetValue) {
                                                        SwipeToDismissBoxValue.StartToEnd -> {
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            undoSnapshot = currentTasks
                                                            val updated = if (task.isCompleted) {
                                                                TodoParser.markIncomplete(task) to null
                                                            } else {
                                                                TodoParser.markCompleted(task, LocalDate.now())
                                                            }
                                                            val nextList = currentTasks.map {
                                                                if (it.lineNumber == task.lineNumber) updated.first else it
                                                            } + listOfNotNull(
                                                                updated.second?.copy(lineNumber = currentTasks.size + 1)
                                                            )
                                                            persistAndSchedule(nextList)
                                                            statusBanner = if (task.isCompleted) "[< Reopen] ${task.description}" else "[x Done] ${task.description}"
                                                            false
                                                        }
                                                        SwipeToDismissBoxValue.EndToStart -> {
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            undoSnapshot = currentTasks
                                                            persistAndSchedule(currentTasks.filterNot { it.lineNumber == task.lineNumber })
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
                                                        SwipeToDismissBoxValue.StartToEnd -> "| [x Done] >>>"
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
                                                        },
                                                        onOpenLinkedNote = { noteRef ->
                                                            openLinkedNoteByName(noteRef)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Non-displacing Floating Undo/Status Toast at Bottom-Start
                            if (statusBanner != null) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 12.dp, end = 108.dp, bottom = 6.dp)
                                        .background(palette.bgSurface)
                                ) {
                                    AsciiHorizontalBorder()
                                    AsciiBoxLeftRightRow(
                                        left = statusBanner ?: "",
                                        right = if (undoSnapshot != null || undoDeletedNote != null) "[< Undo]" else "[X]",
                                        leftColor = palette.reminder,
                                        rightColor = palette.project,
                                        fontSizeSp = (fontScaleSp - 2).coerceAtLeast(11),
                                        boldRight = true,
                                        onClick = {
                                            if (undoSnapshot != null) {
                                                persistAndSchedule(undoSnapshot!!)
                                                undoSnapshot = null
                                            }
                                            if (undoDeletedNote != null) {
                                                val (restoredName, restoredBody) = undoDeletedNote!!
                                                undoDeletedNote = null
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val saved = storageRepo.saveNote(restoredName, restoredBody)
                                                    withContext(Dispatchers.Main) { notes = saved }
                                                }
                                            }
                                            statusBanner = null
                                        }
                                    )
                                    AsciiHorizontalBorder()
                                }
                            }

                            AsciiFloatingAddButton(
                                onClick = { showAddTaskPopup = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 6.dp)
                            )
                        }
                    }

                    1 -> {
                        // TAB 1: LAZY STAGGERED MARKDOWN NOTES BOARD
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                AsciiHorizontalBorder()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "| ",
                                        color = palette.border,
                                        fontSize = 12.sp,
                                        fontFamily = monoFont
                                    )
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "/ NOTES (${filteredNotes.size})",
                                            color = palette.project,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = monoFont
                                        )
                                        Text(
                                            text = if (notesTwoColumnMode) " 2-Col " else "2-Col",
                                            color = if (notesTwoColumnMode) palette.bg else palette.fg,
                                            fontWeight = if (notesTwoColumnMode) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = appFont,
                                            modifier = Modifier
                                                .then(if (notesTwoColumnMode) Modifier.background(palette.project) else Modifier)
                                                .clickable { notesTwoColumnMode = true }
                                                .padding(vertical = 2.dp, horizontal = 2.dp)
                                        )
                                        Text(
                                            text = if (!notesTwoColumnMode) " 1-Col " else "1-Col",
                                            color = if (!notesTwoColumnMode) palette.bg else palette.fg,
                                            fontWeight = if (!notesTwoColumnMode) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = appFont,
                                            modifier = Modifier
                                                .then(if (!notesTwoColumnMode) Modifier.background(palette.project) else Modifier)
                                                .clickable { notesTwoColumnMode = false }
                                                .padding(vertical = 2.dp, horizontal = 2.dp)
                                        )
                                        val noteSearchActive = showSearchBar || searchQuery.isNotBlank()
                                        Text(
                                            text = if (noteSearchActive) " / " else "[/]",
                                            color = if (noteSearchActive) palette.bg else palette.fgMuted,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = monoFont,
                                            modifier = Modifier
                                                .then(if (noteSearchActive) Modifier.background(palette.reminder) else Modifier)
                                                .clickable {
                                                    showSearchBar = !showSearchBar
                                                    if (!showSearchBar) searchQuery = ""
                                                }
                                                .padding(vertical = 2.dp, horizontal = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = " |",
                                        color = palette.border,
                                        fontSize = 12.sp,
                                        fontFamily = monoFont
                                    )
                                }

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
                                                    text = "Search notes...",
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
                                            text = "[X]",
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
                                            color = palette.border,
                                            fontSize = 12.sp,
                                            fontFamily = monoFont
                                        )
                                    }
                                }
                                AsciiHorizontalBorder()

                                Spacer(modifier = Modifier.height(6.dp))

                                if (filteredNotes.isEmpty() && isInitialLoadDone) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        AsciiTitledTopBorder(
                                            badgeText = "NOTES BOARD EMPTY",
                                            badgeColor = palette.project,
                                            rightBadgeText = ".md"
                                        )
                                        AsciiBoxBodyRow(
                                            text = if (searchQuery.isNotBlank()) {
                                                "No Markdown notes matching \"$searchQuery\""
                                            } else {
                                                "No Markdown (.md) notes on the board yet."
                                            },
                                            textColor = palette.fgMuted,
                                            fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                                        )
                                        AsciiHorizontalBorder(corner = "|")
                                        AsciiBoxLeftRightRow(
                                            left = "Start a Markdown checklist or note",
                                            right = "[+ New Note]",
                                            leftColor = palette.fg,
                                            rightColor = palette.reminder,
                                            boldRight = true,
                                            onClick = { createAndOpenNewMarkdownNote() }
                                        )
                                        AsciiHorizontalBorder()
                                    }
                                } else {
                                    LazyVerticalStaggeredGrid(
                                        columns = StaggeredGridCells.Fixed(if (notesTwoColumnMode) 2 else 1),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalItemSpacing = 8.dp,
                                        contentPadding = PaddingValues(bottom = 62.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    ) {
                                        staggeredItems(filteredNotes, key = { it.filename }) { note ->
                                            val cardFontSize = if (notesTwoColumnMode) {
                                                (fontScaleSp - 2).coerceAtLeast(12)
                                            } else {
                                                (fontScaleSp - 1).coerceAtLeast(13)
                                            }
                                            KeepAsciiMarkdownCard(
                                                note = note,
                                                fontSizeSp = cardFontSize,
                                                maxHalfScreenVisualLines = 14,
                                                backlinkCount = noteBacklinkCounts[note.filename.lowercase()] ?: 0,
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

                            if (statusBanner != null) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 12.dp, end = 108.dp, bottom = 6.dp)
                                        .background(palette.bgSurface)
                                ) {
                                    AsciiHorizontalBorder()
                                    AsciiBoxLeftRightRow(
                                        left = statusBanner ?: "",
                                        right = if (undoDeletedNote != null) "[< Undo]" else "[X]",
                                        leftColor = palette.reminder,
                                        rightColor = palette.project,
                                        fontSizeSp = (fontScaleSp - 2).coerceAtLeast(11),
                                        boldRight = true,
                                        onClick = {
                                            if (undoDeletedNote != null) {
                                                val (restoredName, restoredBody) = undoDeletedNote!!
                                                undoDeletedNote = null
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val saved = storageRepo.saveNote(restoredName, restoredBody)
                                                    withContext(Dispatchers.Main) { notes = saved }
                                                }
                                            }
                                            statusBanner = null
                                        }
                                    )
                                    AsciiHorizontalBorder()
                                }
                            }

                            AsciiFloatingAddButton(
                                onClick = { createAndOpenNewMarkdownNote() },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 6.dp)
                            )
                        }
                    }

                    else -> {
                        // TAB 2: SETTINGS
                        SettingsScreen(
                            baseDir = storageRepo.baseDir,
                            storageFolderPath = settings.customSafUri ?: storageRepo.baseDir.absolutePath,
                            settings = settings,
                            backupCount = backupCount,
                            completedCount = tasks.count { !it.isBlankOrComment && it.isCompleted },
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
                            },
                            onPickFolderWithSaf = { folderPickerLauncher.launch(null) },
                            onReloadFromFolder = { reloadAllFromSafOrDisk(switchToTasksTab = false) },
                            onOpenSavedFileLocation = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val text = runCatching { storageRepo.todoFile.readText() }.getOrDefault("")
                                    withContext(Dispatchers.Main) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "todo.txt")
                                            putExtra(Intent.EXTRA_TEXT, text)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(shareIntent, "Share or open todo.txt"))
                                        }
                                    }
                                }
                            },
                            onArchiveCompletedTasks = {
                                val completed = tasks.filter { it.isCompleted }
                                if (completed.isNotEmpty()) {
                                    undoSnapshot = tasks
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val (remaining, newDone, count) = storageRepo.archiveCompletedTasks(tasks, doneLines)
                                        scheduler.syncTaskSchedules(remaining, ZonedDateTime.now())
                                        val bCount = storageRepo.getTodoBackupCount()
                                        withContext(Dispatchers.Main) {
                                            tasks = remaining
                                            doneLines = newDone
                                            backupCount = bCount
                                            statusBanner = "[x Archive] Moved $count task(s) to done.txt"
                                        }
                                    }
                                }
                            },
                            onRestoreLatestBackup = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val restored = storageRepo.restoreLatestTodoBackup()
                                    val bCount = storageRepo.getTodoBackupCount()
                                    if (restored != null) {
                                        scheduler.syncTaskSchedules(restored, ZonedDateTime.now(), forceAll = true)
                                        withContext(Dispatchers.Main) {
                                            tasks = restored
                                            backupCount = bCount
                                            statusBanner = "[< Backup] Restored ${restored.size} task(s)"
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            backupCount = bCount
                                            statusBanner = "[< Backup] No backup snapshots available"
                                        }
                                    }
                                }
                            },
                            onFireImmediateNotification = {
                                triggerFullNotification(
                                    taskTitle = "(A) review architecture notes +work @desk rem:0m/n",
                                    rawSpec = "0m/n",
                                    taskId = "test_notif"
                                )
                            }
                        )
                    }
                }
            }

            // =================================================================
            // BOTTOM NAVIGATION TABS BOX (` Tasks (N) | Notes (M) | Settings `)
            // =================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bg)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                val openCount = tasks.count {
                    !it.isBlankOrComment && !it.isCompleted &&
                        (it.thresholdDate == null || !it.thresholdDate!!.isAfter(todayDate))
                }
                AsciiHorizontalBorder()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "| ",
                        color = palette.border,
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
                                text = if (selected) " $tabLabel " else tabLabel,
                                color = if (selected) palette.bg else palette.fg,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = (fontScaleSp - 1).coerceAtLeast(13).sp,
                                fontFamily = appFont,
                                modifier = Modifier
                                    .then(if (selected) Modifier.background(palette.project) else Modifier)
                                    .clickable {
                                        statusBanner = null
                                        editingTask = null
                                        showAddTaskPopup = false
                                        activeNotePopupFilename = null
                                        showTagsPopup = false
                                        coroutineScope.launch { pagerState.animateScrollToPage(tabIdx) }
                                    }
                                    .padding(vertical = 3.dp, horizontal = 4.dp)
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
                AsciiHorizontalBorder()
            }
        }

        // =====================================================================
        // MODAL OVERLAYS
        // =====================================================================
        if (activeNotePopupFilename != null) {
            val isNewNoteDraft = activeNotePopupFilename == NEW_NOTE_SENTINEL
            val currentNote = notes.firstOrNull { it.filename == activeNotePopupFilename }
            val backlinkedTasks = remember(activeNotePopupFilename, tasks) {
                if (isNewNoteDraft) emptyList()
                else tasks.filter { task ->
                    task.linkedNotes.any { ref ->
                        ref.equals(activeNotePopupFilename, ignoreCase = true) ||
                            NotesRepository.toHumanReadableFilename(ref, ".md")
                                .equals(activeNotePopupFilename, ignoreCase = true)
                    }
                }
            }
            NoteEditorDialog(
                isNewNoteDraft = isNewNoteDraft,
                currentNote = currentNote,
                noteEditMode = noteEditMode,
                noteDraftName = noteDraftName,
                noteDraftText = noteDraftText,
                backlinkedTasks = backlinkedTasks,
                fontSizeSp = fontScaleSp,
                onNoteDraftNameChange = { noteDraftName = it },
                onNoteDraftTextChange = { noteDraftText = it },
                onSetEditMode = { noteEditMode = it },
                onTogglePreviewCheckbox = { idx ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val toggled = toggleMarkdownCheckboxAtLine(noteDraftText, idx)
                    noteDraftText = toggled
                    val origin = activeNotePopupFilename
                    if (origin != null && origin != NEW_NOTE_SENTINEL) {
                        coroutineScope.launch(Dispatchers.IO) {
                            val saved = storageRepo.saveNote(origin, toggled)
                            withContext(Dispatchers.Main) { notes = saved }
                        }
                    }
                },
                onTapBacklinkedTask = { linked ->
                    commitNoteDraftAndClose(closePopup = true)
                    editingTask = linked
                    editingTaskDraft = TodoParser.serializeLine(linked)
                },
                onCommitAndKeepOpen = { commitNoteDraftAndClose(closePopup = false) },
                onCommitAndClose = { commitNoteDraftAndClose(closePopup = true) },
                onDeleteNote = {
                    val targetToDelete = activeNotePopupFilename ?: noteDraftName
                    val backupBody = currentNote?.bodyContent ?: noteDraftText
                    undoDeletedNote = targetToDelete to backupBody
                    noteEditMode = false
                    activeNotePopupFilename = null
                    statusBanner = "[X Delete] $targetToDelete"
                    coroutineScope.launch(Dispatchers.IO) {
                        val updated = storageRepo.deleteNote(targetToDelete)
                        withContext(Dispatchers.Main) { notes = updated }
                    }
                }
            )
        }

        if (showTagsPopup) {
            TagsFilterDialog(
                dynamicProjects = dynamicProjects,
                dynamicContexts = dynamicContexts,
                activeFilter = activeFilter,
                fontSizeSp = fontScaleSp,
                onSelectFilter = { updateFilterWithHistory(it) },
                onDismiss = { showTagsPopup = false }
            )
        }

        if (editingTask != null) {
            val target = editingTask!!
            EditTaskDialog(
                target = target,
                editingTaskDraft = editingTaskDraft,
                showDueDateWheel = showEditDueDateWheel,
                quickTagChips = quickTagChips,
                defaultTimedReminder = settings.defaultTimedReminder,
                currentNow = currentNow,
                fontSizeSp = fontScaleSp,
                onDraftChange = { editingTaskDraft = it },
                onSetShowDueDateWheel = { showEditDueDateWheel = it },
                onSyncGcal = { parsed ->
                    if (gcalManager.hasCalendarPermissions()) {
                        coroutineScope.launch(Dispatchers.IO) {
                            val (synced, ok) = gcalManager.syncTaskToGoogleCalendar(parsed)
                            withContext(Dispatchers.Main) {
                                if (ok) {
                                    persistAndSchedule(
                                        tasks.map { if (it.lineNumber == target.lineNumber) synced else it }
                                    )
                                    editingTask = null
                                    statusBanner = "[# GCal] Synced"
                                } else {
                                    gcalManager.openInGoogleCalendarApp(parsed)
                                }
                            }
                        }
                    } else {
                        gcalManager.openInGoogleCalendarApp(parsed)
                    }
                },
                onSave = { reParsed ->
                    undoSnapshot = tasks
                    learnRuleFromSavedTask(reParsed)
                    persistAndSchedule(
                        tasks.map { if (it.lineNumber == target.lineNumber) reParsed else it }
                    )
                    editingTask = null
                },
                onDelete = {
                    undoSnapshot = tasks
                    persistAndSchedule(tasks.filterNot { it.lineNumber == target.lineNumber })
                    editingTask = null
                    statusBanner = "[X Delete] Removed task"
                },
                onDismiss = { editingTask = null }
            )
        }

        if (showAddTaskPopup) {
            AddTaskDialog(
                smartInput = smartInput,
                parsePreview = parsePreview,
                addTaskPriority = addTaskPriority,
                dismissedInferredTags = dismissedInferredTags,
                quickTagChips = quickTagChips,
                showDueDateWheel = showAddDueDateWheel,
                currentNow = currentNow,
                fontSizeSp = fontScaleSp,
                inputFocusRequester = addInputFocusRequester,
                onSmartInputChange = {
                    smartInput = it
                    if (it.isBlank()) {
                        disabledSpans = emptySet()
                        dismissedInferredTags = emptySet()
                    }
                },
                onDismissInferredTag = { dismissedInferredTags = dismissedInferredTags + it },
                onDisableSpan = { disabledSpans = disabledSpans + it },
                onSelectPriority = { addTaskPriority = it },
                onSetShowDueDateWheel = { showAddDueDateWheel = it },
                onSubmit = { submitSmartAdd() },
                onDismiss = { showAddTaskPopup = false }
            )
        }
    }
}
