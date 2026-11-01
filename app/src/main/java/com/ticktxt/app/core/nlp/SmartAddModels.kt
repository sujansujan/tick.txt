package com.ticktxt.app.core.nlp

import java.time.LocalDate
import java.time.LocalTime

enum class SpanCategory {
    DATE_TIME,
    DURATION_RANGE,
    RECURRENCE,
    REMINDER,
    PRIORITY,
    PROJECT,
    CONTEXT,
    NOTE_MODE
}

/**
 * Represents a substring recognized by SmartAddParser so the UI can highlight it
 * inline and let the user tap once to edit or dismiss that inference.
 */
data class RecognizedSpan(
    val id: String,
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val category: SpanCategory,
    val resolvedToken: String, // e.g. "due:2026-11-03", "@home", "+family", "rem:-30m/n"
    val stripFromTitle: Boolean
)

data class SmartAddParseResult(
    val rawInput: String,
    val isNoteCapture: Boolean = false,
    val noteTitle: String? = null,
    val strippedTitle: String,
    val priority: Char? = null,
    val creationDate: LocalDate,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val thresholdDate: LocalDate? = null,
    val durationMinutes: Int? = null,
    val recurrence: String? = null,
    val projects: List<String> = emptyList(),
    val contexts: List<String> = emptyList(),
    val reminders: List<String> = emptyList(),
    val suppressReminder: Boolean = false,
    val passthroughTags: List<String> = emptyList(),
    val recognizedSpans: List<RecognizedSpan> = emptyList(),
    val formattedTodoLine: String
)
