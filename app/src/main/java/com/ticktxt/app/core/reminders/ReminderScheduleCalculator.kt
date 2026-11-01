package com.ticktxt.app.core.reminders

import com.ticktxt.app.core.todotxt.TodoTask
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class ScheduledReminderInstance(
    val reminderId: String,
    val taskStableId: String,
    val taskDescription: String,
    val spec: ReminderSpec,
    val triggerAt: ZonedDateTime,
    val isSnoozed: Boolean,
    val snoozeCount: Int,
    val maxSnoozes: Int
)

/**
 * Pure, deterministic calculator that resolves `TodoTask` + `ReminderSpec` + `SnoozeStateStore`
 * into concrete `ZonedDateTime` trigger instants across timezones and DST transitions.
 */
object ReminderScheduleCalculator {

    fun computeSchedulesForTask(
        task: TodoTask,
        now: ZonedDateTime,
        defaultMorningTime: LocalTime = LocalTime.of(9, 0),
        defaultMaxSnoozes: Int = 5,
        snoozeStore: SnoozeStateStore? = null
    ): List<ScheduledReminderInstance> {
        if (task.isCompleted || task.isBlankOrComment) return emptyList()

        val zone = now.zone
        val results = mutableListOf<ScheduledReminderInstance>()

        for (rawRem in task.reminderSpecs) {
            val spec = ReminderSpec.parse(rawRem) ?: continue
            val runtimeState = snoozeStore?.getState(task.stableId, rawRem)
            if (runtimeState?.acknowledged == true) continue

            val maxSnoozes = spec.maxSnoozes ?: defaultMaxSnoozes

            // 1. If currently snoozed, use the snoozed timestamp directly (never mutating due:)
            if (runtimeState?.snoozedUntilEpochMs != null) {
                val snoozedZdt = java.time.Instant.ofEpochMilli(runtimeState.snoozedUntilEpochMs).atZone(zone)
                results.add(
                    ScheduledReminderInstance(
                        reminderId = "${task.stableId}|$rawRem",
                        taskStableId = task.stableId,
                        taskDescription = task.description,
                        spec = spec,
                        triggerAt = snoozedZdt,
                        isSnoozed = true,
                        snoozeCount = runtimeState.snoozeCount,
                        maxSnoozes = maxSnoozes
                    )
                )
                continue
            }

            // 2. Otherwise compute base trigger from spec + task due date/time
            val baseLocalDateTime: LocalDateTime? = when (val t = spec.trigger) {
                is ReminderTrigger.AbsoluteDateTime -> t.dateTime
                is ReminderTrigger.AbsoluteTimeOnly -> {
                    val anchorDate = task.dueDate ?: run {
                        val todayCandidate = LocalDateTime.of(now.toLocalDate(), t.time)
                        if (todayCandidate.isAfter(now.toLocalDateTime())) now.toLocalDate()
                        else now.toLocalDate().plusDays(1)
                    }
                    LocalDateTime.of(anchorDate, t.time)
                }
                is ReminderTrigger.Relative -> {
                    val dueDate = task.dueDate ?: continue
                    val anchorTime = task.dueTime ?: defaultMorningTime
                    LocalDateTime.of(dueDate, anchorTime).plusMinutes(t.offsetMinutes)
                }
            }

            if (baseLocalDateTime != null) {
                val zonedTrigger = ZonedDateTime.of(baseLocalDateTime, zone)
                results.add(
                    ScheduledReminderInstance(
                        reminderId = "${task.stableId}|$rawRem",
                        taskStableId = task.stableId,
                        taskDescription = task.description,
                        spec = spec,
                        triggerAt = zonedTrigger,
                        isSnoozed = false,
                        snoozeCount = runtimeState?.snoozeCount ?: 0,
                        maxSnoozes = maxSnoozes
                    )
                )
            }
        }
        return results.sortedBy { it.triggerAt.toInstant().toEpochMilli() }
    }

    /**
     * Computes the target snooze timestamp for quick-snooze actions ("5m", "15m", "1h", "tomorrow_morning")
     * or custom minutes without modifying the task's due date.
     */
    fun computeSnoozeTarget(
        now: ZonedDateTime,
        preset: String,
        morningDefault: LocalTime = LocalTime.of(9, 0)
    ): ZonedDateTime {
        return when (preset.lowercase()) {
            "5m" -> now.plusMinutes(5)
            "15m" -> now.plusMinutes(15)
            "30m" -> now.plusMinutes(30)
            "1h", "60m" -> now.plusHours(1)
            "tomorrow_morning", "tomorrow" -> {
                val tomorrowDate = now.toLocalDate().plusDays(1)
                ZonedDateTime.of(tomorrowDate, morningDefault, now.zone)
            }
            else -> {
                val customMins = preset.removeSuffix("m").toLongOrNull() ?: 15L
                now.plusMinutes(customMins)
            }
        }
    }
}
