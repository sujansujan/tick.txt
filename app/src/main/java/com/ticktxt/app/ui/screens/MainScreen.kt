package com.ticktxt.app.ui.screens

import android.Manifest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ticktxt.app.core.calendar.GoogleCalendarSyncManager
import com.ticktxt.app.core.nlp.RulesEngine
import com.ticktxt.app.core.nlp.SmartAddParser
import com.ticktxt.app.core.notes.NotesRepository
import com.ticktxt.app.core.reminders.ReminderScheduleCalculator
import com.ticktxt.app.core.reminders.ReminderScheduler
import com.ticktxt.app.core.reminders.SnoozeUnit
import com.ticktxt.app.core.storage.PersistedSettings
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ActiveAlarmPayload(
    val reminderId: String,
    val taskId: String,
    val taskTitle: String,
    val rawSpec: String,
    val isAlarmMuted: Boolean = true
)

/**
 * Main screen implementing:
 * 1. Sketch-matched compact 4-line task boxes (`+--[Date and Time]---+`, `| [ ] Task |`, `| [✓] [zZ] [!] [#] |`, `+---+`)
 * 2. Bottom navigation tabs (`[*] Tasks | [/] Notes | [#] Settings`)
 * 3. Shaded-background popups for Task Edit/Snooze, Add Task, and Projects/Contexts (`[+@ Tags]`)
 * 4. Compact vertical spacing so 6-8+ tasks fit on screen before scrolling.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    var currentTab by remember { mutableStateOf("tasks") } // "tasks", "notes", "settings"
    var activeFilter by remember { mutableStateOf("open") }

    // Shaded-background Popups
    var editingTask by remember { mutableStateOf<TodoTask?>(null) }
    var editingTaskDraft by remember { mutableStateOf("") }
    var editSnoozeUnit by remember { mutableStateOf(SnoozeUnit.MINUTES) }
    var editSnoozeAmount by remember { mutableStateOf(5) }

    var showAddTaskPopup by remember { mutableStateOf(false) }
    var smartInput by remember { mutableStateOf("") }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var pickerUnit by remember { mutableStateOf(SnoozeUnit.MINUTES) }
    var pickerAmount by remember { mutableStateOf(5) }

    var showTagsPopup by remember { mutableStateOf(false) }

    var activeAlarmOverlay by remember { mutableStateOf<ActiveAlarmPayload?>(null) }

    var undoSnapshot by remember { mutableStateOf<List<TodoTask>?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

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
            val (syncedList, count) = gcalManager.syncAllDueTasks(tasks)
            tasks = syncedList
            storageRepo.saveTasks(syncedList)
            statusBanner = "[#] Synced $count task(s)"
        } else {
            statusBanner = "[!] Calendar permission needed"
        }
    }

    fun triggerFullAlarmAndNotification(taskTitle: String, rawSpec: String = "0m/a", taskId: String = "immediate") {
        val mutedDefault = settings.alarmMutedByDefault
        scheduler.fireImmediateNotification(
            taskTitle = taskTitle,
            rawSpec = rawSpec,
            typeCode = "a",
            taskId = taskId
        )
        AlarmAudioController.applyAudioMode(context, isAlarmMuted = mutedDefault)
        activeAlarmOverlay = ActiveAlarmPayload(
            reminderId = "$taskId|$rawSpec",
            taskId = taskId,
            taskTitle = taskTitle,
            rawSpec = rawSpec,
            isAlarmMuted = mutedDefault
        )
    }

    fun scheduleExactDelayAlarm(task: TodoTask, delayMs: Long, rawSpec: String = "0m/a") {
        val safeDelay = delayMs.coerceAtLeast(1_000L)
        val targetEpochMs = System.currentTimeMillis() + safeDelay
        scheduler.scheduleRaw(
            reminderId = "${task.stableId}|$rawSpec",
            taskStableId = task.stableId,
            rawSpec = rawSpec,
            taskDescription = task.description,
            typeCode = "a",
            triggerEpochMs = targetEpochMs
        )
        if (safeDelay <= 7_200_000L) {
            coroutineScope.launch {
                delay(safeDelay)
                triggerFullAlarmAndNotification(
                    taskTitle = task.description,
                    rawSpec = rawSpec,
                    taskId = task.stableId
                )
            }
        }
    }

    fun persistAndSchedule(
        updatedTasks: List<TodoTask>,
        exactDelayMillisForLastTask: Long? = null,
        syncLastToCalendar: Boolean = false
    ) {
        var finalTasks = updatedTasks.mapIndexed { idx, t -> t.copy(lineNumber = idx + 1) }

        if (syncLastToCalendar && settings.autoSyncGoogleCalendar && finalTasks.isNotEmpty()) {
            val last = finalTasks.last()
            if (last.dueDate != null && gcalManager.hasCalendarPermissions()) {
                val (syncedLast, _) = gcalManager.syncTaskToGoogleCalendar(last)
                finalTasks = finalTasks.dropLast(1) + syncedLast
            }
        }

        tasks = finalTasks
        storageRepo.saveTasks(finalTasks)

        val nowZdt = ZonedDateTime.now()
        finalTasks.forEachIndexed { idx, t ->
            val isLastAdded = (idx == finalTasks.lastIndex && exactDelayMillisForLastTask != null)
            if (isLastAdded && exactDelayMillisForLastTask != null) {
                scheduleExactDelayAlarm(t, exactDelayMillisForLastTask, "0m/a")
            } else {
                val instances = ReminderScheduleCalculator.computeSchedulesForTask(t, nowZdt)
                instances.forEach { inst ->
                    val targetMs = inst.triggerAt.toInstant().toEpochMilli()
                    if (targetMs > System.currentTimeMillis() - 5_000L) {
                        scheduler.scheduleInstance(inst, overrideTriggerEpochMs = targetMs)
                    }
                }
            }
        }
    }

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
                        val remId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: "alarm"
                        val tId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: "task"
                        val spec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC) ?: "0m/a"
                        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE) ?: "scheduled task"
                        val muted = intent.getBooleanExtra("extra_alarm_muted", settings.alarmMutedByDefault)
                        activeAlarmOverlay = ActiveAlarmPayload(remId, tId, title, spec, muted)
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

    BackHandler(
        enabled = activeAlarmOverlay != null || editingTask != null || showAddTaskPopup || showTagsPopup || noteEditMode || currentTab != "tasks"
    ) {
        when {
            activeAlarmOverlay != null -> {
                AlarmAudioController.stopAll()
                activeAlarmOverlay = null
            }
            editingTask != null -> editingTask = null
            showAddTaskPopup -> showAddTaskPopup = false
            showTagsPopup -> showTagsPopup = false
            noteEditMode -> {
                if (noteDraftName.isNotBlank()) {
                    notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                }
                noteEditMode = false
            }
            currentTab != "tasks" -> {
                currentTab = "tasks"
                statusBanner = null
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
            onUpdateSettings(settings.copy(customSafUri = uri.toString()))
            storageRepo.saveTasks(tasks)
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
            showAddTaskPopup = false
            focusManager.clearFocus()
            statusBanner = null
            currentTab = "notes"
        } else {
            undoSnapshot = tasks
            val newTask = TodoParser.parseLine(res.formattedTodoLine, tasks.size + 1)
            val updatedList = tasks + newTask
            persistAndSchedule(
                updatedList,
                exactDelayMillisForLastTask = res.exactOffsetMillis,
                syncLastToCalendar = true
            )
            smartInput = ""
            disabledSpans = emptySet()
            showAddTaskPopup = false
            focusManager.clearFocus()
            val exactMs = res.exactOffsetMillis
            val remInfo = when {
                exactMs != null && exactMs < 60_000L -> " (in ${exactMs / 1000L}s)"
                newTask.dueTime != null -> " (@${newTask.dueTime})"
                else -> ""
            }
            statusBanner = "[+] ${newTask.description}$remInfo"
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
                activeFilter == "done" -> task.isCompleted
                activeFilter.startsWith("+") -> task.projects.contains(activeFilter.drop(1))
                activeFilter.startsWith("@") -> task.contexts.contains(activeFilter.drop(1))
                else -> true
            }
        }
    }

    // =========================================================================
    // FULL-SCREEN ASCII ALARM OVERLAY
    // =========================================================================
    if (activeAlarmOverlay != null) {
        val alarm = activeAlarmOverlay!!
        AsciiFullScreenAlarmSurface(
            taskTitle = alarm.taskTitle,
            rawSpec = alarm.rawSpec,
            fontSizeSp = fontScaleSp,
            initialAlarmMuted = alarm.isAlarmMuted,
            onToggleAlarmMute = { mutedNow ->
                if (mutedNow) {
                    AlarmAudioController.playSystemNotificationBeepOnce(context)
                } else {
                    AlarmAudioController.applyAudioMode(context, isAlarmMuted = false)
                }
            },
            onSnoozeWithPreset = { presetSpec ->
                AlarmAudioController.stopAll()
                val targetZdt = ReminderScheduleCalculator.computeSnoozeTarget(ZonedDateTime.now(), presetSpec)
                val delayMs = (targetZdt.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1_000L)
                val matchingTask = tasks.firstOrNull {
                    it.stableId == alarm.taskId || it.description.equals(alarm.taskTitle, ignoreCase = true)
                }
                if (matchingTask != null) {
                    scheduleExactDelayAlarm(matchingTask, delayMs, alarm.rawSpec)
                } else {
                    scheduler.scheduleRaw(
                        reminderId = alarm.reminderId,
                        taskStableId = alarm.taskId,
                        rawSpec = alarm.rawSpec,
                        taskDescription = alarm.taskTitle,
                        typeCode = "a",
                        triggerEpochMs = targetZdt.toInstant().toEpochMilli()
                    )
                    if (delayMs <= 7_200_000L) {
                        coroutineScope.launch {
                            delay(delayMs)
                            triggerFullAlarmAndNotification(alarm.taskTitle, alarm.rawSpec, alarm.taskId)
                        }
                    }
                }
                val fmtTime = targetZdt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                statusBanner = "[zZ] +$presetSpec ($fmtTime)"
                activeAlarmOverlay = null
            },
            onDismiss = {
                AlarmAudioController.stopAll()
                statusBanner = "[X] Alarm dismissed"
                activeAlarmOverlay = null
            },
            onMarkDone = {
                AlarmAudioController.stopAll()
                undoSnapshot = tasks
                val nextList = tasks.map { t ->
                    if (!t.isCompleted && (t.stableId == alarm.taskId || t.description.equals(alarm.taskTitle, ignoreCase = true))) {
                        TodoParser.markCompleted(t, LocalDate.now()).first
                    } else {
                        t
                    }
                }
                persistAndSchedule(nextList)
                statusBanner = "[✓] ${alarm.taskTitle}"
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
            // MAIN TAB CONTENT (Takes up top & middle of screen)
            // =================================================================
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    "tasks" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            // 1. Compact Single-Line Top Bar: Views + [+@ Tags] button + [+ Add] button
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
                                        val active = activeFilter == fKey
                                        Text(
                                            text = if (active) "[*$fLabel*]" else fLabel,
                                            color = if (active) palette.project else palette.fg,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                            fontFamily = appFont,
                                            modifier = Modifier
                                                .clickable { activeFilter = fKey }
                                                .padding(vertical = 2.dp, horizontal = 2.dp)
                                        )
                                    }

                                    // Button to open Projects (+) & Contexts (@) Popup
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

                                    // Button to open Add Task Popup
                                    Text(
                                        text = "[+ Add]",
                                        color = palette.reminder,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (fontScaleSp - 2).coerceAtLeast(12).sp,
                                        fontFamily = appFont,
                                        modifier = Modifier
                                            .clickable { showAddTaskPopup = true }
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
                            AsciiHorizontalBorder()

                            // Compact Status / Undo Row (if active)
                            if (statusBanner != null) {
                                AsciiBoxLeftRightRow(
                                    left = statusBanner ?: "",
                                    right = if (undoSnapshot != null) "[Undo]" else "[X]",
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
                                AsciiHorizontalBorder()
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // 2. Dense, Compact Task List (6-8+ tasks visible without scrolling!)
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                                left = "No tasks in [$activeFilter]",
                                                right = "[+ Add Task]",
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
                                        UsableTaskCard(
                                            task = task,
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
                                            },
                                            onTriggerAlarmAndSnooze = {
                                                triggerFullAlarmAndNotification(
                                                    taskTitle = task.description,
                                                    rawSpec = task.reminderSpecs.firstOrNull() ?: "0m/a",
                                                    taskId = task.stableId
                                                )
                                            },
                                            onSyncCalendar = {
                                                if (gcalManager.hasCalendarPermissions()) {
                                                    val (synced, ok) = gcalManager.syncTaskToGoogleCalendar(task)
                                                    if (ok) {
                                                        persistAndSchedule(
                                                            tasks.map { if (it.lineNumber == task.lineNumber) synced else it }
                                                        )
                                                        statusBanner = "[#] Synced to GCal"
                                                    } else {
                                                        gcalManager.openInGoogleCalendarApp(task)
                                                    }
                                                } else {
                                                    gcalManager.openInGoogleCalendarApp(task)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "notes" -> {
                        val currentNote = notes.firstOrNull { it.filename == selectedNoteName } ?: notes.firstOrNull()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            AsciiTitledTopBorder(
                                badgeText = "/ NOTES",
                                badgeColor = palette.project,
                                rightBadgeText = "+ New",
                                rightBadgeColor = palette.reminder,
                                onRightBadgeClick = {
                                    val name = "note-${notes.size + 1}.md"
                                    val initialBody = "# $name\n\n"
                                    notes = storageRepo.saveNote(name, initialBody)
                                    selectedNoteName = name
                                    noteDraftName = name
                                    noteDraftText = initialBody
                                    noteEditMode = true
                                }
                            )
                            AsciiBoxOptionRow(
                                label = "File",
                                options = notes.map { it.filename to it.filename },
                                selectedValue = currentNote?.filename ?: "",
                                onSelect = { fname ->
                                    if (noteEditMode && noteDraftName.isNotBlank()) {
                                        notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                                    }
                                    selectedNoteName = fname
                                    noteEditMode = false
                                },
                                activeColor = palette.project,
                                fontSizeSp = fontScaleSp
                            )
                            if (currentNote != null) {
                                AsciiHorizontalBorder(corner = "|")
                                val noteActions = buildList {
                                    add(
                                        Triple(
                                            if (noteEditMode) "[✓ Save]" else "[/ Edit]",
                                            if (noteEditMode) palette.reminder else palette.project
                                        ) {
                                            if (noteEditMode) {
                                                notes = storageRepo.saveNote(currentNote.filename, noteDraftText)
                                                noteEditMode = false
                                            } else {
                                                noteDraftName = currentNote.filename
                                                noteDraftText = currentNote.rawContent
                                                noteEditMode = true
                                            }
                                        }
                                    )
                                    if (notes.size > 1) {
                                        add(
                                            Triple("[X Del]", palette.priorityA) {
                                                val updated = storageRepo.deleteNote(currentNote.filename)
                                                notes = updated
                                                selectedNoteName = updated.firstOrNull()?.filename ?: ""
                                                noteEditMode = false
                                            }
                                        )
                                    }
                                }
                                AsciiBoxActionRow(
                                    actions = noteActions,
                                    fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                                )
                                AsciiHorizontalBorder(corner = "|")

                                if (noteEditMode) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "| ",
                                            color = palette.fgMuted,
                                            fontSize = 12.sp,
                                            fontFamily = monoFont
                                        )
                                        BasicTextField(
                                            value = noteDraftText,
                                            onValueChange = {
                                                noteDraftText = it
                                                notes = storageRepo.saveNote(currentNote.filename, it)
                                            },
                                            textStyle = TextStyle(
                                                fontFamily = monoFont,
                                                color = palette.fg,
                                                fontSize = fontScaleSp.sp,
                                                lineHeight = (fontScaleSp + 4).sp
                                            ),
                                            cursorBrush = SolidColor(palette.project),
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .padding(vertical = 2.dp)
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
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .clickable {
                                                noteDraftName = currentNote.filename
                                                noteDraftText = currentNote.rawContent
                                                noteEditMode = true
                                            }
                                    ) {
                                        currentNote.bodyContent.lines().forEach { line ->
                                            AsciiBoxBodyRow(
                                                text = line.ifEmpty { " " },
                                                textColor = if (line.startsWith("#")) palette.project else palette.fg,
                                                fontSizeSp = fontScaleSp,
                                                bold = line.startsWith("#")
                                            )
                                        }
                                    }
                                }
                            }
                            AsciiHorizontalBorder()
                        }
                    }

                    "settings" -> {
                        SettingsScreen(
                            baseDir = storageRepo.baseDir,
                            storageFolderPath = settings.customSafUri ?: storageRepo.baseDir.absolutePath,
                            settings = settings,
                            onUpdateSettings = onUpdateSettings,
                            onSyncAllToGoogleCalendar = {
                                if (gcalManager.hasCalendarPermissions()) {
                                    val (syncedList, _) = gcalManager.syncAllDueTasks(tasks)
                                    persistAndSchedule(syncedList)
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
                            },
                            onArchiveCompletedTasks = {
                                val completed = tasks.filter { it.isCompleted }
                                if (completed.isNotEmpty()) {
                                    undoSnapshot = tasks
                                    doneLines = doneLines + completed.map { TodoParser.serializeLine(it) }
                                    storageRepo.saveDoneLines(doneLines)
                                    persistAndSchedule(tasks.filterNot { it.isCompleted })
                                }
                            },
                            onFireImmediateNotification = {
                                triggerFullAlarmAndNotification(
                                    taskTitle = "check chocotat +chocotat @factory",
                                    rawSpec = "0m/a",
                                    taskId = "test_alarm"
                                )
                            }
                        )
                    }
                }
            }

            // =================================================================
            // BOTTOM NAVIGATION TABS BOX (`[*] Tasks | [/] Notes | [#] Settings`)
            // =================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bgElevated)
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
                            "tasks" to "[*] Tasks ($openCount)",
                            "notes" to "[/] Notes (${notes.size})",
                            "settings" to "[#] Settings"
                        ).forEach { (tabKey, tabLabel) ->
                            val selected = currentTab == tabKey
                            Text(
                                text = if (selected) "*$tabLabel*" else tabLabel,
                                color = if (selected) palette.project else palette.fg,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = (fontScaleSp - 1).coerceAtLeast(13).sp,
                                fontFamily = appFont,
                                textDecoration = if (selected) TextDecoration.Underline else TextDecoration.None,
                                modifier = Modifier
                                    .clickable {
                                        currentTab = tabKey
                                        statusBanner = null
                                        editingTask = null
                                        showAddTaskPopup = false
                                        showTagsPopup = false
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
        // POPUP 1: PROJECTS (+) & CONTEXTS (@) FILTER MODAL (Shaded Background)
        // =====================================================================
        if (showTagsPopup) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.76f))
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
                                    activeFilter = it
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
                                    activeFilter = it
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
                                activeFilter = "open"
                                showTagsPopup = false
                            },
                            Triple("[X] Close", palette.fgMuted) {
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
        // POPUP 2: TASK EDIT & GRANULAR SNOOZE MODAL (Shaded Background)
        // =====================================================================
        if (editingTask != null) {
            val target = editingTask!!
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.76f))
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
                        badgeText = "/ TASK #${"%02d".format(target.lineNumber)}",
                        badgeColor = palette.project,
                        rightBadgeText = "X Close",
                        rightBadgeColor = palette.fgMuted,
                        onRightBadgeClick = { editingTask = null }
                    )
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
                    AsciiBoxOptionRow(
                        label = "[zZ] Unit",
                        options = SnoozeUnit.entries.map { it to it.asciiLabel.lowercase() },
                        selectedValue = editSnoozeUnit,
                        onSelect = { u ->
                            editSnoozeUnit = u
                            if (editSnoozeAmount !in u.quickValues) {
                                editSnoozeAmount = u.quickValues.first()
                            }
                        },
                        activeColor = palette.project,
                        fontSizeSp = fontScaleSp
                    )
                    AsciiBoxActionRow(
                        actions = listOf(
                            Triple("<-1>", palette.project) { editSnoozeAmount = (editSnoozeAmount - 1).coerceAtLeast(1) },
                            Triple("$editSnoozeAmount ${editSnoozeUnit.asciiLabel.lowercase()}", palette.fg) {},
                            Triple("<+1>", palette.project) { editSnoozeAmount = (editSnoozeAmount + 1).coerceAtMost(365) }
                        ),
                        fontSizeSp = fontScaleSp
                    )
                    AsciiBoxOptionRow(
                        label = "[+] Step",
                        options = editSnoozeUnit.quickValues.map { it to "+$it${editSnoozeUnit.code}" },
                        selectedValue = editSnoozeAmount,
                        onSelect = { editSnoozeAmount = it },
                        activeColor = palette.priorityB,
                        fontSizeSp = fontScaleSp
                    )
                    AsciiHorizontalBorder(corner = "|")
                    AsciiBoxActionRow(
                        actions = listOf(
                            Triple("[zZ +$editSnoozeAmount${editSnoozeUnit.code}]", palette.reminder) {
                                val zdt = ReminderScheduleCalculator.computeGranularSnoozeTarget(
                                    ZonedDateTime.now(),
                                    editSnoozeAmount.toDouble(),
                                    editSnoozeUnit
                                )
                                val dStr = zdt.toLocalDate().toString()
                                val tStr = "%02d:%02d".format(zdt.hour, zdt.minute)
                                var parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                                parsed = TodoParser.withUpdatedTag(parsed, "due", dStr)
                                parsed = TodoParser.withUpdatedTag(parsed, "duetime", tStr)
                                parsed = TodoParser.withReplacedMultiTag(parsed, "rem", listOf("0m/a"))
                                editingTaskDraft = TodoParser.serializeLine(parsed)

                                val delayMs = (zdt.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1_000L)
                                undoSnapshot = tasks
                                val updatedTasks = tasks.map { if (it.lineNumber == target.lineNumber) parsed else it }
                                tasks = updatedTasks
                                storageRepo.saveTasks(updatedTasks)
                                scheduleExactDelayAlarm(parsed, delayMs, "0m/a")
                                editingTask = null
                                statusBanner = "[zZ] Snoozed +$editSnoozeAmount${editSnoozeUnit.code}"
                            },
                            Triple("[✓ Save]", palette.project) {
                                undoSnapshot = tasks
                                val reParsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber).copy(isDirty = true)
                                persistAndSchedule(
                                    tasks.map { if (it.lineNumber == target.lineNumber) reParsed else it }
                                )
                                editingTask = null
                                statusBanner = "[✓] Saved task"
                            },
                            Triple("[! Alarm]", palette.priorityB) {
                                val parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                                editingTask = null
                                triggerFullAlarmAndNotification(
                                    taskTitle = parsed.description,
                                    rawSpec = parsed.reminderSpecs.firstOrNull() ?: "0m/a",
                                    taskId = parsed.stableId
                                )
                            },
                            Triple("[X Del]", palette.priorityA) {
                                undoSnapshot = tasks
                                persistAndSchedule(tasks.filterNot { it.lineNumber == target.lineNumber })
                                editingTask = null
                                statusBanner = "[X] Deleted task"
                            }
                        ),
                        fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                    )
                    AsciiHorizontalBorder()
                }
            }
        }

        // =====================================================================
        // POPUP 3: ADD TASK & GRANULAR REMINDER MODAL (Shaded Background)
        // =====================================================================
        if (showAddTaskPopup) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.76f))
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
                        badgeText = "+ ADD TASK / REMINDER",
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
                                modifier = Modifier.fillMaxWidth()
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
                            label = "[*]",
                            options = parsePreview.recognizedSpans.map { it.id to "${it.resolvedToken}[x]" },
                            selectedValue = "",
                            onSelect = { spanId -> disabledSpans = disabledSpans + spanId },
                            activeColor = palette.reminder,
                            fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
                        )
                    }

                    AsciiHorizontalBorder(corner = "|")
                    AsciiBoxOptionRow(
                        label = "[zZ] Unit",
                        options = SnoozeUnit.entries.map { it to it.asciiLabel.lowercase() },
                        selectedValue = pickerUnit,
                        onSelect = { u ->
                            pickerUnit = u
                            pickerAmount = u.quickValues.first()
                        },
                        activeColor = palette.project,
                        fontSizeSp = fontScaleSp
                    )
                    AsciiBoxOptionRow(
                        label = "[+] Time",
                        options = pickerUnit.quickValues.map { qv -> "in $qv${pickerUnit.code}" to "+$qv${pickerUnit.code}" },
                        selectedValue = "",
                        onSelect = { chip ->
                            smartInput = if (smartInput.isBlank()) chip else "${smartInput.trim()} $chip"
                        },
                        activeColor = palette.priorityB,
                        fontSizeSp = fontScaleSp
                    )
                    AsciiHorizontalBorder(corner = "|")
                    AsciiBoxActionRow(
                        actions = listOf(
                            Triple("[X Cancel]", palette.fgMuted) { showAddTaskPopup = false },
                            Triple("[+ Add Task >]", palette.reminder) { submitSmartAdd() }
                        ),
                        fontSizeSp = fontScaleSp
                    )
                    AsciiHorizontalBorder()
                }
            }
        }
    }
}

/**
 * Compact 4-Line Task Box matching the user's hand-drawn sketch:
 * Line 1: `+--[2026-11-02 09:00]--------------------------------+`
 * Line 2: `| [ ] check chocotat                                 |`
 * Line 3: `| [✓]           [zZ]           [!]          [#]      |`
 * Line 4: `+----------------------------------------------------+`
 */
@Composable
private fun UsableTaskCard(
    task: TodoTask,
    fontSizeSp: Int = 15,
    fontScaleSp: Int = fontSizeSp,
    onToggleDone: () -> Unit,
    onTapTask: () -> Unit,
    onTriggerAlarmAndSnooze: () -> Unit,
    onSyncCalendar: () -> Unit
) {
    val palette = LocalTickPalette.current
    val isSyncedToGcal = task.getTagValue("gcal") != null

    val topDateAndTime = when {
        task.isCompleted -> "DONE ${task.completionDate ?: ""}".trim()
        task.dueDate != null && task.dueTime != null -> "${task.dueDate} ${task.dueTime}"
        task.dueDate != null -> "${task.dueDate}"
        else -> "OPEN"
    }

    val rightTagsSummary = buildString {
        task.projects.firstOrNull()?.let { append("+$it") }
        task.contexts.firstOrNull()?.let {
            if (isNotEmpty()) append(" ")
            append("@$it")
        }
    }.ifEmpty { null }

    val titleRowText = buildString {
        append(if (task.isCompleted) "[x] " else "[ ] ")
        if (task.priority != null) append("(${task.priority}) ")
        append(task.description.ifEmpty { task.rawLine })
    }

    // The 4 ASCII signs for [Done] [Snooze] [Alarm] [Calendar] as drawn in the sketch
    val cardActions = listOf(
        Triple(
            if (task.isCompleted) "[↺]" else "[✓]",
            palette.reminder,
            onToggleDone
        ),
        Triple(
            "[zZ]",
            palette.project,
            onTapTask
        ),
        Triple(
            "[!]",
            palette.priorityB,
            onTriggerAlarmAndSnooze
        ),
        Triple(
            if (isSyncedToGcal) "[#✓]" else "[#]",
            if (isSyncedToGcal) palette.reminder else palette.contextTag,
            onSyncCalendar
        )
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Line 1: +--[Date and Time]-----------------------+
        AsciiTitledTopBorder(
            badgeText = topDateAndTime,
            badgeColor = if (task.isCompleted) palette.reminder else palette.priorityB,
            rightBadgeText = rightTagsSummary,
            rightBadgeColor = palette.project,
            onRightBadgeClick = { onTapTask() }
        )
        // Line 2: | [ ] Check Chocotat                     |
        AsciiBoxBodyRow(
            text = titleRowText,
            textColor = if (task.isCompleted) palette.fgMuted else palette.fg,
            fontSizeSp = fontScaleSp,
            bold = true,
            strikethrough = task.isCompleted,
            onClick = { onTapTask() }
        )
        // Line 3: | [✓]        [zZ]        [!]        [#] |
        AsciiBoxActionRow(
            actions = cardActions,
            fontSizeSp = (fontScaleSp - 1).coerceAtLeast(12)
        )
        // Line 4: +----------------------------------------+
        AsciiHorizontalBorder()
    }
}
