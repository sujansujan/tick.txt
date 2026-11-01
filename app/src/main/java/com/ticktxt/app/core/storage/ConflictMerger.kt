package com.ticktxt.app.core.storage

import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask

/**
 * Resolves external file changes (e.g. Syncthing, Dropbox, or external editor)
 * against pending in-app edits using a line-level 3-way merge keyed by task identity.
 *
 * Rules:
 * - Lines unchanged locally adopt remote changes.
 * - Lines unchanged remotely keep local changes.
 * - New lines added locally or remotely are both preserved.
 * - If the exact same task was modified differently both locally and remotely,
 *   both variants are preserved and `hasConflict` is flagged so a `.conflict` snapshot
 *   can also be written if desired.
 */
object ConflictMerger {

    data class MergeResult(
        val mergedText: String,
        val hasConflict: Boolean,
        val conflictLines: List<String> = emptyList()
    )

    fun mergeThreeWay(baseText: String, localText: String, remoteText: String): MergeResult {
        if (localText == remoteText) {
            return MergeResult(mergedText = localText, hasConflict = false)
        }
        if (baseText == localText) {
            return MergeResult(mergedText = remoteText, hasConflict = false)
        }
        if (baseText == remoteText) {
            return MergeResult(mergedText = localText, hasConflict = false)
        }

        val baseTasks = TodoParser.parseFile(baseText).associateBy { it.stableId }
        val localTasks = TodoParser.parseFile(localText)
        val remoteTasks = TodoParser.parseFile(remoteText)

        val localById = localTasks.associateBy { it.stableId }
        val remoteById = remoteTasks.associateBy { it.stableId }

        val orderedIds = linkedSetOf<String>()
        remoteTasks.forEach { orderedIds.add(it.stableId) }
        localTasks.forEach { orderedIds.add(it.stableId) }

        val mergedLines = mutableListOf<String>()
        val conflicts = mutableListOf<String>()

        for (id in orderedIds) {
            val baseLine = baseTasks[id]?.let { TodoParser.serializeLine(it) }
            val localLine = localById[id]?.let { TodoParser.serializeLine(it) }
            val remoteLine = remoteById[id]?.let { TodoParser.serializeLine(it) }

            when {
                localLine == remoteLine -> {
                    if (localLine != null) mergedLines.add(localLine)
                }
                baseLine == null -> {
                    // Added in one or both
                    if (remoteLine != null) mergedLines.add(remoteLine)
                    if (localLine != null && localLine != remoteLine) {
                        mergedLines.add(localLine)
                    }
                }
                localLine == baseLine -> {
                    // Modified or deleted remotely
                    if (remoteLine != null) mergedLines.add(remoteLine)
                }
                remoteLine == baseLine -> {
                    // Modified or deleted locally
                    if (localLine != null) mergedLines.add(localLine)
                }
                else -> {
                    // True conflict: modified differently in both local and remote
                    if (remoteLine != null) mergedLines.add(remoteLine)
                    if (localLine != null) {
                        mergedLines.add(localLine)
                        conflicts.add(localLine)
                    }
                }
            }
        }

        val finalContent = if (mergedLines.isEmpty()) "" else mergedLines.joinToString("\n") + "\n"
        return MergeResult(
            mergedText = finalContent,
            hasConflict = conflicts.isNotEmpty(),
            conflictLines = conflicts
        )
    }
}
