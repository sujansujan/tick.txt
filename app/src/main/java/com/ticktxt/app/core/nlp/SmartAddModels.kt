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

enum class TagKind {
    PROJECT,
    CONTEXT
}

data class TagSuggestion(
    val tag: String,           // e.g. "+work", "@phone"
    val kind: TagKind,
    val score: Double,         // 0.0 .. 1.0+ calibrated confidence
    val reason: String,        // concise explanation e.g. "rule:invoice", "history:3x", "verb:call"
    val autoApplied: Boolean
)

data class RecognizedSpan(
    val id: String,
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val category: SpanCategory,
    val resolvedToken: String,
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
    val exactOffsetMillis: Long? = null,
    val thresholdDate: LocalDate? = null,
    val durationMinutes: Int? = null,
    val recurrence: String? = null,
    val projects: List<String> = emptyList(),
    val contexts: List<String> = emptyList(),
    val reminders: List<String> = emptyList(),
    val suppressReminder: Boolean = false,
    val passthroughTags: List<String> = emptyList(),
    val recognizedSpans: List<RecognizedSpan> = emptyList(),
    val suggestedTags: List<TagSuggestion> = emptyList(),
    val formattedTodoLine: String
)
