package com.ticktxt.app

import com.ticktxt.app.core.nlp.RulesEngine
import com.ticktxt.app.core.nlp.SmartAddParser
import com.ticktxt.app.core.nlp.TickRulesConfig
import com.ticktxt.app.core.todotxt.TodoParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SmartAddParserTest {

    // Reference anchor: Sunday, 2026-11-01 at 19:04
    private val anchorNow = LocalDateTime.of(2026, 11, 1, 19, 4)
    // Morning reference anchor: Sunday, 2026-11-01 at 10:00
    private val morningAnchor = LocalDateTime.of(2026, 11, 1, 10, 0)
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

    @Test
    fun `parses colloquial, meridiem-less, dotted, compact, and spoken clock times naturally`() {
        // Bare hour after preposition: "by 5" -> 17:00 today
        val r1 = SmartAddParser.parse("submit report by 5", morningAnchor, rules)
        assertEquals("submit report", r1.strippedTitle)
        assertEquals(LocalDate.of(2026, 11, 1), r1.dueDate)
        assertEquals(LocalTime.of(17, 0), r1.dueTime)
        assertEquals(listOf("work"), r1.projects)

        // Compact 930am + abbreviation tmr
        val r2 = SmartAddParser.parse("standup tmr 930am", anchorNow, rules)
        assertEquals("standup", r2.strippedTitle)
        assertEquals(LocalDate.of(2026, 11, 2), r2.dueDate)
        assertEquals(LocalTime.of(9, 30), r2.dueTime)
        assertEquals(listOf("work"), r2.projects)

        // Dotted shorthand + single-letter meridiem ("9.30a")
        val r3 = SmartAddParser.parse("call mom 9.30a", morningAnchor, rules)
        assertEquals(LocalTime.of(9, 30), r3.dueTime)
        assertEquals(listOf("family"), r3.projects)
        assertEquals(listOf("phone"), r3.contexts)

        // Spoken "half past 2" on a weekday
        val r4 = SmartAddParser.parse("dentist fri half past 2", morningAnchor, rules)
        assertEquals(LocalDate.of(2026, 11, 6), r4.dueDate)
        assertEquals(LocalTime.of(14, 30), r4.dueTime)
        assertEquals(listOf("health"), r4.projects)

        // Spoken "quarter to 8" + tmrw
        val r5 = SmartAddParser.parse("leave house quarter to 8 tmrw", anchorNow, rules)
        assertEquals(LocalDate.of(2026, 11, 2), r5.dueDate)
        assertEquals(LocalTime.of(7, 45), r5.dueTime)

        // Period hint overrides smart AM/PM: "tomorrow morning at 6" -> 06:00 (not 18:00)
        val r6 = SmartAddParser.parse("gym tomorrow morning at 6", LocalDateTime.of(2026, 11, 1, 14, 0), rules)
        assertEquals(LocalDate.of(2026, 11, 2), r6.dueDate)
        assertEquals(LocalTime.of(6, 0), r6.dueTime)
        assertEquals(listOf("health"), r6.projects)

        // Period hint "tonight" resolves "at 8" -> 20:00
        val r7 = SmartAddParser.parse("call mom at 8 tonight", LocalDateTime.of(2026, 11, 1, 14, 0), rules)
        assertEquals(LocalDate.of(2026, 11, 1), r7.dueDate)
        assertEquals(LocalTime.of(20, 0), r7.dueTime)

        // Military 4-digit time ("at 1430")
        val r8 = SmartAddParser.parse("call supplier at 1430", morningAnchor, rules)
        assertEquals(LocalTime.of(14, 30), r8.dueTime)
        assertEquals(listOf("phone"), r8.contexts)
    }

    @Test
    fun `parses workday milestones, spoken offsets, numeric dates, and defer dates without false positives`() {
        val eod = SmartAddParser.parse("submit taxes eod", morningAnchor, rules)
        assertEquals(LocalDate.of(2026, 11, 1), eod.dueDate)
        assertEquals(LocalTime.of(17, 0), eod.dueTime)
        assertEquals(listOf("finance"), eod.projects)

        val eow = SmartAddParser.parse("ship release by eow", morningAnchor, rules)
        assertEquals(LocalDate.of(2026, 11, 6), eow.dueDate)
        assertEquals(LocalTime.of(17, 0), eow.dueTime)
        assertEquals(listOf("work"), eow.projects)

        val afterWork = SmartAddParser.parse("buy oat milk after work", morningAnchor, rules)
        assertEquals(LocalDate.of(2026, 11, 1), afterWork.dueDate)
        assertEquals(LocalTime.of(18, 0), afterWork.dueTime)
        assertEquals(listOf("groceries"), afterWork.projects)
        assertEquals(listOf("errands"), afterWork.contexts)

        val halfHr = SmartAddParser.parse("stretch in half an hour", morningAnchor, rules)
        assertEquals(LocalTime.of(10, 30), halfHr.dueTime)
        assertEquals(listOf("0m/n"), halfHr.reminders)

        val wordOffset = SmartAddParser.parse("check laundry in two hours", morningAnchor, rules)
        assertEquals(LocalTime.of(12, 0), wordOffset.dueTime)

        val dmy = SmartAddParser.parse("renew passport 25/11", morningAnchor, rules)
        assertEquals(LocalDate.of(2026, 11, 25), dmy.dueDate)
        assertEquals(listOf("travel"), dmy.projects)

        val mdy = SmartAddParser.parse("meeting 11/25 at 3", morningAnchor, rules)
        assertEquals(LocalDate.of(2026, 11, 25), mdy.dueDate)
        assertEquals(LocalTime.of(15, 0), mdy.dueTime)

        val defer = SmartAddParser.parse("pay rent starting next monday", anchorNow, rules)
        assertEquals(LocalDate.of(2026, 11, 2), defer.thresholdDate)
        assertEquals(listOf("finance"), defer.projects)

        // False-positive guard: "call Tom at 3" keeps "Tom" in title, and "buy 5 apples" doesn't parse 5 as time
        val callTom = SmartAddParser.parse("call Tom at 3", morningAnchor, rules)
        assertEquals("call Tom", callTom.strippedTitle)
        assertEquals(LocalDate.of(2026, 11, 1), callTom.dueDate)
        assertEquals(LocalTime.of(15, 0), callTom.dueTime)

        val apples = SmartAddParser.parse("buy 5 apples", morningAnchor, rules)
        assertNull(apples.dueDate)
        assertNull(apples.dueTime)
        assertEquals(listOf("errands"), apples.contexts)
    }

    @Test
    fun `suggests projects and contexts via stemming, fuzzy typo match, statistical history, and cross-tag affinity`() {
        // Stemming ("invoices" -> "invoice" -> +work) + weekday/period ("tmrw morning")
        val stemmed = SmartAddParser.parse("email Alex about invoices tmrw morning", anchorNow, rules)
        assertEquals(LocalDate.of(2026, 11, 2), stemmed.dueDate)
        assertEquals(LocalTime.of(9, 0), stemmed.dueTime)
        assertEquals(listOf("work"), stemmed.projects)
        assertEquals(listOf("email"), stemmed.contexts)

        // Fuzzy typo matching ("grocries" -> known +groceries)
        val fuzzyRules = rules.copy(knownProjects = setOf("groceries", "architecture"))
        val fuzzy = SmartAddParser.parse("buy grocries", morningAnchor, fuzzyRules)
        assertEquals(listOf("groceries"), fuzzy.projects)
        assertEquals(listOf("errands"), fuzzy.contexts)

        // Weighted statistical history + cross-tag affinity
        val historyTasks = listOf(
            TodoParser.parseLine("2026-10-20 deploy api service +backend @desk", 1),
            TodoParser.parseLine("2026-10-22 fix api auth +backend @desk", 2),
            TodoParser.parseLine("2026-10-25 benchmark api latency +backend @desk", 3)
        )
        val learnedRules = RulesEngine.withHistoricalLearning(rules, historyTasks, emptyList())
        val inferredFromHistory = SmartAddParser.parse("profile api", morningAnchor, learnedRules)
        assertEquals(listOf("backend"), inferredFromHistory.projects)
        assertEquals(listOf("desk"), inferredFromHistory.contexts)

        // Junk-project guard: vague pronouns/nouns never create bogus +everything / +stuff projects
        val noJunk1 = SmartAddParser.parse("finish everything tomorrow", morningAnchor, rules)
        assertTrue(noJunk1.projects.isEmpty())
        val noJunk2 = SmartAddParser.parse("fix stuff", morningAnchor, rules)
        assertTrue(noJunk2.projects.isEmpty())

        // Multi-candidate runner-up suggestions: "pay dentist bill" surfaces both +health and +finance
        val multi = SmartAddParser.parse("pay dentist bill", morningAnchor, rules)
        val allSuggestedTagNames = multi.suggestedTags.map { it.tag }
        assertTrue("+health" in allSuggestedTagNames)
        assertTrue("+finance" in allSuggestedTagNames)
    }
}
