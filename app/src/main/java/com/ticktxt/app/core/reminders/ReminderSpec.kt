package com.ticktxt.app.core.reminders

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class ReminderType(val code: String) {
    NOTIFICATION("n"),
    ALARM("a"),
    SILENT("s");

    companion object {
        fun fromCode(code: String?): ReminderType = when (code?.lowercase()) {
            "a", "alarm" -> ALARM
            "s", "silent" -> SILENT
            else -> NOTIFICATION
        }
    }
}

sealed class ReminderTrigger {
    /** Relative offset before the task's due timestamp (e.g. -15m, -1d, 0m). */
    data class Relative(val offsetMinutes: Long, val rawUnitSpec: String) : ReminderTrigger()

    /** Specific local date and time (`2026-11-03@09:00`). */
    data class AbsoluteDateTime(val dateTime: LocalDateTime) : ReminderTrigger()

    /** Specific clock time (`09:00`) anchored to the task's due date (or today/tomorrow if no due date). */
    data class AbsoluteTimeOnly(val time: LocalTime) : ReminderTrigger()
}

/**
 * Parsed representation of a single `rem:` tag value.
 *
 * Format: `rem:<trigger>[/<type>][/<flags>]`
 * Examples:
 * - `rem:-15m` -> 15 min before due, notification, default sound+vibrate
 * - `rem:-1d/n` -> 1 day before due, notification
 * - `rem:07:00/a/svr` -> 07:00 alarm with sound, vibration, repeat-until-ack
 * - `rem:2026-11-03@14:00/a/svm3` -> exact timestamp alarm, max 3 snoozes
 */
data class ReminderSpec(
    val rawSpec: String,
    val trigger: ReminderTrigger,
    val type: ReminderType = ReminderType.NOTIFICATION,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val repeatUntilAck: Boolean = false,
    val maxSnoozes: Int? = null
) {
    fun toCanonicalSpec(): String {
        val triggerStr = when (val t = trigger) {
            is ReminderTrigger.Relative -> t.rawUnitSpec
            is ReminderTrigger.AbsoluteDateTime ->
                "${t.dateTime.toLocalDate()}@${"%02d:%02d".format(t.dateTime.hour, t.dateTime.minute)}"
            is ReminderTrigger.AbsoluteTimeOnly ->
                "%02d:%02d".format(t.time.hour, t.time.minute)
        }
        val flagStr = buildString {
            if (soundEnabled) append("s")
            if (vibrationEnabled) append("v")
            if (repeatUntilAck) append("r")
            if (maxSnoozes != null) append("m$maxSnoozes")
        }
        val defaultFlags = if (type == ReminderType.SILENT) "" else "sv"
        return if (type == ReminderType.NOTIFICATION && flagStr == defaultFlags && maxSnoozes == null) {
            triggerStr
        } else if (flagStr == defaultFlags && maxSnoozes == null) {
            "$triggerStr/${type.code}"
        } else {
            "$triggerStr/${type.code}/$flagStr"
        }
    }

    companion object {
        private val RELATIVE_REGEX = Regex("^(-?\\d+)([mhdwMHDW])$")
        private val ABS_DT_REGEX = Regex("^(\\d{4}-\\d{2}-\\d{2})@(\\d{2}:\\d{2})$")
        private val ABS_TIME_REGEX = Regex("^(\\d{2}:\\d{2})$")
        private val MAX_SNOOZE_REGEX = Regex("m(\\d+)")

        fun parse(rawValue: String): ReminderSpec? {
            val segments = rawValue.trim().split("/")
            if (segments.isEmpty() || segments[0].isBlank()) return null

            val triggerPart = segments[0]
            val trigger: ReminderTrigger = when {
                RELATIVE_REGEX.matches(triggerPart) -> {
                    val m = RELATIVE_REGEX.matchEntire(triggerPart)!!
                    val amount = m.groupValues[1].toLongOrNull() ?: return null
                    val unit = m.groupValues[2].lowercase()[0]
                    val mult = when (unit) {
                        'm' -> 1L
                        'h' -> 60L
                        'd' -> 1440L
                        'w' -> 10080L
                        else -> 1L
                    }
                    ReminderTrigger.Relative(amount * mult, triggerPart.lowercase())
                }
                ABS_DT_REGEX.matches(triggerPart) -> {
                    val m = ABS_DT_REGEX.matchEntire(triggerPart)!!
                    try {
                        val dt = LocalDate.parse(m.groupValues[1], DateTimeFormatter.ISO_LOCAL_DATE)
                        val tm = LocalTime.parse(m.groupValues[2])
                        ReminderTrigger.AbsoluteDateTime(LocalDateTime.of(dt, tm))
                    } catch (_: Exception) {
                        return null
                    }
                }
                ABS_TIME_REGEX.matches(triggerPart) -> {
                    try {
                        val tm = LocalTime.parse(triggerPart)
                        ReminderTrigger.AbsoluteTimeOnly(tm)
                    } catch (_: Exception) {
                        return null
                    }
                }
                else -> return null
            }

            val type = if (segments.size >= 2) ReminderType.fromCode(segments[1]) else ReminderType.NOTIFICATION
            val flagsSegment = if (segments.size >= 3) segments[2].lowercase() else null

            val sound = when {
                type == ReminderType.SILENT -> false
                flagsSegment == null -> true
                else -> 's' in flagsSegment
            }
            val vibrate = when {
                type == ReminderType.SILENT -> false
                flagsSegment == null -> true
                else -> 'v' in flagsSegment
            }
            val repeat = when {
                flagsSegment == null -> type == ReminderType.ALARM
                else -> 'r' in flagsSegment
            }
            val maxSnoozes = flagsSegment?.let {
                MAX_SNOOZE_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

            return ReminderSpec(
                rawSpec = rawValue,
                trigger = trigger,
                type = type,
                soundEnabled = sound,
                vibrationEnabled = vibrate,
                repeatUntilAck = repeat,
                maxSnoozes = maxSnoozes
            )
        }
    }
}
