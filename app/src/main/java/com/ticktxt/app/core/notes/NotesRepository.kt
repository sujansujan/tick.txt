package com.ticktxt.app.core.notes

import com.ticktxt.app.core.reminders.ReminderSpec
import com.ticktxt.app.core.storage.AtomicFileWriter
import com.ticktxt.app.core.todotxt.TodoTask
import java.io.File

data class NoteFile(
    val filename: String,       // e.g. "work-ideas.md" or "packing-list.txt"
    val isMarkdown: Boolean,
    val title: String,
    val rawContent: String,
    val bodyContent: String,
    val reminderSpecs: List<String>,
    val linkedTaskIds: List<String>,
    val lastModifiedMs: Long
)

/**
 * Manages individual plain-text (`.txt`) and Markdown (`.md`) notes in `notes/`.
 *
 * Metadata convention (100% plain-text friendly):
 * - In `.md` files, optional first-line HTML comment: `<!-- tick: rem:2026-11-03@09:00/n -->`
 * - In `.txt` files, optional first-line directive: `@tick rem:2026-11-03@09:00/n`
 * If a note has no reminders, zero metadata lines are added—the file is pure user text.
 */
class NotesRepository(private val notesDir: File) {

    init {
        if (!notesDir.exists()) notesDir.mkdirs()
    }

    fun listNotes(): List<NoteFile> {
        val files = notesDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".md", ignoreCase = true) || f.name.endsWith(".txt", ignoreCase = true))
        } ?: return emptyList()

        return files.map { parseNoteFile(it) }.sortedBy { it.filename.lowercase() }
    }

    fun parseNoteFile(file: File): NoteFile {
        val raw = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        return parseNoteContent(file.name, raw, file.lastModified())
    }

    fun saveNote(
        filename: String,
        bodyContent: String,
        reminderSpecs: List<String> = emptyList()
    ): NoteFile {
        val safeName = toHumanReadableFilename(filename)
        val isMd = safeName.endsWith(".md", ignoreCase = true)
        val fullContent = buildString {
            if (reminderSpecs.isNotEmpty()) {
                val remTags = reminderSpecs.joinToString(" ") { "rem:$it" }
                if (isMd) {
                    appendLine("<!-- tick: $remTags -->")
                } else {
                    appendLine("@tick $remTags")
                }
            }
            append(bodyContent.trimEnd())
            appendLine()
        }
        val target = File(notesDir, safeName)
        AtomicFileWriter.writeAtomically(target, fullContent)
        return parseNoteFile(target)
    }

    /**
     * Finds tasks in `todo.txt` that reference `note:<filename>` so the note view
     * can display bi-directional backlinks.
     */
    fun findTasksLinkingToNote(filename: String, allTasks: List<TodoTask>): List<TodoTask> {
        return allTasks.filter { task ->
            task.linkedNotes.any { it.equals(filename, ignoreCase = true) }
        }
    }

    companion object {
        private val MD_META_REGEX = Regex("^<!--\\s*tick:\\s*(.+?)\\s*-->\\s*$")
        private val TXT_META_REGEX = Regex("^@tick\\s+(.+?)\\s*$")
        private val REM_TOKEN_REGEX = Regex("(?<=^|\\s)rem:([^\\s]+)")

        fun parseNoteContent(filename: String, raw: String, lastModifiedMs: Long = 0L): NoteFile {
            val isMd = filename.endsWith(".md", ignoreCase = true)
            val lines = raw.lines()
            val firstLine = lines.firstOrNull()?.trim() ?: ""

            val metaMatch = if (isMd) MD_META_REGEX.matchEntire(firstLine) else TXT_META_REGEX.matchEntire(firstLine)
            val reminders = mutableListOf<String>()
            val bodyLines: List<String>

            if (metaMatch != null) {
                val metaBody = metaMatch.groupValues[1]
                REM_TOKEN_REGEX.findAll(metaBody).forEach { m ->
                    val spec = m.groupValues[1]
                    if (ReminderSpec.parse(spec) != null) {
                        reminders.add(spec)
                    }
                }
                bodyLines = lines.drop(1)
            } else {
                bodyLines = lines
            }

            val body = bodyLines.joinToString("\n").trimStart('\n')
            val firstHeading = bodyLines.firstOrNull { it.isNotBlank() }
                ?.removePrefix("#")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: filename.substringBeforeLast('.')

            return NoteFile(
                filename = filename,
                isMarkdown = isMd,
                title = firstHeading,
                rawContent = raw,
                bodyContent = body,
                reminderSpecs = reminders,
                linkedTaskIds = emptyList(),
                lastModifiedMs = lastModifiedMs
            )
        }

        fun toHumanReadableFilename(input: String, defaultExt: String = ".md"): String {
            val trimmed = input.trim()
            val hasExt = trimmed.endsWith(".md", ignoreCase = true) || trimmed.endsWith(".txt", ignoreCase = true)
            val ext = if (hasExt) "." + trimmed.substringAfterLast('.').lowercase() else defaultExt
            val base = if (hasExt) trimmed.substringBeforeLast('.') else trimmed
            val slug = base.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "note" }
            return "$slug$ext"
        }
    }
}
