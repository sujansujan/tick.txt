package com.ticktxt.app

import com.ticktxt.app.core.nlp.SmartAddParser
import com.ticktxt.app.core.nlp.TickRulesConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SmartAddParserTest {

    // Reference anchor: Sunday, 2026-11-01 at 19:04
    private val anchorNow = LocalDateTime.of(2026, 11, 1, 19, 4)
    private val rules = TickRulesConfig()

    @Test
    fun `parses canonical prompt example coffee with mum at 2pm Tuesday at home`() {
        val res = SmartAddParser.parse("coffee with mum at 2pm Tuesday at home", anchorNow, rules)

        assertEquals("coffee with mum", res.strippedTitle)
        assertEquals(LocalDate.of(2026, 11, 3), res.dueDate)
        assertEquals(LocalTime.of(14, 0), res.dueTime)
        assertEquals(listOf("family"), res.projects)
        assertEquals(listOf("home"), res.contexts)
        assertEquals(listOf("-15m/n"), res.reminders)
        assertEquals(
            "2026-11-01 coffee with mum +family @home due:2026-11-03 duetime:14:00 rem:-15m/n",
            res.formattedTodoLine
        )
    }

    @Test
    fun `infers novel project and context from remind me to check chocotat at factory in 1 min`() {
        val res = SmartAddParser.parse(
            "remind me to check chocotat at factory in 1 min",
            anchorNow,
            rules
        )
        assertEquals("check chocotat", res.strippedTitle)
        assertEquals(listOf("chocotat"), res.projects)
        assertEquals(listOf("factory"), res.contexts)
        assertEquals(LocalDate.of(2026, 11, 1), res.dueDate)
        assertEquals(LocalTime.of(19, 5), res.dueTime)
        assertEquals(listOf("0m/n"), res.reminders)
        assertEquals(
            "2026-11-01 check chocotat +chocotat @factory due:2026-11-01 duetime:19:05 rem:0m/n",
            res.formattedTodoLine
        )
    }

    @Test
    fun `parses granular relative offsets including decimals and months`() {
        val r1 = SmartAddParser.parse("check oven at kitchen in 1.5 hrs", anchorNow, rules)
        assertEquals("check oven", r1.strippedTitle)
        assertEquals(listOf("oven"), r1.projects)
        assertEquals(listOf("kitchen"), r1.contexts)
        assertEquals(LocalDate.of(2026, 11, 1), r1.dueDate)
        assertEquals(LocalTime.of(20, 34), r1.dueTime)
        assertEquals(listOf("0m/n"), r1.reminders)

        val r2 = SmartAddParser.parse("inspect generator at warehouse in 2 hr", anchorNow, rules)
        assertEquals(LocalDate.of(2026, 11, 1), r2.dueDate)
        assertEquals(LocalTime.of(21, 4), r2.dueTime)
        assertEquals(listOf("generator"), r2.projects)
        assertEquals(listOf("warehouse"), r2.contexts)

        val r3 = SmartAddParser.parse("renew license at portal in 1 month", anchorNow, rules)
        assertEquals(LocalDate.of(2026, 12, 1), r3.dueDate)
        assertEquals(LocalTime.of(19, 4), r3.dueTime)
        assertEquals(listOf("portal"), r3.contexts)
    }

    @Test
    fun `preserves title words in meeting at Tuesday's cafe and ambiguous may or march`() {
        val r1 = SmartAddParser.parse("meeting at Tuesday's cafe tomorrow at 10am", anchorNow, rules)
        assertEquals("meeting at Tuesday's cafe", r1.strippedTitle)
        assertEquals(LocalDate.of(2026, 11, 2), r1.dueDate)
        assertEquals(LocalTime.of(10, 0), r1.dueTime)

        val r2 = SmartAddParser.parse("may I ask dad about march madness", anchorNow, rules)
        assertEquals("may I ask dad about march madness", r2.strippedTitle)
        assertNull(r2.dueDate)
        assertEquals(listOf("family"), r2.projects)
    }

    @Test
    fun `explicit todo_txt syntax always wins over inferred rules`() {
        val res = SmartAddParser.parse(
            "(C) call mum tomorrow +customproj @customctx due:2026-12-25 urgent",
            anchorNow,
            rules
        )
        assertEquals('C', res.priority)
        assertEquals(LocalDate.of(2026, 12, 25), res.dueDate)
        assertTrue(res.projects.contains("customproj"))
        assertTrue(res.contexts.contains("customctx"))
    }

    @Test
    fun `handles ranges, recurrence, alarm intents, and past-time rollover`() {
        val pastTime = SmartAddParser.parse("send client invoice at 2pm", anchorNow, rules)
        assertEquals(LocalDate.of(2026, 11, 2), pastTime.dueDate)
        assertEquals(LocalTime.of(14, 0), pastTime.dueTime)
        assertEquals(listOf("work"), pastTime.projects)

        val alarmRec = SmartAddParser.parse("take vitamins every day alarm at 7am", anchorNow, rules)
        assertEquals("1d", alarmRec.recurrence)
        assertEquals(listOf("07:00/a"), alarmRec.reminders)
    }
}
