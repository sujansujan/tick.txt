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
import androidx.compose.ui.text.font.FontFamily
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

/**
 * Main screen rebuilt from scratch in https://plaintextsports.com/ aesthetic:
 * - Zero CSS/Compose box borders or filled button pills
 * - Character-grid `+--------------------------------------+` scorecards for tasks, notes, and snooze
 * - Underlined inline text links (`All  Open  Scheduled  Done`, `Complete  Snooze  Alarm  +GCal  Edit`)
 * - `< - 1   5 minutes   + 1 >` angle-bracket steppers across Seconds, Minutes, Hours, Days, Weeks, and Months.
 */
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
    var activeFilter by remember { mutableStateOf("open") } // Default to Open tasks like a real todo app
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

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            val (syncedList, count) = gcalManager.syncAllDueTasks(tasks)
            tasks = syncedList
            storageRepo.saveTasks(syncedList)
            statusBanner = "Synced $count task(s) to Google Calendar"
        } else {
            statusBanner = "Calendar permission needed for background sync"
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
            statusBanner = "Updated storage folder"
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
            val exactMs = res.exactOffsetMillis
            val remInfo = when {
                exactMs != null && exactMs < 60_000L -> " (alarm in ${exactMs / 1000L}s)"
                newTask.dueTime != null -> " (alarm at ${newTask.dueTime})"
                newTask.dueDate != null -> " (due ${newTask.dueDate})"
                else -> ""
            }
            statusBanner = "Added: ${newTask.description}$remInfo"
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
    // FULL-SCREEN PLAINTEXTSPORTS ALARM OVERLAY
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
                statusBanner = "Snoozed +$presetSpec (until $fmtTime)"
                activeAlarmOverlay = null
            },
            onDismiss = {
                statusBanner = "Alarm dismissed"
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
                statusBanner = "Completed: ${alarm.taskTitle}"
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
        // PLAINTEXTSPORTS UNDERLINED TOP NAVIGATION LINKS
        // =====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val openCount = tasks.count { !it.isBlankOrComment && !it.isCompleted }
            listOf(
                "tasks" to "Tasks ($openCount)",
                "notes" to "Notes (${notes.size})",
                "settings" to "Settings"
            ).forEach { (tabKey, tabLabel) ->
                val selected = currentTab == tabKey
                Text(
                    text = tabLabel,
                    color = if (selected) palette.project else palette.fg,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { currentTab = tabKey }
                        .padding(vertical = 6.dp)
                )
            }
        }

        // =====================================================================
        // INLINE STATUS / UNDO LINE
        // =====================================================================
        if (statusBanner != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusBanner ?: "",
                    color = palette.reminder,
                    fontSize = 13.sp,
                    fontFamily = monoFont,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (undoSnapshot != null) {
                        Text(
                            text = "Undo",
                            color = palette.project,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = monoFont,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable {
                                    persistAndSchedule(undoSnapshot!!)
                                    undoSnapshot = null
                                    statusBanner = null
                                }
                                .padding(vertical = 4.dp)
                        )
                    }
                    Text(
                        text = "Dismiss",
                        color = palette.fgMuted,
                        fontSize = 13.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { statusBanner = null }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }

        // =====================================================================
        // INLINE TASK EDIT & GRANULAR SNOOZE SCORECARD (PlainTextSports style)
        // =====================================================================
        if (editingTask != null) {
            val target = editingTask!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Edit & Snooze Task",
                        color = palette.fg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = monoFont
                    )
                    Text(
                        text = "Close",
                        color = palette.fgMuted,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { editingTask = null }
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                    )
                }

                // Editable task text inside +---+ frame
                Text(
                    text = PlainTextSportsAscii.TOP_BORDER,
                    color = palette.fgMuted,
                    fontSize = 13.sp,
                    fontFamily = monoFont
                )
                BasicTextField(
                    value = editingTaskDraft,
                    onValueChange = { editingTaskDraft = it },
                    textStyle = TextStyle(fontFamily = monoFont, color = palette.fg, fontSize = fontScaleSp.sp),
                    cursorBrush = SolidColor(palette.project),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    text = PlainTextSportsAscii.BOT_BORDER,
                    color = palette.fgMuted,
                    fontSize = 13.sp,
                    fontFamily = monoFont
                )

                // Granular Snooze Unit Links: sec  min  hr  day  wk  mo
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Snooze:", color = palette.fgMuted, fontSize = 13.sp, fontFamily = monoFont)
                    SnoozeUnit.entries.forEach { u ->
                        val sel = editSnoozeUnit == u
                        Text(
                            text = u.asciiLabel.lowercase(),
                            color = if (sel) palette.project else palette.fg,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                            fontFamily = monoFont,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable {
                                    editSnoozeUnit = u
                                    if (editSnoozeAmount !in u.quickValues) {
                                        editSnoozeAmount = u.quickValues.first()
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        )
                    }
                }

                // Angle-bracket stepper: < - 1    5 min    + 1 >   and Quick links
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "< - 1",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { editSnoozeAmount = (editSnoozeAmount - 1).coerceAtLeast(1) }
                            .padding(vertical = 6.dp)
                    )
                    Text(
                        text = "$editSnoozeAmount ${editSnoozeUnit.asciiLabel.lowercase()}",
                        color = palette.fg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = monoFont
                    )
                    Text(
                        text = "+ 1 >",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { editSnoozeAmount = (editSnoozeAmount + 1).coerceAtMost(365) }
                            .padding(vertical = 6.dp)
                    )

                    editSnoozeUnit.quickValues.forEach { qv ->
                        Text(
                            text = "+$qv${editSnoozeUnit.code}",
                            color = if (editSnoozeAmount == qv) palette.priorityB else palette.fgMuted,
                            fontWeight = if (editSnoozeAmount == qv) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = monoFont,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable { editSnoozeAmount = qv }
                                .padding(vertical = 6.dp)
                        )
                    }
                }

                // Underlined Action Links
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Apply Snooze (+$editSnoozeAmount${editSnoozeUnit.code})",
                        color = palette.reminder,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
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
                                statusBanner = "Snoozed +$editSnoozeAmount${editSnoozeUnit.code} (until ${zdt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))})"
                            }
                            .padding(vertical = 8.dp)
                    )

                    Text(
                        text = "Save Task",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                undoSnapshot = tasks
                                val reParsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber).copy(isDirty = true)
                                persistAndSchedule(
                                    tasks.map { if (it.lineNumber == target.lineNumber) reParsed else it }
                                )
                                editingTask = null
                                statusBanner = "Saved task"
                            }
                            .padding(vertical = 8.dp)
                    )

                    Text(
                        text = "Alarm Now",
                        color = palette.priorityB,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                val parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                                editingTask = null
                                triggerFullAlarmAndNotification(
                                    taskTitle = parsed.description,
                                    rawSpec = parsed.reminderSpecs.firstOrNull() ?: "0m/a",
                                    taskId = parsed.stableId
                                )
                            }
                            .padding(vertical = 8.dp)
                    )

                    Text(
                        text = "Delete",
                        color = palette.priorityA,
                        fontSize = 14.sp,
                        fontFamily = monoFont,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                undoSnapshot = tasks
                                persistAndSchedule(tasks.filterNot { it.lineNumber == target.lineNumber })
                                editingTask = null
                                statusBanner = "Deleted task"
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }

        // =====================================================================
        // MAIN CONTENT AREA
        // =====================================================================
        when (currentTab) {
            "tasks" -> {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // PlainTextSports Category Links ("Views: Open  All  Scheduled  Done")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Views:",
                            color = palette.fgMuted,
                            fontSize = 13.sp,
                            fontFamily = monoFont
                        )
                        val filters = listOf(
                            "open" to "Open",
                            "all" to "All",
                            "due" to "Scheduled",
                            "done" to "Completed"
                        ) + dynamicProjects.map { it to it } + dynamicContexts.map { it to it }

                        filters.forEach { (fKey, fLabel) ->
                            val active = activeFilter == fKey
                            Text(
                                text = fLabel,
                                color = if (active) palette.project else palette.fg,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp,
                                fontFamily = monoFont,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .clickable { activeFilter = fKey }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }

                    // PlainTextSports Scorecard Task List
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (filteredTasks.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = PlainTextSportsAscii.TROPHY_ART,
                                        color = palette.project,
                                        fontSize = 13.sp,
                                        lineHeight = 16.sp,
                                        fontFamily = monoFont
                                    )
                                    val emptyBox = remember {
                                        buildString {
                                            appendLine(PlainTextSportsAscii.TOP_BORDER)
                                            appendLine(PlainTextSportsAscii.row("No tasks in this view."))
                                            appendLine(PlainTextSportsAscii.row("Type below to add a task or alarm."))
                                            append(PlainTextSportsAscii.BOT_BORDER)
                                        }
                                    }
                                    Text(
                                        text = emptyBox,
                                        color = palette.fgMuted,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        fontFamily = monoFont
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
                                                statusBanner = "Synced to Google Calendar: ${task.description}"
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

                    // Expandable Granular Offset Links (when tapping "+ Time")
                    if (showTimePickerSheet) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Unit:", color = palette.fgMuted, fontSize = 13.sp, fontFamily = monoFont)
                                SnoozeUnit.entries.forEach { u ->
                                    val sel = pickerUnit == u
                                    Text(
                                        text = u.asciiLabel.lowercase(),
                                        color = if (sel) palette.project else palette.fg,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                        fontFamily = monoFont,
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier
                                            .clickable {
                                                pickerUnit = u
                                                pickerAmount = u.quickValues.first()
                                            }
                                            .padding(vertical = 6.dp)
                                    )
                                }
                                Text(
                                    text = "Close",
                                    color = palette.fgMuted,
                                    fontSize = 13.sp,
                                    fontFamily = monoFont,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier
                                        .clickable { showTimePickerSheet = false }
                                        .padding(vertical = 6.dp)
                                )
                            }

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Offset:", color = palette.fgMuted, fontSize = 13.sp, fontFamily = monoFont)
                                pickerUnit.quickValues.forEach { qv ->
                                    val chip = "in $qv${pickerUnit.code}"
                                    Text(
                                        text = "+$qv${pickerUnit.code}",
                                        color = palette.reminder,
                                        fontSize = 14.sp,
                                        fontFamily = monoFont,
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier
                                            .clickable {
                                                smartInput = if (smartInput.isBlank()) chip else "${smartInput.trim()} $chip"
                                                showTimePickerSheet = false
                                            }
                                            .padding(vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Live Smart-Add Preview Links
                    if (parsePreview != null && parsePreview.recognizedSpans.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Parsed:", color = palette.fgMuted, fontSize = 12.sp, fontFamily = monoFont)
                            parsePreview.recognizedSpans.forEach { span ->
                                Text(
                                    text = "${span.resolvedToken} (x)",
                                    color = palette.reminder,
                                    fontSize = 12.sp,
                                    fontFamily = monoFont,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier
                                        .clickable { disabledSpans = disabledSpans + span.id }
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Bottom Quick-Add Input Box (+--------------------------------------+)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = PlainTextSportsAscii.TOP_BORDER,
                            color = palette.fgMuted,
                            fontSize = 13.sp,
                            fontFamily = monoFont
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "| > ",
                                color = palette.project,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = monoFont
                            )
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (smartInput.isEmpty()) {
                                    Text(
                                        text = "add task (e.g. check chocotat in 1m)",
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
                                    cursorBrush = SolidColor(palette.project),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Text(
                                text = " |",
                                color = palette.fgMuted,
                                fontSize = 13.sp,
                                fontFamily = monoFont
                            )
                        }
                        Text(
                            text = PlainTextSportsAscii.BOT_BORDER,
                            color = palette.fgMuted,
                            fontSize = 13.sp,
                            fontFamily = monoFont
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showTimePickerSheet) "Hide Time Offsets" else "+ Time / Snooze Offset",
                                color = palette.priorityB,
                                fontSize = 14.sp,
                                fontFamily = monoFont,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .clickable { showTimePickerSheet = !showTimePickerSheet }
                                    .padding(vertical = 8.dp)
                            )

                            Text(
                                text = "Add Task >",
                                color = palette.reminder,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                fontFamily = monoFont,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .clickable { submitSmartAdd() }
                                    .padding(vertical = 8.dp)
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
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Underlined Note File Links
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Files:", color = palette.fgMuted, fontSize = 13.sp, fontFamily = monoFont)
                        notes.forEach { n ->
                            val sel = n.filename == currentNote?.filename
                            Text(
                                text = n.filename,
                                color = if (sel) palette.project else palette.fg,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp,
                                fontFamily = monoFont,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .clickable {
                                        if (noteEditMode && noteDraftName.isNotBlank()) {
                                            notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                                        }
                                        selectedNoteName = n.filename
                                        noteEditMode = false
                                    }
                                    .padding(vertical = 6.dp)
                            )
                        }

                        Text(
                            text = "+ New Note",
                            color = palette.reminder,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            fontFamily = monoFont,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable {
                                    val name = "note-${notes.size + 1}.md"
                                    val initialBody = "# $name\n\n"
                                    notes = storageRepo.saveNote(name, initialBody)
                                    selectedNoteName = name
                                    noteDraftName = name
                                    noteDraftText = initialBody
                                    noteEditMode = true
                                }
                                .padding(vertical = 6.dp)
                        )
                    }

                    if (currentNote != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (noteEditMode) "Save Note" else "Edit Note",
                                color = if (noteEditMode) palette.reminder else palette.project,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = monoFont,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .clickable {
                                        if (noteEditMode) {
                                            notes = storageRepo.saveNote(currentNote.filename, noteDraftText)
                                            noteEditMode = false
                                            statusBanner = "Saved ${currentNote.filename}"
                                        } else {
                                            noteDraftName = currentNote.filename
                                            noteDraftText = currentNote.rawContent
                                            noteEditMode = true
                                        }
                                    }
                                    .padding(vertical = 6.dp)
                            )

                            if (notes.size > 1) {
                                Text(
                                    text = "Delete Note",
                                    color = palette.priorityA,
                                    fontSize = 14.sp,
                                    fontFamily = monoFont,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier
                                        .clickable {
                                            val updated = storageRepo.deleteNote(currentNote.filename)
                                            notes = updated
                                            selectedNoteName = updated.firstOrNull()?.filename ?: ""
                                            noteEditMode = false
                                            statusBanner = "Deleted ${currentNote.filename}"
                                        }
                                        .padding(vertical = 6.dp)
                                )
                            }
                        }

                        if (noteEditMode) {
                            Text(
                                text = PlainTextSportsAscii.TOP_BORDER,
                                color = palette.fgMuted,
                                fontSize = 13.sp,
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
                                    lineHeight = (fontScaleSp + 6).sp
                                ),
                                cursorBrush = SolidColor(palette.project),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                            Text(
                                text = PlainTextSportsAscii.BOT_BORDER,
                                color = palette.fgMuted,
                                fontSize = 13.sp,
                                fontFamily = monoFont
                            )
                        } else {
                            val noteBox = remember(currentNote.filename, currentNote.bodyContent) {
                                buildString {
                                    appendLine(PlainTextSportsAscii.TOP_BORDER)
                                    appendLine(PlainTextSportsAscii.rowLeftRight(currentNote.filename, "notes/"))
                                    appendLine(PlainTextSportsAscii.MID_DIVIDER)
                                    currentNote.bodyContent.lines().forEach { line ->
                                        PlainTextSportsAscii.wrapRows(line).forEach { appendLine(it) }
                                    }
                                    append(PlainTextSportsAscii.BOT_BORDER)
                                }
                            }
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
                                Text(
                                    text = noteBox,
                                    color = palette.fg,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    fontFamily = monoFont
                                )
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
                                statusBanner = "Synced $count task(s) to Google Calendar"
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
                                statusBanner = "Archived ${completed.size} task(s) to done.txt"
                            } else {
                                statusBanner = "No completed tasks to archive"
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
 * PlainTextSports.com Scorecard Task Box:
 * Renders each task inside a strict 40-character `+--------------------------------------+`
 * ASCII scorecard with underlined action links (`Complete   Snooze   Alarm   +GCal`) underneath.
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
    val monoFont = FontFamily.Monospace
    val isSyncedToGcal = task.getTagValue("gcal") != null

    val statusLeft = buildString {
        append(if (task.isCompleted) "[x]" else "[ ]")
        if (task.priority != null) append(" (${task.priority})")
    }
    val statusRight = when {
        task.isCompleted -> "DONE"
        task.dueDate != null && task.dueTime != null -> "${task.dueDate} ${task.dueTime}"
        task.dueDate != null -> "Due ${task.dueDate}"
        else -> "OPEN"
    }

    val tagsLine = buildString {
        task.projects.forEach { append("+$it ") }
        task.contexts.forEach { append("@$it ") }
    }.trim()

    val remRight = task.reminderSpecs.firstOrNull()?.let { "rem:$it" } ?: ""

    val scorecardText = remember(task.rawLine, task.isCompleted) {
        buildString {
            appendLine(PlainTextSportsAscii.TOP_BORDER)
            appendLine(PlainTextSportsAscii.rowLeftRight(statusLeft, statusRight))
            PlainTextSportsAscii.wrapRows(task.description.ifEmpty { task.rawLine }).forEach {
                appendLine(it)
            }
            if (tagsLine.isNotEmpty() || remRight.isNotEmpty()) {
                appendLine(PlainTextSportsAscii.rowLeftRight(tagsLine, remRight))
            }
            append(PlainTextSportsAscii.BOT_BORDER)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // The PlainTextSports `+--------------------------------------+` Box
        Text(
            text = scorecardText,
            color = if (task.isCompleted) palette.fgMuted else palette.fg,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontFamily = monoFont,
            modifier = Modifier.clickable { onTapTask() }
        )

        // Underlined PlainTextSports Action Links right below the box (44dp+ touch height)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (task.isCompleted) "Reopen" else "Complete",
                color = palette.reminder,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = monoFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onToggleDone() }
                    .padding(vertical = 8.dp)
            )

            Text(
                text = "Snooze / Edit",
                color = palette.project,
                fontSize = 13.sp,
                fontFamily = monoFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onTapTask() }
                    .padding(vertical = 8.dp)
            )

            Text(
                text = "Alarm",
                color = palette.priorityB,
                fontSize = 13.sp,
                fontFamily = monoFont,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onTriggerAlarmAndSnooze() }
                    .padding(vertical = 8.dp)
            )

            if (task.dueDate != null) {
                Text(
                    text = if (isSyncedToGcal) "GCal Synced" else "+GCal",
                    color = if (isSyncedToGcal) palette.fgMuted else palette.contextTag,
                    fontSize = 13.sp,
                    fontFamily = monoFont,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { onSyncCalendar() }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}
