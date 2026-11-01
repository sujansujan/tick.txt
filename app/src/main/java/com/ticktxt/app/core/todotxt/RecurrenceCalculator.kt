package com.ticktxt.app.core.todotxt

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Supports community-standard todo.txt `rec:` tags:
 * - Flexible recurrence (`rec:1d`, `rec:2w`, `rec:1m`, `rec:1y`, `rec:3b`):
 *   Next due date is calculated from the completion date.
 * - Strict recurrence (`rec:+1d`, `rec:+2w`, `rec:+1m`, `rec:+1y`, `rec:+3b`):
 *   Next due date is calculated from the original `due:` date.
 */
object RecurrenceCalculator {

    data class RecurrenceRule(
        val isStrict: Boolean,
        val amount: Int,
        val unit: Char // 'd' days, 'b' business days, 'w' weeks, 'm' months, 'y' years
    )

    private val REC_REGEX = Regex("^(\\+)?([1-9]\\d*)([dbwmyDBWMY])$")

    fun parseRule(spec: String?): RecurrenceRule? {
        if (spec == null) return null
        val match = REC_REGEX.matchEntire(spec.trim()) ?: return null
        val isStrict = match.groupValues[1] == "+"
        val amount = match.groupValues[2].toIntOrNull() ?: return null
        val unit = match.groupValues[3].lowercase()[0]
        return RecurrenceRule(isStrict = isStrict, amount = amount, unit = unit)
    }

    fun advanceDate(baseDate: LocalDate, rule: RecurrenceRule): LocalDate {
        return when (rule.unit) {
            'd' -> baseDate.plusDays(rule.amount.toLong())
            'w' -> baseDate.plusWeeks(rule.amount.toLong())
            'm' -> baseDate.plusMonths(rule.amount.toLong())
            'y' -> baseDate.plusYears(rule.amount.toLong())
            'b' -> {
                var current = baseDate
                var remaining = rule.amount
                while (remaining > 0) {
                    current = current.plusDays(1)
                    if (current.dayOfWeek != DayOfWeek.SATURDAY && current.dayOfWeek != DayOfWeek.SUNDAY) {
                        remaining--
                    }
                }
                current
            }
            else -> baseDate.plusDays(rule.amount.toLong())
        }
    }

    fun spawnNextRecurringTask(completedSource: TodoTask, completionDate: LocalDate): TodoTask? {
        val rule = parseRule(completedSource.recurrenceSpec) ?: return null
        val currentDue = completedSource.dueDate
        val anchorDate = if (rule.isStrict && currentDue != null) currentDue else completionDate
        val nextDue = advanceDate(anchorDate, rule)

        var spawned = completedSource.copy(
            isCompleted = false,
            completionDate = null,
            creationDate = completionDate,
            isDirty = true
        )
        spawned = TodoParser.withUpdatedTag(spawned, "due", nextDue.toString())

        val currentThreshold = completedSource.thresholdDate
        if (currentThreshold != null) {
            val nextThreshold = if (currentDue != null) {
                val leadDays = ChronoUnit.DAYS.between(currentThreshold, currentDue)
                nextDue.minusDays(leadDays)
            } else {
                advanceDate(currentThreshold, rule)
            }
            spawned = TodoParser.withUpdatedTag(spawned, "t", nextThreshold.toString())
        }
        return spawned
    }
}
