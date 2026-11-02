package com.ticktxt.app.core.reminders

import com.ticktxt.app.core.todotxt.TodoTask
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.roundToLong

enum class SnoozeUnit(
    val code: String,
    val asciiLabel: String,
    val fullName: String,
    val quickValues: List<Int>
) {
    SECONDS("s", "SEC", "Seconds", listOf(5, 10, 15, 30, 45, 60)),
    MINUTES("m", "MIN", "Minutes", listOf(1, 5, 10, 15, 30, 45)),
    HOURS("h", "HR", "Hours", listOf(1, 2, 3, 4, 6, 12)),
    DAYS("d", "DAY", "Days", listOf(1, 2, 3, 5, 7, 14)),
    WEEKS("w", "WK", "Weeks", listOf(1, 2, 3, 4, 6, 8)),
    MONTHS("mo", "MO", "Months", listOf(1, 2, 3, 6, 9, 12));

    fun toPresetSpec(amount: Int): String = "${amount.coerceAtLeast(1)}$code"

    companion object {
        fun fromCode(raw: String?): SnoozeUnit = when (raw?.lowercase()?.trim()) {
            "s", "sec", "secs", "second", "seconds" -> SECONDS
            "m", "min", "mins", "minute", "minutes" -> MINUTES
            "h", "hr", "hrs", "hour", "hours" -> HOURS
            "d", "day", "days" -> DAYS
            "w", "wk", "wks", "week", "weeks" -> WEEKS
            "mo", "mos", "month", "months" -> MONTHS
            else -> MINUTES
        }
    }
}

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
 * Supports full granular snooze across Seconds, Minutes, Hours, Days, Weeks, and Months.
 */
object ReminderScheduleCalculator {

    private val GRANULAR_SNOOZE_REGEX = Regex(
        "^\\+?(\\d+(?:\\.\\d+)?)\\s*(mo|mos|months?|wks?|weeks?|w|days?|d|hrs?|hours?|h|mins?|minutes?|m|secs?|seconds?|s)$",
        RegexOption.IGNORE_CASE
    )

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
     * Computes target snooze timestamp for any unit:
     * Seconds ("10s", "30sec"), Minutes ("5m", "15m"), Hours ("1h", "1.5h"),
     * Days ("1d", "3d"), Weeks ("1w", "2w"), Months ("1mo", "3mo"), or "tomorrow_morning".
     */
    fun computeSnoozeTarget(
        now: ZonedDateTime,
        preset: String,
        morningDefault: LocalTime = LocalTime.of(9, 0)
    ): ZonedDateTime {
        val cleaned = preset.trim().lowercase()
        if (cleaned == "tomorrow_morning" || cleaned == "tomorrow") {
            val tomorrowDate = now.toLocalDate().plusDays(1)
            return ZonedDateTime.of(tomorrowDate, morningDefault, now.zone)
        }

        val match = GRANULAR_SNOOZE_REGEX.matchEntire(cleaned)
        if (match != null) {
            val amount = match.groupValues[1].toDoubleOrNull() ?: 5.0
            val unitToken = match.groupValues[2].lowercase()
            return computeGranularSnoozeTarget(now, amount, SnoozeUnit.fromCode(unitToken))
        }

        val fallbackMins = cleaned.removeSuffix("m").toLongOrNull() ?: 5L
        return now.plusMinutes(fallbackMins)
    }

    fun computeGranularSnoozeTarget(
        now: ZonedDateTime,
        amount: Double,
        unit: SnoozeUnit
    ): ZonedDateTime {
        val safeAmount = amount.coerceAtLeast(0.1)
        return when (unit) {
            SnoozeUnit.SECONDS -> now.plusSeconds(safeAmount.roundToLong().coerceAtLeast(1L))
            SnoozeUnit.MINUTES -> now.plusSeconds((safeAmount * 60.0).roundToLong().coerceAtLeast(1L))
            SnoozeUnit.HOURS -> now.plusSeconds((safeAmount * 3600.0).roundToLong().coerceAtLeast(1L))
            SnoozeUnit.DAYS -> {
                if (safeAmount % 1.0 == 0.0) now.plusDays(safeAmount.toLong())
                else now.plusSeconds((safeAmount * 86400.0).roundToLong())
            }
            SnoozeUnit.WEEKS -> {
                if (safeAmount % 1.0 == 0.0) now.plusWeeks(safeAmount.toLong())
                else now.plusSeconds((safeAmount * 604800.0).roundToLong())
            }
            SnoozeUnit.MONTHS -> {
                val wholeMonths = safeAmount.toLong()
                val extraDays = ((safeAmount - wholeMonths) * 30.0).roundToLong()
                now.plusMonths(wholeMonths).plusDays(extraDays)
            }
        }
    }
}
