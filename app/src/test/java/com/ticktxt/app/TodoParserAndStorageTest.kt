package com.ticktxt.app

import com.ticktxt.app.core.storage.ConflictMerger
import com.ticktxt.app.core.todotxt.RecurrenceCalculator
import com.ticktxt.app.core.todotxt.TodoParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class TodoParserAndStorageTest {

    @Test
    fun `parses full todo_txt line with priority, creation date, projects, contexts, and tags`() {
        val line = "(A) 2026-11-01 coffee with mum +family @home due:2026-11-03 duetime:14:00 rem:-15m/n custom:preserve_me"
        val task = TodoParser.parseLine(line)

        assertFalse(task.isCompleted)
        assertEquals('A', task.priority)
        assertEquals(LocalDate.of(2026, 11, 1), task.creationDate)
        assertEquals("coffee with mum", task.description)
        assertEquals(listOf("family"), task.projects)
        assertEquals(listOf("home"), task.contexts)
        assertEquals(LocalDate.of(2026, 11, 3), task.dueDate)
        assertEquals(LocalTime.of(14, 0), task.dueTime)
        assertEquals(listOf("-15m/n"), task.reminderSpecs)
        assertEquals("preserve_me", task.getTagValue("custom"))
    }

    @Test
    fun `lossless round-trip preserves unknown tokens, URLs, and math expressions`() {
        val trickyLines = listOf(
            "(B) 2026-11-01 check https://plaintextsports.com/ and 2+2=4 email@example.com +reading @web custom_tag:42",
            "x 2026-11-01 2026-10-29 file tax return +finance @desk due:2026-11-01 pri:A",
            "Task with no priority or creation date just plain text"
        )
        for (raw in trickyLines) {
            val parsed = TodoParser.parseLine(raw)
            assertEquals(raw, TodoParser.serializeLine(parsed))
        }
    }

    @Test
    fun `strict vs flexible recurrence advances due and threshold dates accurately`() {
        val strictLine = "2026-10-25 pay rent +home due:2026-11-01 t:2026-10-28 rec:+1m"
        val strictTask = TodoParser.parseLine(strictLine)
        val (_, nextStrict) = TodoParser.markCompleted(strictTask, LocalDate.of(2026, 11, 3))
        assertNotNull(nextStrict)
        assertEquals(LocalDate.of(2026, 12, 1), nextStrict!!.dueDate)
        assertEquals(LocalDate.of(2026, 11, 27), nextStrict.thresholdDate)

        val flexLine = "2026-10-25 water plants +home due:2026-11-01 rec:7d"
        val flexTask = TodoParser.parseLine(flexLine)
        val (_, nextFlex) = TodoParser.markCompleted(flexTask, LocalDate.of(2026, 11, 4))
        assertNotNull(nextFlex)
        assertEquals(LocalDate.of(2026, 11, 11), nextFlex!!.dueDate)
    }

    @Test
    fun `3-way merge resolves concurrent external Syncthing edits without data loss`() {
        val base = "2026-11-01 buy milk +groceries\n2026-11-01 call dentist +health\n"
        val local = "x 2026-11-01 2026-11-01 buy milk +groceries\n2026-11-01 call dentist +health\n"
        val remote = "2026-11-01 buy milk +groceries\n2026-11-01 call dentist +health\n2026-11-01 synced from desktop +work\n"

        val result = ConflictMerger.mergeThreeWay(base, local, remote)
        assertFalse(result.hasConflict)
        assertTrue(result.mergedText.contains("x 2026-11-01 2026-11-01 buy milk +groceries"))
        assertTrue(result.mergedText.contains("2026-11-01 synced from desktop +work"))
    }
}
