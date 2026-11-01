package com.ticktxt.app

import com.ticktxt.app.core.reminders.*
import com.ticktxt.app.core.todotxt.TodoParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderEngineTest {

    @Test
    fun `parses relative, absolute date-time, and time-only reminder specs with flags`() {
        val rel = ReminderSpec.parse("-30m/n")!!
        assertEquals(ReminderType.NOTIFICATION, rel.type)
        assertTrue(rel.trigger is ReminderTrigger.Relative)
        assertEquals(-30L, (rel.trigger as ReminderTrigger.Relative).offsetMinutes)

        val alarm = ReminderSpec.parse("07:00/a/svrm3")!!
        assertEquals(ReminderType.ALARM, alarm.type)
        assertTrue(alarm.soundEnabled)
        assertTrue(alarm.vibrationEnabled)
        assertTrue(alarm.repeatUntilAck)
        assertEquals(3, alarm.maxSnoozes)

        val absDt = ReminderSpec.parse("2026-11-03@14:00/s")!!
        assertEquals(ReminderType.SILENT, absDt.type)
        assertFalse(absDt.soundEnabled)
        assertFalse(absDt.vibrationEnabled)
    }

    @Test
    fun `computes multiple reminders per task and respects snooze state without altering due date`(@TempDir tempDir: File) {
        val stateFile = File(tempDir, "alarm-state.json")
        val snoozeStore = SnoozeStateStore(stateFile)
        val zone = ZoneId.of("Asia/Kathmandu")
        val now = ZonedDateTime.of(2026, 11, 1, 19, 0, 0, 0, zone)

        val line = "2026-11-01 coffee with mum +family @home due:2026-11-03 duetime:14:00 rem:-1d/n rem:-30m/a/svm2"
        val task = TodoParser.parseLine(line)

        val initialSchedules = ReminderScheduleCalculator.computeSchedulesForTask(
            task = task,
            now = now,
            snoozeStore = snoozeStore
        )
        assertEquals(2, initialSchedules.size)
        assertEquals(ZonedDateTime.of(2026, 11, 2, 14, 0, 0, 0, zone), initialSchedules[0].triggerAt)
        assertEquals(ZonedDateTime.of(2026, 11, 3, 13, 30, 0, 0, zone), initialSchedules[1].triggerAt)

        // Snooze the second reminder by 15 minutes
        val snoozedTarget = ReminderScheduleCalculator.computeSnoozeTarget(initialSchedules[1].triggerAt, "15m")
        val allowed1 = snoozeStore.recordSnooze(task.stableId, "-30m/a/svm2", snoozedTarget.toInstant().toEpochMilli(), 2)
        assertTrue(allowed1)

        // Reload store from disk to verify survival across process death / reboot
        val reloadedStore = SnoozeStateStore(stateFile)
        val afterSnooze = ReminderScheduleCalculator.computeSchedulesForTask(
            task = task,
            now = now,
            snoozeStore = reloadedStore
        )
        assertEquals(ZonedDateTime.of(2026, 11, 3, 13, 45, 0, 0, zone), afterSnooze[1].triggerAt)
        assertTrue(afterSnooze[1].isSnoozed)
        assertEquals(1, afterSnooze[1].snoozeCount)

        // Verify task's due date and raw line are completely untouched
        assertEquals(LocalDate.of(2026, 11, 3), task.dueDate)
        assertEquals(LocalTime.of(14, 0), task.dueTime)
        assertEquals(line, TodoParser.serializeLine(task))
    }
}
