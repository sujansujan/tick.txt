package com.ticktxt.app.core.todotxt

import java.time.LocalDate
import java.time.LocalTime

/**
 * Represents a single token in a todo.txt line so unknown tokens and exact positions
 * can round-trip without loss or corruption.
 */
sealed class RawToken {
    abstract val rawText: String

    data class Word(override val rawText: String) : RawToken()
    data class Project(val name: String, override val rawText: String = "+$name") : RawToken()
    data class ContextTag(val name: String, override val rawText: String = "@$name") : RawToken()
    data class KeyValue(
        val key: String,
        val value: String,
        override val rawText: String = "$key:$value"
    ) : RawToken()
}

/**
 * Immutable representation of a single line in todo.txt or done.txt.
 * Follows https://github.com/todotxt/todo.txt strictly.
 */
data class TodoTask(
    val lineNumber: Int,
    val rawLine: String,
    val isCompleted: Boolean = false,
    val priority: Char? = null,
    val completionDate: LocalDate? = null,
    val creationDate: LocalDate? = null,
    val bodyTokens: List<RawToken> = emptyList(),
    val isDirty: Boolean = false
) {
    val isBlankOrComment: Boolean
        get() = rawLine.isBlank()

    /** Human-readable task description excluding +project, @context, and known metadata tags. */
    val description: String
        get() = bodyTokens.filterIsInstance<RawToken.Word>().joinToString(" ") { it.rawText }

    /** Full body string including inline projects, contexts, and tags. */
    val fullBody: String
        get() = bodyTokens.joinToString(" ") { it.rawText }

    val projects: List<String>
        get() = bodyTokens.filterIsInstance<RawToken.Project>().map { it.name }.distinct()

    val contexts: List<String>
        get() = bodyTokens.filterIsInstance<RawToken.ContextTag>().map { it.name }.distinct()

    val keyValues: List<RawToken.KeyValue>
        get() = bodyTokens.filterIsInstance<RawToken.KeyValue>()

    fun getTagValue(key: String): String? =
        keyValues.firstOrNull { it.key.equals(key, ignoreCase = false) }?.value

    fun getTagValues(key: String): List<String> =
        keyValues.filter { it.key.equals(key, ignoreCase = false) }.map { it.value }

    val dueDate: LocalDate?
        get() = getTagValue("due")?.let { TodoParser.parseDateOrNull(it.substringBefore('T')) }

    val dueTime: LocalTime?
        get() {
            getTagValue("duetime")?.let { TodoParser.parseTimeOrNull(it) }?.let { return it }
            val dueRaw = getTagValue("due") ?: return null
            if ('T' in dueRaw) {
                return TodoParser.parseTimeOrNull(dueRaw.substringAfter('T'))
            }
            return null
        }

    val thresholdDate: LocalDate?
        get() = getTagValue("t")?.let { TodoParser.parseDateOrNull(it) }

    val recurrenceSpec: String?
        get() = getTagValue("rec")

    val reminderSpecs: List<String>
        get() = getTagValues("rem")

    val linkedNotes: List<String>
        get() = getTagValues("note")

    /**
     * Stable content identity hash (ignoring completion status and line number)
     * used for 3-way conflict merging and snooze state binding.
     */
    val stableId: String
        get() {
            val core = buildString {
                append(creationDate?.toString() ?: "")
                append("|")
                append(description.lowercase().trim())
                append("|")
                append(projects.sorted().joinToString(","))
                append("|")
                append(contexts.sorted().joinToString(","))
            }
            return TodoParser.sha256Short(core)
        }
}
