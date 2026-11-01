package com.ticktxt.app

import com.ticktxt.app.core.nlp.RulesEngine
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
        // Past time today (2pm when now is 19:04) -> rolls over to tomorrow (2026-11-02)
        val pastTime = SmartAddParser.parse("send client invoice at 2pm", anchorNow, rules)
        assertEquals(LocalDate.of(2026, 11, 2), pastTime.dueDate)
        assertEquals(LocalTime.of(14, 0), pastTime.dueTime)
        assertEquals(listOf("work"), pastTime.projects)

        // Alarm intent + recurrence
        val alarmRec = SmartAddParser.parse("take vitamins every day alarm at 7am", anchorNow, rules)
        assertEquals("1d", alarmRec.recurrence)
        assertEquals(listOf("07:00/a"), alarmRec.reminders)
    }
}
