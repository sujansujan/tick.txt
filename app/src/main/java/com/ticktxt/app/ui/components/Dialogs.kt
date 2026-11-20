package com.ticktxt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.nlp.SmartAddParseResult
import com.ticktxt.app.core.notes.NoteFile
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.ascii.AsciiBoxActionRow
import com.ticktxt.app.ui.ascii.AsciiBoxBodyRow
import com.ticktxt.app.ui.ascii.AsciiBoxLeftRightRow
import com.ticktxt.app.ui.ascii.AsciiBoxOptionRow
import com.ticktxt.app.ui.ascii.AsciiHorizontalBorder
import com.ticktxt.app.ui.ascii.AsciiSnoozeWheelPopup
import com.ticktxt.app.ui.ascii.AsciiTitledTopBorder
import com.ticktxt.app.ui.ascii.DUE_TAG_REGEX
import com.ticktxt.app.ui.ascii.DUETIME_TAG_REGEX
import com.ticktxt.app.ui.ascii.PlainTextSportsAscii
import com.ticktxt.app.ui.ascii.toggleTagInText
import com.ticktxt.app.ui.theme.LocalTickPalette
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun NoteEditorDialog(
    isNewNoteDraft: Boolean,
    currentNote: NoteFile?,
    noteEditMode: Boolean,
    noteDraftName: String,
    noteDraftText: String,
    backlinkedTasks: List<TodoTask>,
    fontSizeSp: Int,
    onNoteDraftNameChange: (String) -> Unit,
    onNoteDraftTextChange: (String) -> Unit,
    onSetEditMode: (Boolean) -> Unit,
    onTogglePreviewCheckbox: (Int) -> Unit,
    onTapBacklinkedTask: (TodoTask) -> Unit,
    onCommitAndKeepOpen: () -> Unit,
    onCommitAndClose: () -> Unit,
    onDeleteNote: () -> Unit
) {
    val palette = LocalTickPalette.current
    val monoFont = FontFamily.Monospace
    val displayLines = remember(noteDraftText, currentNote?.bodyContent, noteEditMode) {
        if (noteEditMode) noteDraftText.lines()
        else (currentNote?.bodyContent ?: noteDraftText).lines()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.scrim)
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCommitAndClose() }
            .padding(horizontal = 14.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(10.dp)
        ) {
            AsciiTitledTopBorder(
                badgeText = if (isNewNoteDraft) "+ NEW NOTE (.md)" else "/ NOTE (.md)",
                borderColor = palette.project,
                badgeColor = palette.project,
                rightBadgeText = "X Close",
                rightBadgeColor = palette.fgMuted,
                onRightBadgeClick = onCommitAndClose
            )

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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (noteDraftName.isEmpty()) {
                        Text(
                            text = "title.md (optional -- auto-named if blank)",
                            color = palette.fgMuted,
                            fontSize = (fontSizeSp - 2).coerceAtLeast(11).sp,
                            fontFamily = monoFont,
                            maxLines = 1
                        )
                    }
                    BasicTextField(
                        value = noteDraftName,
                        onValueChange = {
                            onNoteDraftNameChange(it)
                            onSetEditMode(true)
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = monoFont,
                            color = palette.project,
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSizeSp.sp
                        ),
                        cursorBrush = SolidColor(palette.project),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = " |",
                    color = palette.border,
                    fontSize = 12.sp,
                    fontFamily = monoFont
                )
            }
            AsciiHorizontalBorder(corner = "|")

            AsciiBoxActionRow(
                actions = listOf(
                    Triple<String, Color, () -> Unit>("[+ # H1]", palette.project) {
                        onSetEditMode(true)
                        onNoteDraftTextChange(
                            if (noteDraftText.endsWith("\n") || noteDraftText.isEmpty()) "${noteDraftText}# "
                            else "$noteDraftText\n# "
                        )
                    },
                    Triple<String, Color, () -> Unit>("[+ [ ]]", palette.reminder) {
                        onSetEditMode(true)
                        onNoteDraftTextChange(
                            if (noteDraftText.endsWith("\n") || noteDraftText.isEmpty()) "${noteDraftText}- [ ] "
                            else "$noteDraftText\n- [ ] "
                        )
                    },
                    Triple<String, Color, () -> Unit>("[+ -]", palette.contextTag) {
                        onSetEditMode(true)
                        onNoteDraftTextChange(
                            if (noteDraftText.endsWith("\n") || noteDraftText.isEmpty()) "${noteDraftText}- "
                            else "$noteDraftText\n- "
                        )
                    },
                    Triple<String, Color, () -> Unit>("[**B**]", palette.priorityB) {
                        onSetEditMode(true)
                        onNoteDraftTextChange("$noteDraftText **bold**")
                    },
                    Triple<String, Color, () -> Unit>("[`code`]", palette.fgMuted) {
                        onSetEditMode(true)
                        onNoteDraftTextChange("$noteDraftText `code`")
                    }
                ),
                fontSizeSp = (fontSizeSp - 2).coerceAtLeast(11)
            )
            AsciiHorizontalBorder(corner = "|")

            if (noteEditMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 340.dp)
                ) {
                    Text(
                        text = "| ",
                        color = palette.border,
                        fontSize = 12.sp,
                        fontFamily = monoFont
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.TopStart
                    ) {
                        if (noteDraftText.isEmpty()) {
                            Text(
                                text = "Write Markdown note (# Heading, - [ ] checklist, **bold**)...",
                                color = palette.fgMuted,
                                fontSize = (fontSizeSp - 1).coerceAtLeast(12).sp,
                                fontFamily = monoFont
                            )
                        }
                        BasicTextField(
                            value = noteDraftText,
                            onValueChange = onNoteDraftTextChange,
                            textStyle = TextStyle(
                                fontFamily = monoFont,
                                color = palette.fg,
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp + 4).sp
                            ),
                            cursorBrush = SolidColor(palette.project),
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    Text(
                        text = " |",
                        color = palette.border,
                        fontSize = 12.sp,
                        fontFamily = monoFont
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    displayLines.forEachIndexed { idx, line ->
                        MarkdownFormattedRow(
                            rawLine = line,
                            fontSizeSp = fontSizeSp,
                            onTapCheckbox = { onTogglePreviewCheckbox(idx) },
                            onTapLineToEdit = { onSetEditMode(true) }
                        )
                    }
                }
            }

            if (!noteEditMode && backlinkedTasks.isNotEmpty()) {
                AsciiHorizontalBorder(corner = "|")
                AsciiBoxBodyRow(
                    text = "BACKLINKS (${backlinkedTasks.size})",
                    textColor = palette.noteLink,
                    fontSizeSp = (fontSizeSp - 2).coerceAtLeast(11),
                    bold = true,
                    singleLine = true
                )
                backlinkedTasks.take(4).forEach { linked ->
                    val prefix = if (linked.isCompleted) "[x]" else "[ ]"
                    AsciiBoxLeftRightRow(
                        left = "$prefix ${linked.description}",
                        right = linked.dueDate?.toString() ?: "",
                        leftColor = if (linked.isCompleted) palette.fgMuted else palette.fg,
                        rightColor = palette.priorityB,
                        fontSizeSp = (fontSizeSp - 2).coerceAtLeast(11),
                        onClick = { onTapBacklinkedTask(linked) }
                    )
                }
            }

            AsciiHorizontalBorder(corner = "|")

            val popupActions: List<Triple<String, Color, () -> Unit>> = buildList {
                add(
                    Triple<String, Color, () -> Unit>(
                        if (noteEditMode) "[Preview]" else "[/ Edit]",
                        palette.project
                    ) {
                        if (noteEditMode) onCommitAndKeepOpen() else onSetEditMode(true)
                    }
                )
                add(Triple("[+ Save]", palette.reminder, onCommitAndClose))
                if (!isNewNoteDraft) {
                    add(Triple("[X Delete]", palette.priorityA, onDeleteNote))
                }
                add(Triple("[X Close]", palette.fgMuted, onCommitAndClose))
            }
            AsciiBoxActionRow(
                actions = popupActions,
                fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12)
            )
            AsciiHorizontalBorder()
        }
    }
}

@Composable
fun TagsFilterDialog(
    dynamicProjects: List<String>,
    dynamicContexts: List<String>,
    activeFilter: String,
    fontSizeSp: Int,
    onSelectFilter: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTickPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bgSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(12.dp)
        ) {
            AsciiTitledTopBorder(
                badgeText = "+@ PROJECTS & CONTEXTS",
                borderColor = palette.project,
                badgeColor = palette.project,
                rightBadgeText = "X Close",
                rightBadgeColor = palette.fgMuted,
                onRightBadgeClick = onDismiss
            )
            if (dynamicProjects.isEmpty() && dynamicContexts.isEmpty()) {
                AsciiBoxBodyRow(
                    text = "No +projects or @contexts found in tasks.",
                    textColor = palette.fgMuted,
                    fontSizeSp = fontSizeSp
                )
            } else {
                if (dynamicProjects.isNotEmpty()) {
                    AsciiBoxOptionRow(
                        label = "[+] Proj",
                        options = dynamicProjects.map { it to it },
                        selectedValue = activeFilter,
                        onSelect = {
                            onSelectFilter(it)
                            onDismiss()
                        },
                        activeColor = palette.project,
                        fontSizeSp = fontSizeSp
                    )
                }
                if (dynamicContexts.isNotEmpty()) {
                    AsciiBoxOptionRow(
                        label = "[@] Ctx",
                        options = dynamicContexts.map { it to it },
                        selectedValue = activeFilter,
                        onSelect = {
                            onSelectFilter(it)
                            onDismiss()
                        },
                        activeColor = palette.contextTag,
                        fontSizeSp = fontSizeSp
                    )
                }
            }
            AsciiHorizontalBorder(corner = "|")
            AsciiBoxActionRow(
                actions = listOf(
                    Triple("[*] Show All Open", palette.reminder) {
                        onSelectFilter("open")
                        onDismiss()
                    },
                    Triple("[X Close]", palette.fgMuted, onDismiss)
                ),
                fontSizeSp = fontSizeSp
            )
            AsciiHorizontalBorder()
        }
    }
}

@Composable
fun EditTaskDialog(
    target: TodoTask,
    editingTaskDraft: String,
    showDueDateWheel: Boolean,
    quickTagChips: List<String>,
    defaultTimedReminder: String,
    currentNow: LocalDateTime,
    fontSizeSp: Int,
    onDraftChange: (String) -> Unit,
    onSetShowDueDateWheel: (Boolean) -> Unit,
    onSyncGcal: (TodoTask) -> Unit,
    onSave: (TodoTask) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTickPalette.current
    val monoFont = FontFamily.Monospace
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
        targetDt.format(DateTimeFormatter.ofPattern("EEE, d MMM HH:mm", Locale.ENGLISH))
    }

    if (showDueDateWheel) {
        AsciiSnoozeWheelPopup(
            taskTitle = parsedDraft.description.ifEmpty { target.description },
            initialDate = parsedDraft.dueDate,
            initialTime = parsedDraft.dueTime,
            fontSizeSp = fontSizeSp,
            isSnoozeMode = false,
            confirmLabel = "[+ Set Due]",
            onDismiss = { onSetShowDueDateWheel(false) },
            onConfirmDateTime = { chosenDate, chosenTime, _ ->
                val dStr = chosenDate.toString()
                val tStr = "%02d:%02d".format(chosenTime.hour, chosenTime.minute)
                var updated = TodoParser.parseLine(editingTaskDraft, target.lineNumber)
                updated = TodoParser.withUpdatedTag(updated, "due", dStr)
                updated = TodoParser.withUpdatedTag(updated, "duetime", tStr)
                if (updated.reminderSpecs.isEmpty()) {
                    updated = TodoParser.withReplacedMultiTag(updated, "rem", listOf(defaultTimedReminder))
                }
                onDraftChange(TodoParser.serializeLine(updated))
                onSetShowDueDateWheel(false)
            }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.scrim)
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bgSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(10.dp)
            ) {
                AsciiTitledTopBorder(
                    badgeText = "/ EDIT TASK",
                    borderColor = palette.reminder,
                    badgeColor = palette.reminder,
                    rightBadgeText = "X Close",
                    rightBadgeColor = palette.fgMuted,
                    onRightBadgeClick = onDismiss
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
                        onValueChange = onDraftChange,
                        textStyle = TextStyle(fontFamily = monoFont, color = palette.fg, fontSize = fontSizeSp.sp),
                        cursorBrush = SolidColor(palette.project),
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    )
                    Text(
                        text = " |",
                        color = palette.border,
                        fontSize = 12.sp,
                        fontFamily = monoFont
                    )
                }
                AsciiHorizontalBorder(corner = "|")

                if (quickTagChips.isNotEmpty()) {
                    AsciiBoxOptionRow(
                        label = "[+@ Tag]",
                        options = quickTagChips.map { it to it },
                        selectedValue = "",
                        onSelect = { tag -> onDraftChange(toggleTagInText(editingTaskDraft, tag)) },
                        activeColor = palette.project,
                        fontSizeSp = (fontSizeSp - 2).coerceAtLeast(11)
                    )
                    AsciiHorizontalBorder(corner = "|")
                }

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
                        onDraftChange(TodoParser.serializeLine(updated))
                    },
                    activeColor = when (currentPriKey) {
                        "A" -> palette.priorityA
                        "B" -> palette.priorityB
                        "C" -> palette.priorityC
                        else -> palette.fgMuted
                    },
                    fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12)
                )
                AsciiHorizontalBorder(corner = "|")

                AsciiBoxLeftRightRow(
                    left = "DUE: $formattedDateLine",
                    right = "$deltaStr [@ Pick Due]",
                    leftColor = palette.fg,
                    rightColor = palette.reminder,
                    fontSizeSp = fontSizeSp,
                    boldLeft = true,
                    boldRight = true,
                    onClick = { onSetShowDueDateWheel(true) }
                )
                AsciiHorizontalBorder(corner = "|")

                AsciiBoxActionRow(
                    actions = listOf(
                        Triple(if (isTargetGcalSynced) "[# Synced]" else "[# GCal]", palette.contextTag) {
                            onSyncGcal(TodoParser.parseLine(editingTaskDraft, target.lineNumber))
                        },
                        Triple("[+ Save]", palette.reminder) {
                            val reParsed = TodoParser.parseLine(editingTaskDraft, target.lineNumber).copy(isDirty = true)
                            onSave(reParsed)
                        },
                        Triple("[X Delete]", palette.priorityA, onDelete)
                    ),
                    fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12)
                )
                AsciiHorizontalBorder()
            }
        }
    }
}

@Composable
fun AddTaskDialog(
    smartInput: String,
    parsePreview: SmartAddParseResult?,
    addTaskPriority: Char?,
    dismissedInferredTags: Set<String>,
    quickTagChips: List<String>,
    showDueDateWheel: Boolean,
    currentNow: LocalDateTime,
    fontSizeSp: Int,
    inputFocusRequester: FocusRequester,
    onSmartInputChange: (String) -> Unit,
    onDismissInferredTag: (String) -> Unit,
    onDisableSpan: (String) -> Unit,
    onSelectPriority: (Char?) -> Unit,
    onSetShowDueDateWheel: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTickPalette.current
    val monoFont = FontFamily.Monospace

    if (showDueDateWheel) {
        AsciiSnoozeWheelPopup(
            taskTitle = smartInput.ifBlank { "New Task" },
            initialDate = parsePreview?.dueDate ?: currentNow.toLocalDate(),
            initialTime = parsePreview?.dueTime ?: LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0),
            fontSizeSp = fontSizeSp,
            isSnoozeMode = false,
            confirmLabel = "[+ Set Due]",
            onDismiss = { onSetShowDueDateWheel(false) },
            onConfirmDateTime = { chosenDate, chosenTime, _ ->
                val dStr = chosenDate.toString()
                val tStr = "%02d:%02d".format(chosenTime.hour, chosenTime.minute)
                val cleaned = smartInput
                    .replace(DUE_TAG_REGEX, "")
                    .replace(DUETIME_TAG_REGEX, "")
                    .trim()
                onSmartInputChange("$cleaned due:$dStr duetime:$tStr".trim())
                onSetShowDueDateWheel(false)
            }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.scrim)
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bgSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(10.dp)
            ) {
                AsciiTitledTopBorder(
                    badgeText = "+ ADD TASK",
                    borderColor = palette.reminder,
                    badgeColor = palette.reminder,
                    rightBadgeText = "X Close",
                    rightBadgeColor = palette.fgMuted,
                    onRightBadgeClick = onDismiss
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
                                text = "pay rent tomorrow 9am +home @phone...",
                                color = palette.fgMuted,
                                fontSize = 13.sp,
                                fontFamily = monoFont,
                                maxLines = 1
                            )
                        }
                        BasicTextField(
                            value = smartInput,
                            onValueChange = onSmartInputChange,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                            textStyle = TextStyle(
                                fontFamily = monoFont,
                                color = palette.fg,
                                fontSize = fontSizeSp.sp
                            ),
                            cursorBrush = SolidColor(palette.reminder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(inputFocusRequester)
                        )
                    }
                    Text(
                        text = " |",
                        color = palette.border,
                        fontSize = 12.sp,
                        fontFamily = monoFont
                    )
                }

                val liveAutoTokens = remember(parsePreview, dismissedInferredTags) {
                    if (parsePreview == null) emptyList()
                    else buildList {
                        parsePreview.projects.forEach { p ->
                            val tag = "+$p"
                            if (tag !in dismissedInferredTags) add("tag:$tag" to "$tag[x]")
                        }
                        parsePreview.contexts.forEach { c ->
                            val tag = "@$c"
                            if (tag !in dismissedInferredTags) add("tag:$tag" to "$tag[x]")
                        }
                        parsePreview.recognizedSpans.forEach { span ->
                            if (!span.resolvedToken.startsWith("+") && !span.resolvedToken.startsWith("@")) {
                                add("span:${span.id}" to "${span.resolvedToken}[x]")
                            }
                        }
                    }
                }

                if (liveAutoTokens.isNotEmpty()) {
                    AsciiHorizontalBorder(corner = "|")
                    AsciiBoxOptionRow(
                        label = "Auto",
                        options = liveAutoTokens,
                        selectedValue = "",
                        onSelect = { tokenKey ->
                            if (tokenKey.startsWith("tag:")) {
                                onDismissInferredTag(tokenKey.removePrefix("tag:"))
                            } else if (tokenKey.startsWith("span:")) {
                                onDisableSpan(tokenKey.removePrefix("span:"))
                            }
                        },
                        activeColor = palette.reminder,
                        fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12)
                    )
                }

                if (quickTagChips.isNotEmpty()) {
                    AsciiHorizontalBorder(corner = "|")
                    AsciiBoxOptionRow(
                        label = "[+@ Tag]",
                        options = quickTagChips.map { it to it },
                        selectedValue = "",
                        onSelect = { tag -> onSmartInputChange(toggleTagInText(smartInput, tag)) },
                        activeColor = palette.project,
                        fontSizeSp = (fontSizeSp - 2).coerceAtLeast(11)
                    )
                }

                AsciiHorizontalBorder(corner = "|")
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
                    onSelect = { onSelectPriority(it.firstOrNull()) },
                    activeColor = when (addPriKey) {
                        "A" -> palette.priorityA
                        "B" -> palette.priorityB
                        "C" -> palette.priorityC
                        else -> palette.fgMuted
                    },
                    fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12)
                )

                AsciiHorizontalBorder(corner = "|")
                val previewDateText = if (parsePreview?.dueDate != null) {
                    "${parsePreview.dueDate} ${parsePreview.dueTime ?: ""}".trim()
                } else {
                    "Due Date & Time (optional)"
                }
                AsciiBoxLeftRightRow(
                    left = "DUE: $previewDateText",
                    right = "[@ Pick Due]",
                    leftColor = palette.fg,
                    rightColor = palette.project,
                    fontSizeSp = fontSizeSp,
                    boldRight = true,
                    onClick = { onSetShowDueDateWheel(true) }
                )
                AsciiHorizontalBorder(corner = "|")

                AsciiBoxActionRow(
                    actions = listOf(
                        Triple("[X Close]", palette.fgMuted, onDismiss),
                        Triple("[+ Add]", palette.reminder, onSubmit)
                    ),
                    fontSizeSp = fontSizeSp
                )
                AsciiHorizontalBorder()
            }
        }
    }
}
