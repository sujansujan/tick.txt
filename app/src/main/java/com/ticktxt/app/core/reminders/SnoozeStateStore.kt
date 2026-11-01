package com.ticktxt.app.core.reminders

import com.ticktxt.app.core.storage.AtomicFileWriter
import java.io.File
import java.time.Instant

/**
 * Ephemeral reminder state persisted in `.tick/alarm-state.json`.
 * Keeps snooze timestamps, snooze counts, and acknowledged flags across app kills
 * and device reboots WITHOUT altering `due:` or polluting `todo.txt`.
 */
data class ReminderRuntimeState(
    val reminderKey: String,          // "<taskStableId>|<rawRemSpec>"
    val taskStableId: String,
    val rawRemSpec: String,
    val snoozedUntilEpochMs: Long? = null,
    val snoozeCount: Int = 0,
    val lastFiredEpochMs: Long? = null,
    val acknowledged: Boolean = false
)

class SnoozeStateStore(private val stateFile: File) {

    private val entries = mutableMapOf<String, ReminderRuntimeState>()

    init {
        reload()
    }

    @Synchronized
    fun reload() {
        entries.clear()
        if (!stateFile.exists()) return
        try {
            val lines = stateFile.readLines(Charsets.UTF_8)
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split("\t")
                if (parts.size >= 6) {
                    val key = "${parts[0]}|${parts[1]}"
                    entries[key] = ReminderRuntimeState(
                        reminderKey = key,
                        taskStableId = parts[0],
                        rawRemSpec = parts[1],
                        snoozedUntilEpochMs = parts[2].toLongOrNull(),
                        snoozeCount = parts[3].toIntOrNull() ?: 0,
                        lastFiredEpochMs = parts[4].toLongOrNull(),
                        acknowledged = parts[5].toBooleanStrictOrNull() ?: false
                    )
                }
            }
        } catch (_: Exception) {
            // Rebuildable state: ignore corrupt lines safely
        }
    }

    @Synchronized
    fun getState(taskStableId: String, rawRemSpec: String): ReminderRuntimeState? =
        entries["$taskStableId|$rawRemSpec"]

    @Synchronized
    fun recordSnooze(
        taskStableId: String,
        rawRemSpec: String,
        snoozeUntilEpochMs: Long,
        maxSnoozesAllowed: Int
    ): Boolean {
        val key = "$taskStableId|$rawRemSpec"
        val current = entries[key]
        val currentCount = current?.snoozeCount ?: 0
        if (currentCount >= maxSnoozesAllowed) {
            return false // Max snooze limit reached
        }
        entries[key] = ReminderRuntimeState(
            reminderKey = key,
            taskStableId = taskStableId,
            rawRemSpec = rawRemSpec,
            snoozedUntilEpochMs = snoozeUntilEpochMs,
            snoozeCount = currentCount + 1,
            lastFiredEpochMs = current?.lastFiredEpochMs ?: Instant.now().toEpochMilli(),
            acknowledged = false
        )
        persist()
        return true
    }

    @Synchronized
    fun recordFired(taskStableId: String, rawRemSpec: String, firedAtEpochMs: Long) {
        val key = "$taskStableId|$rawRemSpec"
        val current = entries[key]
        entries[key] = ReminderRuntimeState(
            reminderKey = key,
            taskStableId = taskStableId,
            rawRemSpec = rawRemSpec,
            snoozedUntilEpochMs = null,
            snoozeCount = current?.snoozeCount ?: 0,
            lastFiredEpochMs = firedAtEpochMs,
            acknowledged = false
        )
        persist()
    }

    @Synchronized
    fun acknowledgeAndDismiss(taskStableId: String, rawRemSpec: String) {
        val key = "$taskStableId|$rawRemSpec"
        val current = entries[key]
        entries[key] = ReminderRuntimeState(
            reminderKey = key,
            taskStableId = taskStableId,
            rawRemSpec = rawRemSpec,
            snoozedUntilEpochMs = null,
            snoozeCount = current?.snoozeCount ?: 0,
            lastFiredEpochMs = current?.lastFiredEpochMs ?: Instant.now().toEpochMilli(),
            acknowledged = true
        )
        persist()
    }

    @Synchronized
    fun clearForTask(taskStableId: String) {
        val toRemove = entries.keys.filter { it.startsWith("$taskStableId|") }
        toRemove.forEach { entries.remove(it) }
        persist()
    }

    @Synchronized
    private fun persist() {
        val content = buildString {
            appendLine("# tick.txt rebuildable alarm runtime state (TSV)")
            for (state in entries.values) {
                append(state.taskStableId).append('\t')
                append(state.rawRemSpec).append('\t')
                append(state.snoozedUntilEpochMs?.toString() ?: "").append('\t')
                append(state.snoozeCount).append('\t')
                append(state.lastFiredEpochMs?.toString() ?: "").append('\t')
                appendLine(state.acknowledged)
            }
        }
        AtomicFileWriter.writeAtomically(stateFile, content)
    }
}
