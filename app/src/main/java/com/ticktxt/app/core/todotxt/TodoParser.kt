package com.ticktxt.app.core.todotxt

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Strict, lossless parser and serializer for todo.txt lines.
 *
 * Spec guarantees:
 * 1. Completed tasks begin with `x ` (lowercase x + space).
 * 2. Priority is uppercase `(A)`-`(Z)` followed by space at start of incomplete task.
 * 3. Optional completion date (`YYYY-MM-DD`) immediately follows `x `.
 * 4. Optional creation date (`YYYY-MM-DD`) follows priority (or `x YYYY-MM-DD `).
 * 5. Unknown tokens, custom `key:value` metadata, URLs, and arbitrary ordering round-trip untouched.
 */
object TodoParser {

    private val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val PRIORITY_REGEX = Regex("^\\(([A-Z])\\)$")
    private val KEY_VALUE_REGEX = Regex("^([A-Za-z][A-Za-z0-9_-]*):([^\\s:]+.*)$")
    private val URL_SCHEMES = setOf("http", "https", "mailto", "ftp", "file", "tel", "geo")

    fun parseDateOrNull(text: String): LocalDate? {
        if (!DATE_REGEX.matches(text)) return null
        return try {
            LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun parseTimeOrNull(text: String): LocalTime? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    fun parseLine(rawLine: String, lineNumber: Int = 1): TodoTask {
        val stripped = rawLine.trimEnd('\r', '\n')
        if (stripped.isBlank()) {
            return TodoTask(lineNumber = lineNumber, rawLine = stripped)
        }

        val tokens = stripped.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return TodoTask(lineNumber = lineNumber, rawLine = stripped)
        }

        var idx = 0
        var isCompleted = false
        var priority: Char? = null
        var completionDate: LocalDate? = null
        var creationDate: LocalDate? = null

        // Rule 1: Completed tasks start with lowercase "x" token
        if (tokens[idx] == "x" && stripped.startsWith("x ")) {
            isCompleted = true
            idx++
            // Check for completion date (and optional creation date after it)
            if (idx < tokens.size) {
                val firstDate = parseDateOrNull(tokens[idx])
                if (firstDate != null) {
                    completionDate = firstDate
                    idx++
                    if (idx < tokens.size) {
                        val secondDate = parseDateOrNull(tokens[idx])
                        if (secondDate != null) {
                            creationDate = secondDate
                            idx++
                        }
                    }
                }
            }
        } else {
            // Incomplete task: check optional priority (A)-(Z)
            val priMatch = PRIORITY_REGEX.matchEntire(tokens[idx])
            if (priMatch != null) {
                priority = priMatch.groupValues[1][0]
                idx++
            }
            // Check optional creation date
            if (idx < tokens.size) {
                val maybeCreation = parseDateOrNull(tokens[idx])
                if (maybeCreation != null) {
                    creationDate = maybeCreation
                    idx++
                }
            }
        }

        val bodyTokens = mutableListOf<RawToken>()
        while (idx < tokens.size) {
            bodyTokens.add(classifyBodyToken(tokens[idx]))
            idx++
        }

        return TodoTask(
            lineNumber = lineNumber,
            rawLine = stripped,
            isCompleted = isCompleted,
            priority = priority,
            completionDate = completionDate,
            creationDate = creationDate,
            bodyTokens = bodyTokens,
            isDirty = false
        )
    }

    fun classifyBodyToken(token: String): RawToken {
        if (token.length > 1 && token.startsWith("+") && !token.substring(1).startsWith("+")) {
            return RawToken.Project(name = token.substring(1), rawText = token)
        }
        if (token.length > 1 && token.startsWith("@") && !token.substring(1).startsWith("@")) {
            return RawToken.ContextTag(name = token.substring(1), rawText = token)
        }
        val kvMatch = KEY_VALUE_REGEX.matchEntire(token)
        if (kvMatch != null) {
            val key = kvMatch.groupValues[1]
            val value = kvMatch.groupValues[2]
            if (key.lowercase() !in URL_SCHEMES && !value.startsWith("//")) {
                return RawToken.KeyValue(key = key, value = value, rawText = token)
            }
        }
        return RawToken.Word(token)
    }

    /**
     * Serializes a TodoTask back to a valid todo.txt line.
     * If the task was never modified (`!isDirty`), returns `rawLine` verbatim so
     * extra spaces or unknown formatting round-trip untouched.
     */
    fun serializeLine(task: TodoTask): String {
        if (!task.isDirty) {
            return task.rawLine
        }
        val parts = mutableListOf<String>()
        if (task.isCompleted) {
            parts.add("x")
            task.completionDate?.let { parts.add(it.toString()) }
            task.creationDate?.let { parts.add(it.toString()) }
        } else {
            task.priority?.let { parts.add("($it)") }
            task.creationDate?.let { parts.add(it.toString()) }
        }
        for (token in task.bodyTokens) {
            parts.add(token.rawText)
        }
        return parts.joinToString(" ")
    }

    fun parseFile(content: String): List<TodoTask> {
        if (content.isEmpty()) return emptyList()
        return content.lines()
            .let { lines ->
                // Drop trailing empty string introduced by trailing newline
                if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            }
            .mapIndexed { index, line -> parseLine(line, index + 1) }
    }

    fun serializeFile(tasks: List<TodoTask>): String {
        if (tasks.isEmpty()) return ""
        return tasks.joinToString("\n") { serializeLine(it) } + "\n"
    }

    /**
     * Updates or inserts a single-value key:value tag (e.g. `due`, `duetime`, `t`, `rec`)
     * in place without disturbing token order.
     */
    fun withUpdatedTag(task: TodoTask, key: String, newValue: String?): TodoTask {
        val updated = mutableListOf<RawToken>()
        var replaced = false
        for (token in task.bodyTokens) {
            if (token is RawToken.KeyValue && token.key == key) {
                if (!replaced && newValue != null) {
                    updated.add(RawToken.KeyValue(key, newValue))
                    replaced = true
                }
            } else {
                updated.add(token)
            }
        }
        if (!replaced && newValue != null) {
            updated.add(RawToken.KeyValue(key, newValue))
        }
        return task.copy(bodyTokens = updated, isDirty = true)
    }

    /**
     * Replaces all multi-value tags for `key` (such as `rem:`) with `newValues`.
     */
    fun withReplacedMultiTag(task: TodoTask, key: String, newValues: List<String>): TodoTask {
        val filtered = task.bodyTokens.filterNot { it is RawToken.KeyValue && it.key == key }.toMutableList()
        for (v in newValues) {
            filtered.add(RawToken.KeyValue(key, v))
        }
        return task.copy(bodyTokens = filtered, isDirty = true)
    }

    /**
     * Marks a task completed on `completedOn`. Preserves original priority in `pri:X`
     * per official todo.txt convention so unchecking restores `(X)`.
     */
    fun markCompleted(
        task: TodoTask,
        completedOn: LocalDate
    ): Pair<TodoTask, TodoTask?> {
        if (task.isCompleted) return task to null
        val newTokens = task.bodyTokens.toMutableList()
        if (task.priority != null && task.getTagValue("pri") == null) {
            newTokens.add(RawToken.KeyValue("pri", task.priority.toString()))
        }
        val completedTask = task.copy(
            isCompleted = true,
            priority = null,
            completionDate = completedOn,
            // Per spec: creationDate is only written after completionDate if creationDate existed
            bodyTokens = newTokens,
            isDirty = true
        )
        val nextRecurring = RecurrenceCalculator.spawnNextRecurringTask(task, completedOn)
        return completedTask to nextRecurring
    }

    fun markIncomplete(task: TodoTask): TodoTask {
        if (!task.isCompleted) return task
        val restoredPriority = task.priority ?: task.getTagValue("pri")?.firstOrNull()
        val cleanedTokens = task.bodyTokens.filterNot { it is RawToken.KeyValue && it.key == "pri" }
        return task.copy(
            isCompleted = false,
            priority = restoredPriority,
            completionDate = null,
            bodyTokens = cleanedTokens,
            isDirty = true
        )
    }

    fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
