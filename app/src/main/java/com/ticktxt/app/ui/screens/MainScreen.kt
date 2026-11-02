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

    var smartInput by remember { mutableStateOf("") }
    var disabledSpans by remember { mutableStateOf(setOf<String>()) }
    var showTimePickerSheet by remember { mutableStateOf(false) }

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
            statusBanner = "Synced $count task(s) to Google Calendar"
        } else {
            statusBanner = "Calendar permission needed for background sync (or use Open in Calendar)"
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
            val instances = ReminderScheduleCalculator.computeSchedulesForTask(t, nowZdt)
            instances.forEach { inst ->
                val isLastAdded = (idx == finalTasks.lastIndex && exactDelayMillisForLastTask != null)
                val targetMs = if (isLastAdded) {
                    System.currentTimeMillis() + exactDelayMillisForLastTask!!
                } else {
                    inst.triggerAt.toInstant().toEpochMilli()
                }
                if (targetMs > System.currentTimeMillis() - 5_000L) {
                    scheduler.scheduleInstance(inst, overrideTriggerEpochMs = targetMs)
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

    // Listen for Mark Done action from Android notification
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                tasks = storageRepo.loadTasks()
                statusBanner = "Task marked done"
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

    // Back gesture support
    BackHandler(
        enabled = editingTask != null || showTimePickerSheet || noteEditMode || currentTab != "tasks"
    ) {
        when {
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
            statusBanner = "Folder updated"
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
            val remInfo = if (newTask.dueTime != null) {
                " • Reminder set for ${newTask.dueTime}"
            } else ""
            statusBanner = "Added \"${newTask.description}\"$remInfo"
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
    ) {
        // =====================================================================
        // SPACIOUS TOP NAVIGATION BAR (48dp+ touch targets, zero fluff)
        // =====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgElevated)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val openCount = tasks.count { !it.isBlankOrComment && !it.isCompleted }
            listOf(
                "tasks" to "Tasks ($openCount)",
                "notes" to "Notes (${notes.size})",
                "settings" to "Settings"
            ).forEach { (tabKey, tabLabel) ->
                val selected = currentTab == tabKey
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .background(if (selected) palette.project else palette.bg)
                        .border(1.dp, if (selected) palette.project else palette.border)
                        .clickable { currentTab = tabKey }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tabLabel,
                        color = if (selected) palette.bg else palette.fg,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                }
            }
        }

        // =====================================================================
        // FEEDBACK / UNDO BANNER (Spacious, easy to tap)
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
                    text = statusBanner ?: "",
                    color = palette.fg,
                    fontSize = 14.sp,
                    fontFamily = fontFamily,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (undoSnapshot != null) {
                        Box(
                            modifier = Modifier
                                .background(palette.project)
                                .clickable {
                                    persistAndSchedule(undoSnapshot!!)
                                    undoSnapshot = null
                                    statusBanner = null
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Undo",
                                color = palette.bg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = fontFamily
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clickable { statusBanner = null }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Dismiss",
                            color = palette.fgMuted,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }

        // =====================================================================
        // TASK EDIT & GOOGLE CALENDAR SHEET (Opens when tapping any task)
        // =====================================================================
        if (editingTask != null) {
            val target = editingTask!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(palette.bgElevated)
                    .border(1.dp, palette.project)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Edit Task & Reminder",
                        color = palette.project,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        fontFamily = fontFamily
                    )
                    Box(
                        modifier = Modifier
                            .clickable { editingTask = null }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text = "Close", color = palette.fgMuted, fontSize = 14.sp, fontFamily = fontFamily)
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

                Text(
                    text = "Set Reminder In:",
                    color = palette.fgMuted,
                    fontSize = 13.sp,
                    fontFamily = fontFamily
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "1 min" to 1L,
                        "5 min" to 5L,
                        "15 min" to 15L,
                        "30 min" to 30L,
                        "1 hr" to 60L,
                        "1.5 hrs" to 90L,
                        "2 hrs" to 120L,
                        "Tomorrow" to 1440L
                    ).forEach { (label, mins) ->
                        Box(
                            modifier = Modifier
                                .heightIn(min = 42.dp)
                                .background(palette.bg)
                                .border(1.dp, palette.reminder)
                                .clickable {
                                    val dt = LocalDateTime.now().plusMinutes(mins)
                                    val dStr = dt.toLocalDate().toString()
                                    val tStr = "%02d:%02d".format(dt.hour, dt.minute)
                                    var parsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                                    parsed = TodoParser.withUpdatedTag(parsed, "due", dStr)
                                    parsed = TodoParser.withUpdatedTag(parsed, "duetime", tStr)
                                    parsed = TodoParser.withReplacedMultiTag(parsed, "rem", listOf("0m/n"))
                                    editingTaskDraft = TodoParser.serializeLine(parsed)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = label, color = palette.reminder, fontSize = 13.sp, fontFamily = fontFamily)
                        }
                    }
                }

                // Google Calendar Sync Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
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
                                        statusBanner = "Synced to Google Calendar"
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
                            text = "Add to Google Calendar",
                            color = palette.contextTag,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = fontFamily
                        )
                    }
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
                                statusBanner = "Task deleted"
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Delete",
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
                                statusBanner = "Changes saved"
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Save",
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
                    // Spacious Filter Pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val filters = listOf(
                            "all" to "All",
                            "open" to "Open",
                            "due" to "Scheduled"
                        ) + dynamicProjects.map { it to it } + dynamicContexts.map { it to it }

                        filters.forEach { (fKey, fLabel) ->
                            val active = activeFilter == fKey
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .background(if (active) palette.project else palette.bgElevated)
                                    .border(1.dp, if (active) palette.project else palette.border)
                                    .clickable { activeFilter = fKey }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = fLabel,
                                    color = if (active) palette.bg else palette.fg,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }
                    }

                    // Spacious Task List
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (filteredTasks.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No tasks here yet. Type below to add one.",
                                        color = palette.fgMuted,
                                        fontSize = fontScaleSp.sp,
                                        fontFamily = fontFamily
                                    )
                                }
                            }
                        } else {
                            items(filteredTasks, key = { "${it.lineNumber}:${it.rawLine}" }) { task ->
                                UsableTaskCard(
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
                                    },
                                    onTapTask = {
                                        editingTask = task
                                        editingTaskDraft = TodoParser.serializeLine(task)
                                    },
                                    onSyncCalendar = {
                                        if (gcalManager.hasCalendarPermissions()) {
                                            val (synced, ok) = gcalManager.syncTaskToGoogleCalendar(task)
                                            if (ok) {
                                                persistAndSchedule(
                                                    tasks.map { if (it.lineNumber == task.lineNumber) synced else it }
                                                )
                                                statusBanner = "Synced \"${task.description}\" to Google Calendar"
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

                    // Expandable Quick Time Picker Sheet (when tapping [+ Time])
                    if (showTimePickerSheet) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(palette.bgElevated)
                                .border(1.dp, palette.border)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Quick Reminder Time",
                                    color = palette.project,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily
                                )
                                Box(
                                    modifier = Modifier
                                        .clickable { showTimePickerSheet = false }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(text = "Done", color = palette.reminder, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = fontFamily)
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "in 1m", "in 5m", "in 15m", "in 30m",
                                    "in 1 hr", "in 1.5 hrs", "in 2 hrs", "tomorrow 9am", "in 1 week", "in 1 month"
                                ).forEach { chip ->
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
                                        Text(text = chip, color = palette.reminder, fontSize = 13.sp, fontFamily = fontFamily)
                                    }
                                }
                            }
                        }
                    }

                    // Clean Live Smart-Add Preview Chips (Only shown while typing)
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
                                        .heightIn(min = 36.dp)
                                        .background(palette.bgElevated)
                                        .border(1.dp, palette.reminder)
                                        .clickable { disabledSpans = disabledSpans + span.id }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${span.resolvedToken}  ✕",
                                        color = palette.reminder,
                                        fontSize = 13.sp,
                                        fontFamily = fontFamily
                                    )
                                }
                            }
                        }
                    }

                    // Spacious Bottom Capture Bar (56dp+ height, large Add & Time buttons)
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
                                text = "+ Time",
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
                                    text = "Add task (e.g. check chocotat at factory in 1m)...",
                                    color = palette.fgMuted,
                                    fontSize = 14.sp,
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
                                .clickable { submitSmartAdd() }
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Add",
                                color = palette.bg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
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
                    // Spacious Note Selector Pills
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
                                    .heightIn(min = 44.dp)
                                    .background(if (sel) palette.project else palette.bgElevated)
                                    .border(1.dp, if (sel) palette.project else palette.border)
                                    .clickable {
                                        if (noteEditMode && noteDraftName.isNotBlank()) {
                                            notes = storageRepo.saveNote(noteDraftName, noteDraftText)
                                        }
                                        selectedNoteName = n.filename
                                        noteEditMode = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = n.filename,
                                    color = if (sel) palette.bg else palette.fg,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .heightIn(min = 44.dp)
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
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "+ New Note",
                                color = palette.bg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = fontFamily
                            )
                        }
                    }

                    if (currentNote != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (notes.size > 1) {
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 42.dp)
                                        .background(palette.bgElevated)
                                        .border(1.dp, palette.priorityA)
                                        .clickable {
                                            val updated = storageRepo.deleteNote(currentNote.filename)
                                            notes = updated
                                            selectedNoteName = updated.firstOrNull()?.filename ?: ""
                                            noteEditMode = false
                                            statusBanner = "Deleted ${currentNote.filename}"
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "Delete Note", color = palette.priorityA, fontSize = 13.sp, fontFamily = fontFamily)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                            }

                            Box(
                                modifier = Modifier
                                    .heightIn(min = 42.dp)
                                    .background(if (noteEditMode) palette.reminder else palette.project)
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
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (noteEditMode) "Done Editing" else "Edit Note",
                                    color = palette.bg,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    fontFamily = fontFamily
                                )
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
                                        text = line,
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
                                statusBanner = "Archived ${completed.size} completed task(s)"
                            } else {
                                statusBanner = "No completed tasks to archive"
                            }
                        },
                        onFireImmediateNotification = {
                            scheduler.fireImmediateNotification(
                                taskTitle = "Test reminder from tick.txt",
                                rawSpec = "0m/n",
                                typeCode = "n"
                            )
                            statusBanner = "Notification sent"
                        }
                    )
                }
            }
        }
    }
}

/**
 * Clean, spacious, easy-to-tap Task Card with zero raw-tag clutter:
 * - 48dp+ checkbox touch area on the left
 * - Clear task title + clean pill badges for +project, @context, due date/time, and Google Calendar sync
 */
@Composable
private fun UsableTaskCard(
    task: TodoTask,
    fontSizeSp: Int,
    onToggleDone: () -> Unit,
    onTapTask: () -> Unit,
    onSyncCalendar: () -> Unit
) {
    val palette = LocalTickPalette.current
    val fontFamily = LocalTickFontFamily.current
    val isSyncedToGcal = task.getTagValue("gcal") != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bgElevated)
            .border(1.dp, palette.border)
            .clickable { onTapTask() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Large 48dp x 48dp Checkbox Touch Target
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { onToggleDone() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(if (task.isCompleted) palette.reminder else palette.bg)
                    .border(2.dp, if (task.isCompleted) palette.reminder else palette.project),
                contentAlignment = Alignment.Center
            ) {
                if (task.isCompleted) {
                    Text(
                        text = "✓",
                        color = palette.bg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = fontFamily
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Task Title & Clean Badges
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
                        text = "(${task.priority})",
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
                    fontWeight = FontWeight.Medium,
                    fontFamily = fontFamily,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )
            }

            // Clean Metadata Pills (+project, @context, Due Date/Time)
            if (task.projects.isNotEmpty() || task.contexts.isNotEmpty() || task.dueDate != null) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    task.dueDate?.let { d ->
                        val timePart = task.dueTime?.let { " $it" } ?: ""
                        Box(
                            modifier = Modifier
                                .background(palette.bg)
                                .border(1.dp, palette.priorityB)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "$d$timePart",
                                color = palette.priorityB,
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
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "+$p",
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
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "@$c",
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

        // 1-Tap Google Calendar Sync Button on Tasks with Due Dates
        if (task.dueDate != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 42.dp)
                    .padding(start = 6.dp)
                    .background(palette.bg)
                    .border(1.dp, if (isSyncedToGcal) palette.reminder else palette.border)
                    .clickable { onSyncCalendar() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSyncedToGcal) "Synced" else "+ Cal",
                    color = if (isSyncedToGcal) palette.reminder else palette.contextTag,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = fontFamily
                )
            }
        }
    }
}
