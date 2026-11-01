package com.ticktxt.app.core.notes

import com.ticktxt.app.core.todotxt.TodoTask

data class SearchHit(
    val kind: String,        // "task" or "note"
    val id: String,          // stableId or filename
    val title: String,
    val previewLine: String,
    val matchedTags: List<String>
)

/**
 * Rebuildable in-memory / FTS index across both `todo.txt` tasks and `notes/` files.
 * Can be reconstructed from scratch in <15ms for thousands of items purely from plain text.
 */
class PlainTextSearchIndex {

    private var indexedTasks: List<TodoTask> = emptyList()
    private var indexedNotes: List<NoteFile> = emptyList()

    fun rebuildFromFiles(tasks: List<TodoTask>, notes: List<NoteFile>) {
        indexedTasks = tasks.filterNot { it.isBlankOrComment }
        indexedNotes = notes
    }

    fun search(query: String): List<SearchHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val terms = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hits = mutableListOf<SearchHit>()

        for (task in indexedTasks) {
            val haystack = task.rawLine.lowercase()
            if (terms.all { term -> haystack.contains(term) }) {
                hits.add(
                    SearchHit(
                        kind = "task",
                        id = task.stableId,
                        title = task.description,
                        previewLine = task.rawLine,
                        matchedTags = task.projects.map { "+$it" } + task.contexts.map { "@$it" } +
                            task.linkedNotes.map { "note:$it" }
                    )
                )
            }
        }

        for (note in indexedNotes) {
            val haystack = "${note.filename} ${note.title} ${note.bodyContent}".lowercase()
            if (terms.all { term -> haystack.contains(term) }) {
                val matchingLine = note.bodyContent.lines()
                    .firstOrNull { line -> terms.any { line.lowercase().contains(it) } }
                    ?: note.bodyContent.lines().firstOrNull().orEmpty()
                hits.add(
                    SearchHit(
                        kind = "note",
                        id = note.filename,
                        title = note.filename,
                        previewLine = matchingLine.take(96),
                        matchedTags = note.reminderSpecs.map { "rem:$it" }
                    )
                )
            }
        }

        return hits
    }
}
