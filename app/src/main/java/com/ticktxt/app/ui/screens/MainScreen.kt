package com.ticktxt.app.ui.screens

import android.Manifest
import android.app.Activity
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ActiveAlarmPayload(
    val reminderId: String,
    val taskId: String,
    val taskTitle: String,
    val rawSpec: String,
    val isAlarmMuted: Boolean = true
)

/**
 * Main screen following a strict, uniform ASCII UX Design System:
 * - Zero CSS/Compose `.border()` rectangles anywhere (even the floating `[+ Add]` button uses pure `+---+` / `|   |` ASCII borders)
 * - Uniform canonical action tokens everywhere (`[+ Add]`, `[✓ Done]`, `[zZ Snooze]`, `[✓ Save]`, `[# GCal]`, `[X Delete]`, `[X Close]`)
 * - Screenshot-1 style "EDIT REMINDER" popup + 3-Column ASCII Wheel Snooze Picker (`AsciiSnoozeWheelPopup`) with touch-drag & Offset/Calendar modes
 * - Smart Priority `(A)->(B)->(C)` & Due-Date sorting + 1-tap Priority selector `(A)/(B)/(C)` in popups
 * - 3-line task cards with live auto-refreshing relative countdown badges (`15 m.`, `2 d.`, `-5 m.`), Swipe Right = Done, Swipe Left = Delete
 * - HorizontalPager tab swiping + multi-step Back gesture navigation.
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

    // Pager for swiping between tabs (0 = Tasks, 1 = Notes, 2 = Settings)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var activeFilter by remember { mutableStateOf("open") }

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
    var editTaskAlertType by remember { mutableStateOf("a") } // "a" = Alarm, "n" = Notification
    var showEditSnoozeWheel by remember { mutableStateOf(false) }

    var showAddTaskPopup by remember { mutableStateOf(false) }
    var smartInput by remember { mutableStateOf("") }
    var addTaskAlertType by remember { mutableStateOf("a") } // "a" = Alarm, "n" = Notification
    var addTaskPriority by remember { mutableStateOf<Char?>(null) }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var showAddSnoozeWheel by remember { mutableStateOf(false) }

    var showTagsPopup by remember { mutableStateOf(false) }

    var activeAlarmOverlay by remember { mutableStateOf<ActiveAlarmPayload?>(null) }

    var undoSnapshot by remember { mutableStateOf<List<TodoTask>?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    // Live-ticking clock so relative countdown badges (`15 m.`, `2 h.`, `-5 m.`) stay accurate automatically
    var currentNow by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            currentNow = LocalDateTime.now()
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
            val (syncedList, count) = gcalManager.syncAllDueTasks(tasks)
            tasks = syncedList
            storageRepo.saveTasks(syncedList)
            statusBanner = "[# GCal] Synced $count task(s)"
        } else {
            statusBanner = "[!] Calendar permission needed"
        }
    }

    fun triggerFullAlarmAndNotification(
        taskTitle: String,
        rawSpec: String = "0m/a",
        taskId: String = "immediate",
        typeCode: String = if (rawSpec.endsWith("/n")) "n" else "a"
    ) {
        val mutedMode = if (typeCode == "n") true else settings.alarmMutedByDefault
        scheduler.fireImmediateNotification(
            taskTitle = taskTitle,
            rawSpec = rawSpec,
            typeCode = typeCode,
            taskId = taskId
        )
        AlarmAudioController.applyAudioMode(context, isAlarmMuted = mutedMode)
        activeAlarmOverlay = ActiveAlarmPayload(
            reminderId = "$taskId|$rawSpec",
            taskId = taskId,
            taskTitle = taskTitle,
            rawSpec = rawSpec,
            isAlarmMuted = mutedMode
        )
    }

    fun scheduleExactDelayAlarm(task: TodoTask, delayMs: Long, rawSpec: String = "0m/a") {
        val safeDelay = delayMs.coerceAtLeast(1_000L)
        val targetEpochMs = System.currentTimeMillis() + safeDelay
        val typeCode = if (rawSpec.endsWith("/n")) "n" else "a"
        scheduler.scheduleRaw(
            reminderId = "${task.stableId}|$rawSpec",
            taskStableId = task.stableId,
            rawSpec = rawSpec,
            taskDescription = task.description,
            typeCode = typeCode,
            triggerEpochMs = targetEpochMs
        )
        if (safeDelay <= 7_200_000L) {
            coroutineScope.launch {
                delay(safeDelay)
                triggerFullAlarmAndNotification(
                    taskTitle = task.description,
                    rawSpec = rawSpec,
                    taskId = task.stableId,
                    typeCode = typeCode
                )
            }
        }
    }

    fun persistAndSchedule(
        updatedTasks: List<TodoTask>,
        exactDelayMillisForLastTask: Long? = null,
        lastTaskRawSpec: String = "0m/a",
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
                scheduleExactDelayAlarm(t, exactDelayMillisForLastTask, lastTaskRawSpec)
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
                currentNow = LocalDateTime.now()
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

    // Back gesture state machine:
    // 1. Closes any open wheel / popup / editor
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
            showTagsPopup -> showTagsPopup = false
            noteEditMode -> {
                if (noteDraftName.isNotBlank()) {
                    notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                }
                noteEditMode = false
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
            onUpdateSettings(settings.copy(customSafUri = uri.toString()))
            storageRepo.saveTasks(tasks)
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
            notes = storageRepo.saveNote(fn, "# ${res.strippedTitle}\n")
            selectedNoteName = fn
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
            val chosenSpec = "0m/$addTaskAlertType"
            if (newTask.dueDate != null || newTask.reminderSpecs.isNotEmpty() || res.exactOffsetMillis != null) {
                newTask = TodoParser.withReplacedMultiTag(newTask, "rem", listOf(chosenSpec))
            }
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
            val modeLabel = if (addTaskAlertType == "a") "ALM" else "NTF"
            statusBanner = "[+ $modeLabel] ${newTask.description}"
        }
    }

    val dynamicProjects = remember(tasks) { tasks.flatMap { it.projects }.distinct().map { "+$it" } }
    val dynamicContexts = remember(tasks) { tasks.flatMap { it.contexts }.distinct().map { "@$it" } }

    // Smart sorting: Incomplete first -> Priority (A)->(B)->(C) -> Earliest dueDate & dueTime -> lineNumber
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
        }.sortedWith(
            compareBy<TodoTask> { it.isCompleted }
                .thenBy { it.priority ?: '[' }
                .thenBy { it.dueDate ?: LocalDate.MAX }
                .thenBy { it.dueTime ?: LocalTime.MAX }
                .thenBy { it.lineNumber }
        )
    }

    // =========================================================================
    // FULL-SCREEN 3-OPTION NOTIFICATION / ALARM OVERLAY
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
                    val typeCode = if (alarm.rawSpec.endsWith("/n")) "n" else "a"
                    scheduler.scheduleRaw(
                        reminderId = alarm.reminderId,
                        taskStableId = alarm.taskId,
                        rawSpec = alarm.rawSpec,
                        taskDescription = alarm.taskTitle,
                        typeCode = typeCode,
                        triggerEpochMs = targetZdt.toInstant().toEpochMilli()
                    )
                    if (delayMs <= 7_200_000L) {
                        coroutineScope.launch {
                            delay(delayMs)
                            triggerFullAlarmAndNotification(alarm.taskTitle, alarm.rawSpec, alarm.taskId, typeCode)
                        }
                    }
                }
                val fmtTime = targetZdt.format(DateTimeFormatter.ofPattern("HH:mm"))
                statusBanner = "[zZ Snooze] Until $fmtTime"
                activeAlarmOverlay = null
            },
            onDismiss = {
                AlarmAudioController.stopAll()
                statusBanner = "[X Dismiss] Closed alert"
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
                                // 1. Single-Line Top Filter Bar: Open | All | Due | Done | [+@ Tags]
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
                                                    .clickable { updateFilterWithHistory(fKey) }
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
                                    }
                                    Text(
                                        text = " |",
                                        color = palette.fgMuted,
                                        fontSize = 12.sp,
                                        fontFamily = monoFont
                                    )
                                }
                                AsciiHorizontalBorder()

                                // Status / Undo Row
                                if (statusBanner != null) {
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
                                    AsciiHorizontalBorder()
                                }

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
                                                    left = "No tasks in [$activeFilter]",
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
                                                            val curRem = task.reminderSpecs.firstOrNull() ?: "0m/a"
                                                            editTaskAlertType = if (curRem.endsWith("/n")) "n" else "a"
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 3. Uniform Pure-ASCII `[+ Add]` Box at Bottom-Right (No CSS green border!)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 6.dp)
                                    .background(palette.bgElevated)
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
                        // TAB 1: NOTES (Uses the exact same `[+ Add]`, `[✓ Save]`, `[X Delete]` vocabulary!)
                        val currentNote = notes.firstOrNull { it.filename == selectedNoteName } ?: notes.firstOrNull()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            AsciiTitledTopBorder(
                                badgeText = "/ NOTES",
                                badgeColor = palette.project,
                                rightBadgeText = "+ Add",
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
                                    add(
                                        Triple("[+ Add]", palette.reminder) {
                                            val name = "note-${notes.size + 1}.md"
                                            val initialBody = "# $name\n\n"
                                            notes = storageRepo.saveNote(name, initialBody)
                                            selectedNoteName = name
                                            noteDraftName = name
                                            noteDraftText = initialBody
                                            noteEditMode = true
                                        }
                                    )
                                    if (notes.size > 1) {
                                        add(
                                            Triple("[X Delete]", palette.priorityA) {
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

                    else -> {
                        // TAB 2: SETTINGS
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
                            0 to "[*] Tasks ($openCount)",
                            1 to "[/] Notes (${notes.size})",
                            2 to "[#] Settings"
                        ).forEach { (tabIdx, tabLabel) ->
                            val selected = pagerState.currentPage == tabIdx
                            Text(
                                text = if (selected) "*$tabLabel*" else tabLabel,
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
                        val specStr = "0m/$editTaskAlertType"
                        var updated = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                        updated = TodoParser.withUpdatedTag(updated, "due", dStr)
                        updated = TodoParser.withUpdatedTag(updated, "duetime", tStr)
                        updated = TodoParser.withReplacedMultiTag(updated, "rem", listOf(specStr))
                        editingTaskDraft = TodoParser.serializeLine(updated)

                        undoSnapshot = tasks
                        val updatedTasks = tasks.map { if (it.lineNumber == target.lineNumber) updated else it }
                        tasks = updatedTasks
                        storageRepo.saveTasks(updatedTasks)
                        scheduleExactDelayAlarm(updated, delayMs, specStr)
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
                        // Top Header matching Screenshot 1 ("EDIT REMINDER") + 1-tap +10m Quick Snooze
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

                        // Row 4: Show as Notification vs Alarm (from Screenshot 1)
                        AsciiBoxOptionRow(
                            label = "[!] Show as",
                            options = listOf(
                                "n" to "Notification",
                                "a" to "Alarm"
                            ),
                            selectedValue = editTaskAlertType,
                            onSelect = { newType ->
                                editTaskAlertType = newType
                                var parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                                val updatedRems = if (parsed.reminderSpecs.isEmpty()) {
                                    listOf("0m/$newType")
                                } else {
                                    parsed.reminderSpecs.map { spec ->
                                        val base = spec.substringBefore("/")
                                        "$base/$newType"
                                    }
                                }
                                parsed = TodoParser.withReplacedMultiTag(parsed, "rem", updatedRems)
                                editingTaskDraft = TodoParser.serializeLine(parsed)
                            },
                            activeColor = if (editTaskAlertType == "a") palette.priorityB else palette.reminder,
                            fontSizeSp = fontScaleSp
                        )
                        AsciiHorizontalBorder(corner = "|")

                        // Row 5: Uniform Actions Bar: [zZ Snooze]  [# GCal]  [✓ Save]  [X Delete]
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
                            .replace(Regex("\\bdue:\\S+"), "")
                            .replace(Regex("\\bduetime:\\S+"), "")
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
                        // Date & Time Wheel Trigger Row (matching Screenshot 1)
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

                        // Show as Notification vs Alarm (from Screenshot 1)
                        AsciiBoxOptionRow(
                            label = "[!] Show as",
                            options = listOf(
                                "n" to "Notification",
                                "a" to "Alarm"
                            ),
                            selectedValue = addTaskAlertType,
                            onSelect = { addTaskAlertType = it },
                            activeColor = if (addTaskAlertType == "a") palette.priorityB else palette.reminder,
                            fontSizeSp = fontScaleSp
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
 * 3-Line Task Card with Live Relative Countdown Badge (like `32 d.` / `15 m.` / `-5 m.` in Screenshot 1):
 * Line 1: `+--[2026-11-02 09:00 • ALM]----------[+chocotat • 15 m.]--+`
 * Line 2: `| [ ] (A) check chocotat                                   |`
 * Line 3: `+----------------------------------------------------------+`
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

    val alertKind = task.reminderSpecs.firstOrNull()?.let {
        if (it.endsWith("/n")) "NTF" else "ALM"
    }

    val topDateAndTime = when {
        task.isCompleted -> "DONE ${task.completionDate ?: ""}".trim()
        task.dueDate != null && task.dueTime != null -> {
            val suffix = alertKind?.let { " • $it" } ?: ""
            "${task.dueDate} ${task.dueTime}$suffix"
        }
        task.dueDate != null -> {
            val suffix = alertKind?.let { " • $it" } ?: ""
            "${task.dueDate}$suffix"
        }
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
