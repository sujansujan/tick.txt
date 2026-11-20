package com.ticktxt.app.ui.ascii

import java.time.Duration
import java.time.LocalDateTime

object PlainTextSportsAscii {
    val ALARM_BELL_ART = listOf(
        "   .---.   ",
        "  /  |  \\  ",
        " |   |   | ",
        " |  / \\  | ",
        " /--===--\\ ",
        "    'v'    "
    ).joinToString("\n")

    val TROPHY_ART = listOf(
        "  .---.  ",
        " | \\ / | ",
        "  '---'  ",
        "   _|_   "
    ).joinToString("\n")

    fun formatRelativeDelta(now: LocalDateTime, target: LocalDateTime): String {
        val dur = Duration.between(now, target)
        val totalSec = dur.seconds
        if (totalSec < -60) {
            val overdueMin = (-totalSec) / 60
            return when {
                overdueMin >= 1440 -> "-${overdueMin / 1440}d"
                overdueMin >= 60 -> "-${overdueMin / 60}h"
                else -> "-${overdueMin}m"
            }
        }
        if (totalSec <= 0) return "now"
        val days = totalSec / 86400
        val hours = (totalSec % 86400) / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return when {
            days > 0 && hours > 0 -> "${days}d ${hours}h"
            days > 0 -> "${days}d"
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            mins > 0 -> "${mins}m"
            else -> "${secs}s"
        }
    }
}
