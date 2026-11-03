package com.ticktxt.app.core.notes

import com.ticktxt.app.core.reminders.ReminderSpec
import com.ticktxt.app.core.storage.AtomicFileWriter
import com.ticktxt.app.core.todotxt.TodoTask
import java.io.File

data class NoteFile(
    val filename: String,       // Always ".md", e.g. "architecture.md" or "packing-list.md"
    val isMarkdown: Boolean = true,
    val title: String,
    val rawContent: String,
    val bodyContent: String,
    val reminderSpecs: List<String>,
    val linkedTaskIds: List<String>,
    val lastModifiedMs: Long
)

/**
 * Manages Markdown-only (`.md`) notes in `notes/`.
 *
 * Metadata convention (100% plain-text Markdown friendly):
 * - Optional first-line HTML comment: `<!-- tick: rem:2026-11-03@09:00/n -->`
 * - Automatically migrates any legacy `.txt` note in `notes/` to `.md`.
 */
class NotesRepository(private val notesDir: File) {

    init {
        if (!notesDir.exists()) notesDir.mkdirs()
    }

    fun listNotes(): List<NoteFile> {
        notesDir.listFiles { f -> f.isFile && f.name.endsWith(".txt", ignoreCase = true) }?.forEach { legacyTxt ->
            runCatching {
                val mdName = toHumanReadableFilename(legacyTxt.nameWithoutExtension, ".md")
                val mdTarget = File(notesDir, mdName)
                if (!mdTarget.exists()) {
                    val content = legacyTxt.readText(Charsets.UTF_8)
                    AtomicFileWriter.writeAtomically(mdTarget, content)
                }
                legacyTxt.delete()
            }
        }

        val files = notesDir.listFiles { f ->
            f.isFile && f.name.endsWith(".md", ignoreCase = true)
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
        val safeName = toHumanReadableFilename(filename, ".md")
        val fullContent = buildString {
            if (reminderSpecs.isNotEmpty()) {
                val remTags = reminderSpecs.joinToString(" ") { "rem:$it" }
                appendLine("<!-- tick: $remTags -->")
            }
            append(bodyContent.trimEnd())
            appendLine()
        }
        val target = File(notesDir, safeName)
        AtomicFileWriter.writeAtomically(target, fullContent)
        return parseNoteFile(target)
    }

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
            val safeMdName = toHumanReadableFilename(filename, ".md")
            val lines = raw.lines()
            val firstLine = lines.firstOrNull()?.trim() ?: ""

            val metaMatch = MD_META_REGEX.matchEntire(firstLine) ?: TXT_META_REGEX.matchEntire(firstLine)
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
                ?.removePrefix("###")
                ?.removePrefix("##")
                ?.removePrefix("#")
                ?.removePrefix("- [ ]")
                ?.removePrefix("- [x]")
                ?.removePrefix("- [X]")
                ?.removePrefix("-")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: safeMdName.substringBeforeLast('.')

            return NoteFile(
                filename = safeMdName,
                isMarkdown = true,
                title = firstHeading,
                rawContent = raw,
                bodyContent = body,
                reminderSpecs = reminders,
                linkedTaskIds = emptyList(),
                lastModifiedMs = lastModifiedMs
            )
        }

        fun toHumanReadableFilename(input: String, @Suppress("UNUSED_PARAMETER") defaultExt: String = ".md"): String {
            val trimmed = input.trim()
            val hasExt = trimmed.endsWith(".md", ignoreCase = true) || trimmed.endsWith(".txt", ignoreCase = true)
            val base = if (hasExt) trimmed.substringBeforeLast('.') else trimmed
            val slug = base.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "note" }
            return "$slug.md"
        }
    }
}
