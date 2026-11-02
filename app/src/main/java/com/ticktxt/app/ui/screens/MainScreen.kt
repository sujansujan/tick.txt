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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    val rawSpec: String
)

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

    var currentTab by remember { mutableStateOf("tasks") } // "tasks", "notes", "settings"
    var activeFilter by remember { mutableStateOf("all") }
    var editingTask by remember { mutableStateOf<TodoTask?>(null) }
    var editingTaskDraft by remember { mutableStateOf("") }
    var editSnoozeUnit by remember { mutableStateOf(SnoozeUnit.MINUTES) }
    var editSnoozeAmount by remember { mutableStateOf(5) }

    var activeAlarmOverlay by remember { mutableStateOf<ActiveAlarmPayload?>(null) }

    var smartInput by remember { mutableStateOf("") }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var showTimePickerSheet by remember { mutableStateOf(false) }
    var pickerUnit by remember { mutableStateOf(SnoozeUnit.MINUTES) }
    var pickerAmount by remember { mutableStateOf(5) }

    var undoSnapshot by remember { mutableStateOf<List<TodoTask>?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val scheduler = remember { ReminderScheduler(context) }
    val gcalManager = remember { GoogleCalendarSyncManager(context) }

    // Calendar permission launcher for 1-tap Google Calendar sync
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            val (syncedList, count) = gcalManager.syncAllDueTasks(tasks)
            tasks = syncedList
            storageRepo.saveTasks(syncedList)
            statusBanner = "[✓ GCAL] Synced $count task(s) to Google Calendar"
        } else {
            statusBanner = "[! GCAL] Permission needed for background sync (using Calendar intent)"
        }
    }

    fun triggerFullAlarmAndNotification(taskTitle: String, rawSpec: String = "0m/a", taskId: String = "immediate") {
        scheduler.fireImmediateNotification(
            taskTitle = taskTitle,
            rawSpec = rawSpec,
            typeCode = "a",
            taskId = taskId
        )
        activeAlarmOverlay = ActiveAlarmPayload(
            reminderId = "$taskId|$rawSpec",
            taskId = taskId,
            taskTitle = taskTitle,
            rawSpec = rawSpec
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

    // Save state automatically when app pauses/stops
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

    // Listen for Mark Done / Snooze / Alarm broadcasts from Android Notification & AlarmBroadcastReceiver
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ReminderScheduler.ACTION_TASKS_UPDATED -> {
                        tasks = storageRepo.loadTasks()
                        val msg = intent.getStringExtra("extra_status_msg") ?: "[✓] Task state updated"
                        statusBanner = msg
                    }
                    ReminderScheduler.ACTION_ALARM_FIRED_UI -> {
                        val remId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: "alarm"
                        val tId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: "task"
                        val spec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC) ?: "0m/a"
                        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE) ?: "scheduled task"
                        activeAlarmOverlay = ActiveAlarmPayload(remId, tId, title, spec)
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

    // Back gesture support
    BackHandler(
        enabled = activeAlarmOverlay != null || editingTask != null || showTimePickerSheet || noteEditMode || currentTab != "tasks"
    ) {
        when {
            activeAlarmOverlay != null -> activeAlarmOverlay = null
            editingTask != null -> editingTask = null
            showTimePickerSheet -> showTimePickerSheet = false
            noteEditMode -> {
                if (noteDraftName.isNotBlank()) {
                    notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                }
                noteEditMode = false
            }
            currentTab != "tasks" -> currentTab = "tasks"
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
            statusBanner = "[📁] Storage directory updated"
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
            showTimePickerSheet = false
            focusManager.clearFocus()
            val remInfo = when {
                res.exactOffsetMillis != null && res.exactOffsetMillis < 60_000L ->
                    " [⏰ ALARM IN ${res.exactOffsetMillis / 1000L}s]"
                newTask.dueTime != null ->
                    " [⏰ ALARM @ ${newTask.dueTime}]"
                newTask.dueDate != null ->
                    " [📅 DUE ${newTask.dueDate}]"
                else -> ""
            }
            statusBanner = "[+ TASK] \"${newTask.description}\"$remInfo"
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

    // =========================================================================
    // FULL-SCREEN ASCII ALARM OVERLAY (When alarm fires or user taps [🔔 ALARM])
    // =========================================================================
    if (activeAlarmOverlay != null) {
        val alarm = activeAlarmOverlay!!
        AsciiFullScreenAlarmSurface(
            taskTitle = alarm.taskTitle,
            rawSpec = alarm.rawSpec,
            fontSizeSp = fontScaleSp,
            onSnoozeWithPreset = { presetSpec ->
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
                statusBanner = "[💤 SNOOZED +$presetSpec] Next alarm -> $fmtTime"
                activeAlarmOverlay = null
            },
            onDismiss = {
                statusBanner = "[✕ DISMISSED] Alarm silenced for \"${alarm.taskTitle}\""
                activeAlarmOverlay = null
            },
            onMarkDone = {
                undoSnapshot = tasks
                val nextList = tasks.map { t ->
                    if (!t.isCompleted && (t.stableId == alarm.taskId || t.description.equals(alarm.taskTitle, ignoreCase = true))) {
                        TodoParser.markCompleted(t, LocalDate.now()).first
                    } else {
                        t
                    }
                }
                persistAndSchedule(nextList)
                statusBanner = "[x DONE] Completed \"${alarm.taskTitle}\""
                activeAlarmOverlay = null
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // =====================================================================
        // SPACIOUS ASCII TOP NAVIGATION BAR (48dp+ touch targets)
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .border(1.dp, palette.border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val openCount = tasks.count { !it.isBlankOrComment && !it.isCompleted }
                listOf(
                    "tasks" to "TASKS:$openCount",
                    "notes" to "NOTES:${notes.size}",
                    "settings" to "CFG/SYS"
                ).forEach { (tabKey, tabLabel) ->
                    val selected = currentTab == tabKey
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(if (selected) palette.project else palette.bg)
                            .border(1.dp, if (selected) palette.project else palette.border)
                            .clickable { currentTab = tabKey }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selected) "╔[$tabLabel]╗" else "[$tabLabel]",
                            color = if (selected) palette.bg else palette.fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        // =====================================================================
        // ASCII FEEDBACK / UNDO BANNER (Spacious, easy to tap)
        // =====================================================================
        if (statusBanner != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(palette.bgElevated)
                    .border(1.dp, palette.reminder)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ">> ${statusBanner ?: ""}",
                    color = palette.fg,
                    fontSize = 13.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (undoSnapshot != null) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .background(palette.project)
                                .clickable {
                                    persistAndSchedule(undoSnapshot!!)
                                    undoSnapshot = null
                                    statusBanner = null
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "[↶ UNDO]",
                                color = palette.bg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = fontFamily
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .border(1.dp, palette.border)
                            .clickable { statusBanner = null }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[✕]",
                            color = palette.fgMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        // =====================================================================
        // ASCII TASK EDIT, GRANULAR SNOOZE & FULL-SCREEN ALARM SHEET
        // =====================================================================
        if (editingTask != null) {
            val target = editingTask!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(palette.bgElevated)
                    .border(2.dp, palette.project)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "╔═[ ✎ TASK & GRANULAR SNOOZE ]═╗",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                    Box(
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .border(1.dp, palette.border)
                            .clickable { editingTask = null }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "[✕ ESC]", color = palette.fgMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = fontFamily)
                    }
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
                        .padding(14.dp)
                )

                // Granular Snooze / Alarm Selector (SEC | MIN | HR | DAY | WK | MO)
                Text(
                    text = "├─[ SNOOZE / ALARM UNIT: SEC | MIN | HR | DAY | WK | MO ]─┤",
                    color = palette.fgMuted,
                    fontSize = 11.sp,
                    fontFamily = fontFamily
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SnoozeUnit.entries.forEach { u ->
                        val sel = editSnoozeUnit == u
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .background(if (sel) palette.project else palette.bg)
                                .border(1.dp, if (sel) palette.project else palette.border)
                                .clickable {
                                    editSnoozeUnit = u
                                    if (editSnoozeAmount !in u.quickValues) {
                                        editSnoozeAmount = u.quickValues.first()
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (sel) "[${u.asciiLabel}]" else u.asciiLabel,
                                color = if (sel) palette.bg else palette.fg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = fontFamily
                            )
                        }
                    }
                }

                // Quick value chips + stepper for selected unit
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .background(palette.bg)
                            .border(1.dp, palette.priorityA)
                            .clickable { editSnoozeAmount = (editSnoozeAmount - 1).coerceAtLeast(1) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "[-1]", color = palette.priorityA, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = fontFamily)
                    }

                    editSnoozeUnit.quickValues.forEach { qv ->
                        val activeQv = editSnoozeAmount == qv
                        Box(
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .background(if (activeQv) palette.priorityB else palette.bg)
                                .border(1.dp, if (activeQv) palette.priorityB else palette.border)
                                .clickable { editSnoozeAmount = qv }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "[+$qv${editSnoozeUnit.code}]",
                                color = if (activeQv) palette.bg else palette.fg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = fontFamily
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .background(palette.bg)
                            .border(1.dp, palette.reminder)
                            .clickable { editSnoozeAmount = (editSnoozeAmount + 1).coerceAtMost(365) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "[+1]", color = palette.reminder, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = fontFamily)
                    }
                }

                // Apply Granular Snooze / Alarm Button + Fire Immediate Full-Screen Alarm Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(palette.bg)
                            .border(1.dp, palette.reminder)
                            .clickable {
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
                                statusBanner = "[⏰ ALARM SET] +$editSnoozeAmount ${editSnoozeUnit.asciiLabel} (${zdt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))})"
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[⏰ SNOOZE +$editSnoozeAmount ${editSnoozeUnit.asciiLabel}]",
                            color = palette.reminder,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(palette.bg)
                            .border(1.dp, palette.priorityA)
                            .clickable {
                                val parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                                editingTask = null
                                triggerFullAlarmAndNotification(
                                    taskTitle = parsed.description,
                                    rawSpec = parsed.reminderSpecs.firstOrNull() ?: "0m/a",
                                    taskId = parsed.stableId
                                )
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[🔔 FULL ALARM NOW]",
                            color = palette.priorityA,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }

                // Google Calendar Sync Row
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .background(palette.bg)
                        .border(1.dp, palette.contextTag)
                        .clickable {
                            val parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                            if (gcalManager.hasCalendarPermissions()) {
                                val (syncedTask, ok) = gcalManager.syncTaskToGoogleCalendar(parsed)
                                if (ok) {
                                    persistAndSchedule(
                                        tasks.map { if (it.lineNumber == target.lineNumber) syncedTask else it }
                                    )
                                    editingTask = null
                                    statusBanner = "[✓ GCAL] Synced to Google Calendar"
                                } else {
                                    gcalManager.openInGoogleCalendarApp(parsed)
                                }
                            } else {
                                gcalManager.openInGoogleCalendarApp(parsed)
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[ ⇄ SYNC TASK TO GOOGLE CALENDAR ]",
                        color = palette.contextTag,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = fontFamily
                    )
                }

                // Delete / Save Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(palette.bg)
                            .border(1.dp, palette.priorityA)
                            .clickable {
                                undoSnapshot = tasks
                                persistAndSchedule(tasks.filterNot { it.lineNumber == target.lineNumber })
                                editingTask = null
                                statusBanner = "[✕ DELETED] Task removed"
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[ ✕ DELETE ]",
                            color = palette.priorityA,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(palette.reminder)
                            .clickable {
                                undoSnapshot = tasks
                                val reParsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber).copy(isDirty = true)
                                persistAndSchedule(
                                    tasks.map { if (it.lineNumber == target.lineNumber) reParsed else it }
                                )
                                editingTask = null
                                statusBanner = "[✓ SAVED] Task updated in todo.txt"
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[ ✓ SAVE LINE ]",
                            color = palette.bg,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        // =====================================================================
        // MAIN CONTENT AREA
        // =====================================================================
        when (currentTab) {
            "tasks" -> {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Spacious ASCII Filter Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val filters = listOf(
                            "all" to "ALL",
                            "open" to "OPEN",
                            "due" to "SCHEDULED"
                        ) + dynamicProjects.map { it to it } + dynamicContexts.map { it to it }

                        filters.forEach { (fKey, fLabel) ->
                            val active = activeFilter == fKey
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 42.dp)
                                    .background(if (active) palette.project else palette.bgElevated)
                                    .border(1.dp, if (active) palette.project else palette.border)
                                    .clickable { activeFilter = fKey }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (active) "[[ * $fLabel * ]]" else "[ $fLabel ]",
                                    color = if (active) palette.bg else palette.fg,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }
                    }

                    // Spacious ASCII Task List
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (filteredTasks.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 28.dp)
                                        .background(palette.bgElevated)
                                        .border(1.dp, palette.border)
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "╔══════════════════════════════════╗",
                                        color = palette.project,
                                        fontSize = 12.sp,
                                        fontFamily = fontFamily
                                    )
                                    Text(
                                        text = "║   (o_o)  0 TASKS IN BUFFER       ║",
                                        color = palette.fg,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = fontScaleSp.sp,
                                        fontFamily = fontFamily
                                    )
                                    Text(
                                        text = "║   Type below to schedule alarm   ║",
                                        color = palette.fgMuted,
                                        fontSize = 13.sp,
                                        fontFamily = fontFamily
                                    )
                                    Text(
                                        text = "╚══════════════════════════════════╝",
                                        color = palette.project,
                                        fontSize = 12.sp,
                                        fontFamily = fontFamily
                                    )
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
                                                statusBanner = "[✓ GCAL] Synced \"${task.description}\""
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

                    // Expandable ASCII Granular Time & Snooze Picker Sheet (when tapping [⏰ +TIME])
                    if (showTimePickerSheet) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(palette.bgElevated)
                                .border(2.dp, palette.project)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "╔═[ ⏰ GRANULAR OFFSET: SEC/MIN/HR/DAY/WK/MO ]═╗",
                                    color = palette.project,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    fontFamily = fontFamily
                                )
                                Box(
                                    modifier = Modifier
                                        .clickable { showTimePickerSheet = false }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(text = "[✕]", color = palette.reminder, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = fontFamily)
                                }
                            }

                            // Unit selector row: SEC | MIN | HR | DAY | WK | MO
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SnoozeUnit.entries.forEach { u ->
                                    val sel = pickerUnit == u
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 42.dp)
                                            .background(if (sel) palette.project else palette.bg)
                                            .border(1.dp, if (sel) palette.project else palette.border)
                                            .clickable {
                                                pickerUnit = u
                                                pickerAmount = u.quickValues.first()
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (sel) "[${u.asciiLabel}]" else u.asciiLabel,
                                            color = if (sel) palette.bg else palette.fg,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            fontFamily = fontFamily
                                        )
                                    }
                                }
                            }

                            // Quick chips for chosen unit
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pickerUnit.quickValues.forEach { qv ->
                                    val chip = "in $qv${pickerUnit.code}"
                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = 42.dp)
                                            .background(palette.bg)
                                            .border(1.dp, palette.reminder)
                                            .clickable {
                                                smartInput = if (smartInput.isBlank()) chip else "${smartInput.trim()} $chip"
                                                showTimePickerSheet = false
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "[$chip]",
                                            color = palette.reminder,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            fontFamily = fontFamily
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Live ASCII Smart-Add Preview Chips (Only shown while typing)
                    if (parsePreview != null && parsePreview.recognizedSpans.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            parsePreview.recognizedSpans.forEach { span ->
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 38.dp)
                                        .background(palette.bgElevated)
                                        .border(1.dp, palette.reminder)
                                        .clickable { disabledSpans = disabledSpans + span.id }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "[ ${span.resolvedToken} ✕ ]",
                                        color = palette.reminder,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        fontFamily = fontFamily
                                    )
                                }
                            }
                        }
                    }

                    // Spacious ASCII Bottom Capture Bar (56dp+ height, large Add & Time buttons)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.bgElevated)
                            .border(1.dp, palette.border)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .background(palette.bg)
                                .border(1.dp, if (showTimePickerSheet) palette.project else palette.border)
                                .clickable { showTimePickerSheet = !showTimePickerSheet }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "[⏰+TIME]",
                                color = palette.priorityB,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = fontFamily
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .background(palette.bg)
                                .border(1.dp, palette.border)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (smartInput.isEmpty()) {
                                Text(
                                    text = ">_ task (e.g. check chocotat at factory in 10s)...",
                                    color = palette.fgMuted,
                                    fontSize = 13.sp,
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

                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .background(palette.reminder)
                                .border(1.dp, palette.fg)
                                .clickable { submitSmartAdd() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "[+ADD ↵]",
                                color = palette.bg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = fontFamily
                            )
                        }
                    }
                }
            }

            "notes" -> {
                val currentNote = notes.firstOrNull { it.filename == selectedNoteName } ?: notes.firstOrNull()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Spacious ASCII Note Selector Pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        notes.forEach { n ->
                            val sel = n.filename == currentNote?.filename
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 46.dp)
                                    .background(if (sel) palette.project else palette.bgElevated)
                                    .border(1.dp, if (sel) palette.project else palette.border)
                                    .clickable {
                                        if (noteEditMode && noteDraftName.isNotBlank()) {
                                            notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                                        }
                                        selectedNoteName = n.filename
                                        noteEditMode = false
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (sel) "[[ 📄 ${n.filename} ]]" else "[ ${n.filename} ]",
                                    color = if (sel) palette.bg else palette.fg,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .heightIn(min = 46.dp)
                                .background(palette.reminder)
                                .clickable {
                                    val name = "note-${notes.size + 1}.md"
                                    val initialBody = "# $name\n\n"
                                    notes = storageRepo.saveNote(name, initialBody)
                                    selectedNoteName = name
                                    noteDraftName = name
                                    noteDraftText = initialBody
                                    noteEditMode = true
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "[ + NEW.MD ]",
                                color = palette.bg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = fontFamily
                            )
                        }
                    }

                    if (currentNote != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "┌─[ BUFFER: ${currentNote.filename} ]─┐",
                                color = palette.project,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = fontFamily
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (notes.size > 1) {
                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = 44.dp)
                                            .background(palette.bgElevated)
                                            .border(1.dp, palette.priorityA)
                                            .clickable {
                                                val updated = storageRepo.deleteNote(currentNote.filename)
                                                notes = updated
                                                selectedNoteName = updated.firstOrNull()?.filename ?: ""
                                                noteEditMode = false
                                                statusBanner = "[✕ DELETED] ${currentNote.filename}"
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = "[✕ DEL]", color = palette.priorityA, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = fontFamily)
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 44.dp)
                                        .background(if (noteEditMode) palette.reminder else palette.project)
                                        .clickable {
                                            if (noteEditMode) {
                                                notes = storageRepo.saveNote(currentNote.filename, noteDraftText)
                                                noteEditMode = false
                                                statusBanner = "[✓ SAVED] ${currentNote.filename}"
                                            } else {
                                                noteDraftName = currentNote.filename
                                                noteDraftText = currentNote.rawContent
                                                noteEditMode = true
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (noteEditMode) "[✓ SAVE]" else "[✎ EDIT]",
                                        color = palette.bg,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        fontFamily = fontFamily
                                    )
                                }
                            }
                        }

                        if (noteEditMode) {
                            BasicTextField(
                                value = noteDraftText,
                                onValueChange = {
                                    noteDraftText = it
                                    notes = storageRepo.saveNote(currentNote.filename, it)
                                },
                                textStyle = TextStyle(
                                    fontFamily = fontFamily,
                                    color = palette.fg,
                                    fontSize = fontScaleSp.sp,
                                    lineHeight = (fontScaleSp + 6).sp
                                ),
                                cursorBrush = SolidColor(palette.project),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(palette.bgElevated)
                                    .border(1.dp, palette.project)
                                    .padding(16.dp)
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
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                currentNote.bodyContent.lines().forEach { line ->
                                    val isHeader = line.startsWith("#")
                                    Text(
                                        text = if (isHeader) "══ $line ══" else "│ $line",
                                        color = if (isHeader) palette.project else palette.fg,
                                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = if (isHeader) (fontScaleSp + 2).sp else fontScaleSp.sp,
                                        fontFamily = fontFamily
                                    )
                                }
                            }
                        }
                    }
                }
            }

            "settings" -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SettingsScreen(
                        baseDir = storageRepo.baseDir,
                        storageFolderPath = settings.customSafUri ?: storageRepo.baseDir.absolutePath,
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                        onSyncAllToGoogleCalendar = {
                            if (gcalManager.hasCalendarPermissions()) {
                                val (syncedList, count) = gcalManager.syncAllDueTasks(tasks)
                                persistAndSchedule(syncedList)
                                statusBanner = "[✓ GCAL] Synced $count task(s) to Google Calendar"
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
                                statusBanner = "[📦 ARCHIVED] ${completed.size} completed task(s) -> done.txt"
                            } else {
                                statusBanner = "[!] No completed tasks to archive"
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
    }
}

/**
 * Spacious ASCII Box-Drawing Task Card:
 * - Preserves generous 48dp+ touch targets and comfortable padding
 * - Features rich ASCII terminal frames, `[ x ]` / `[   ]` ASCII checkboxes,
 *   `[+project]`, `[@context]`, `[⏰ due]` tags, 1-tap `[🔔 ALARM]` trigger, and `[+GCAL]` sync.
 */
@Composable
private fun UsableTaskCard(
    task: TodoTask,
    fontSizeSp: Int,
    fontScaleSp: Int = fontSizeSp,
    onToggleDone: () -> Unit,
    onTapTask: () -> Unit,
    onTriggerAlarmAndSnooze: () -> Unit,
    onSyncCalendar: () -> Unit
) {
    val palette = LocalTickPalette.current
    val fontFamily = LocalTickFontFamily.current
    val isSyncedToGcal = task.getTagValue("gcal") != null
    val hasReminderOrDue = task.dueDate != null || task.reminderSpecs.isNotEmpty()
    val lineNumFormatted = "%02d".format(task.lineNumber)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bgElevated)
            .border(1.dp, if (task.priority == 'A') palette.priorityA else palette.border)
            .clickable { onTapTask() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Top ASCII Box Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "┌──[ #$lineNumFormatted ]────────────────────",
                color = palette.fgMuted,
                fontSize = 11.sp,
                fontFamily = fontFamily
            )
            Text(
                text = if (task.isCompleted) "[ STATUS: DONE ]──┐"
                else if (hasReminderOrDue) "[ ⏰ ALARM READY ]──┐"
                else "[ OPEN ]──┐",
                color = if (task.isCompleted) palette.reminder else if (hasReminderOrDue) palette.priorityB else palette.fgMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                fontFamily = fontFamily
            )
        }

        // Main Interactive Row with 48dp+ Touch Targets
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Large 48dp x 48dp ASCII Checkbox Touch Target
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 48.dp)
                    .background(if (task.isCompleted) palette.reminder else palette.bg)
                    .border(1.dp, if (task.isCompleted) palette.reminder else palette.project)
                    .clickable { onToggleDone() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (task.isCompleted) "[ x ]" else "[   ]",
                    color = if (task.isCompleted) palette.bg else palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Task Title & ASCII Badges
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (task.priority != null) {
                        val pColor = when (task.priority) {
                            'A' -> palette.priorityA
                            'B' -> palette.priorityB
                            else -> palette.priorityC
                        }
                        Text(
                            text = "[(${task.priority})]",
                            color = pColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = fontScaleSp.sp,
                            fontFamily = fontFamily
                        )
                    }
                    Text(
                        text = task.description.ifEmpty { task.rawLine },
                        color = if (task.isCompleted) palette.fgMuted else palette.fg,
                        fontSize = fontScaleSp.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = fontFamily,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                    )
                }

                // ASCII Metadata Badges ([⏰ due], [+project], [@context])
                if (task.projects.isNotEmpty() || task.contexts.isNotEmpty() || task.dueDate != null) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        task.dueDate?.let { d ->
                            val timePart = task.dueTime?.let { " @$it" } ?: ""
                            Box(
                                modifier = Modifier
                                    .background(palette.bg)
                                    .border(1.dp, palette.priorityB)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "[⏰ $d$timePart]",
                                    color = palette.priorityB,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }
                        task.projects.forEach { p ->
                            Box(
                                modifier = Modifier
                                    .background(palette.bg)
                                    .border(1.dp, palette.project)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "[+$p]",
                                    color = palette.project,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }
                        task.contexts.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .background(palette.bg)
                                    .border(1.dp, palette.contextTag)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "[@$c]",
                                    color = palette.contextTag,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }
                    }
                }
            }

            // Right-side Quick ASCII Action Buttons (Alarm/Snooze & Google Calendar)
            Column(
                modifier = Modifier.padding(start = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .background(palette.bg)
                        .border(1.dp, palette.priorityA)
                        .clickable { onTriggerAlarmAndSnooze() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[🔔 ALARM]",
                        color = palette.priorityA,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        fontFamily = fontFamily
                    )
                }

                if (task.dueDate != null) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .background(palette.bg)
                            .border(1.dp, if (isSyncedToGcal) palette.reminder else palette.contextTag)
                            .clickable { onSyncCalendar() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSyncedToGcal) "[✓GCAL]" else "[+GCAL]",
                            color = if (isSyncedToGcal) palette.reminder else palette.contextTag,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        // Bottom ASCII Box Footer
        Text(
            text = "└──────────────────────────────────────────┘",
            color = palette.fgMuted,
            fontSize = 11.sp,
            fontFamily = fontFamily
        )
    }
}
