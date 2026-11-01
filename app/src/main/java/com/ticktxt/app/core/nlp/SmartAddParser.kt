package com.ticktxt.app.core.nlp

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Deterministic, 100% offline natural-language task & note parser.
 *
 * Pipeline stages:
 * 0. Detect `note: ...` quick-capture prefix.
 * 1. Lock explicit todo.txt tokens (`(A)`, `+project`, `@context`, `due:YYYY-MM-DD`, `rec:...`, `rem:...`).
 * 2. Protect title-internal phrases (quoted strings and possessive/proper-noun structures like
 *    `at Tuesday's cafe`, `May's report`, `March madness`).
 * 3. Extract recurrence (`every Tuesday`, `daily`, `every 2 weeks`, `monthly on the 1st`).
 * 4. Extract reminder intent (`alarm at 7am`, `remind me 30 min before`, `notify me the day before`, `don't remind me`).
 * 5. Extract ranges & durations (`2 to 3pm`, `for 1 hour`, `for 45 min`).
 * 6. Extract dates & times (`2pm`, `14:00`, `tomorrow`, `Tuesday`, `next Tuesday`, `in 3 days`,
 *    `end of month`, `the 15th`, `tonight`, `this weekend`, `noon`, `after lunch`).
 * 7. Extract priority keywords (`urgent`, `asap`, `!!` -> `(A)`; `important` -> `(B)`).
 * 8. Extract prepositional contexts (`at home` -> `@home`) and infer projects from `TickRulesConfig`
 *    (`mum` -> `+family` without stripping `mum` from the title).
 */
object SmartAddParser {

    private val WEEKDAY_MAP = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY
    )

    private val MONTH_MAP = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6,
        "july" to 7, "jul" to 7, "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9,
        "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12
    )

    fun parse(
        input: String,
        now: LocalDateTime,
        rules: TickRulesConfig = TickRulesConfig(),
        disabledSpanIds: Set<String> = emptySet()
    ): SmartAddParseResult {
        val today = now.toLocalDate()
        val trimmed = input.trim()

        // 0. Quick Note prefix ("note: ...")
        if (trimmed.lowercase().startsWith("note:")) {
            val noteBody = trimmed.substring(5).trim()
            val span = RecognizedSpan(
                id = "note_prefix",
                startIndex = 0,
                endIndex = 5,
                matchedText = trimmed.substring(0, 5),
                category = SpanCategory.NOTE_MODE,
                resolvedToken = "note",
                stripFromTitle = true
            )
            return SmartAddParseResult(
                rawInput = input,
                isNoteCapture = true,
                noteTitle = noteBody.lines().firstOrNull()?.take(48) ?: "untitled",
                strippedTitle = noteBody,
                creationDate = today,
                recognizedSpans = listOf(span),
                formattedTodoLine = noteBody
            )
        }

        val spans = mutableListOf<RecognizedSpan>()
        val consumedMask = BooleanArray(input.length)

        fun isRangeFree(start: Int, end: Int): Boolean {
            if (start < 0 || end > input.length || start >= end) return false
            for (i in start until end) {
                if (consumedMask[i]) return false
            }
            return true
        }

        fun markRange(start: Int, end: Int) {
            for (i in start until end) consumedMask[i] = true
        }

        fun addSpan(
            start: Int,
            end: Int,
            category: SpanCategory,
            resolvedToken: String,
            stripFromTitle: Boolean = true
        ): Boolean {
            val spanId = "${category.name}:$start..$end"
            if (spanId in disabledSpanIds) return false
            if (!isRangeFree(start, end)) return false
            if (stripFromTitle) markRange(start, end)
            spans.add(
                RecognizedSpan(
                    id = spanId,
                    startIndex = start,
                    endIndex = end,
                    matchedText = input.substring(start, end),
                    category = category,
                    resolvedToken = resolvedToken,
                    stripFromTitle = stripFromTitle
                )
            )
            return true
        }

        // Protect possessive/proper constructs like "Tuesday's cafe", "May's", or quoted text
        val protectedMask = BooleanArray(input.length)
        Regex(""[^"]+"|\b[A-Za-z]+'s\b|\b(?:May|March)\s+(?:I|we|you|madness)\b", RegexOption.IGNORE_CASE)
            .findAll(input)
            .forEach { m ->
                for (i in m.range) protectedMask[i] = true
            }

        fun isUnprotected(range: IntRange): Boolean =
            range.all { !protectedMask[it] && !consumedMask[it] }

        var explicitPriority: Char? = null
        var explicitDueDate: LocalDate? = null
        var explicitDueTime: LocalTime? = null
        var explicitThreshold: LocalDate? = null
        var explicitRecurrence: String? = null
        val explicitProjects = mutableListOf<String>()
        val explicitContexts = mutableListOf<String>()
        val explicitReminders = mutableListOf<String>()
        val passthroughTags = mutableListOf<String>()

        // 1. Pass 1: Explicit todo.txt tokens
        Regex("^\s*\(([A-Z])\)(?=\s|$)").find(input)?.let { m ->
            val p = m.groupValues[1][0]
            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.PRIORITY, "($p)")) {
                explicitPriority = p
            }
        }

        Regex("(?<=^|\s)\+([A-Za-z0-9_-]+)(?=\s|$)").findAll(input).forEach { m ->
            val proj = m.groupValues[1]
            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.PROJECT, "+$proj")) {
                explicitProjects.add(proj)
            }
        }

        Regex("(?<=^|\s)@([A-Za-z0-9_-]+)(?=\s|$)").findAll(input).forEach { m ->
            val ctx = m.groupValues[1]
            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.CONTEXT, "@$ctx")) {
                explicitContexts.add(ctx)
            }
        }

        Regex("(?<=^|\s)([A-Za-z][A-Za-z0-9_-]*):([^\s:]+[^\s]*)(?=\s|$)").findAll(input).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[2]
            if (key.lowercase() !in setOf("http", "https", "mailto", "note")) {
                when (key) {
                    "due" -> runCatching { LocalDate.parse(value.substringBefore('T')) }.getOrNull()?.let {
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$it")) {
                            explicitDueDate = it
                        }
                    }
                    "duetime" -> runCatching { LocalTime.parse(value) }.getOrNull()?.let {
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$it")) {
                            explicitDueTime = it
                        }
                    }
                    "t" -> runCatching { LocalDate.parse(value) }.getOrNull()?.let {
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "t:$it")) {
                            explicitThreshold = it
                        }
                    }
                    "rec" -> {
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.RECURRENCE, "rec:$value")) {
                            explicitRecurrence = value
                        }
                    }
                    "rem" -> {
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:$value")) {
                            explicitReminders.add(value)
                        }
                    }
                    else -> {
                        markRange(m.range.first, m.range.last + 1)
                        passthroughTags.add("${key}:${value}")
                    }
                }
            }
        }

        var inferredDueDate: LocalDate? = null
        var inferredDueTime: LocalTime? = null
        var inferredDurationMinutes: Int? = null
        var inferredRecurrence: String? = null
        var suppressReminder = false
        val inferredReminders = mutableListOf<String>()

        // 2. Reminder intents ("don't remind me", "remind me 30 min before", "alarm at 7am", "notify me the day before")
        Regex("\b(?:don't|do not|no)\s+remind(?:\s+me)?\b", RegexOption.IGNORE_CASE).find(input)?.let { m ->
            if (isUnprotected(m.range) && addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:none")) {
                suppressReminder = true
            }
        }

        Regex("\b(alarm|remind(?:\s+me)?|notify(?:\s+me)?|notification)\s+(\d+)\s*(min(?:ute)?s?|m|hr?s?|hours?|days?|d)\s+before\b", RegexOption.IGNORE_CASE)
            .findAll(input).forEach { m ->
                if (isUnprotected(m.range)) {
                    val kind = if (m.groupValues[1].lowercase().startsWith("alarm")) "a" else "n"
                    val num = m.groupValues[2].toInt()
                    val unitChar = m.groupValues[3].lowercase()[0]
                    val spec = "-${num}${unitChar}/$kind"
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:$spec")) {
                        inferredReminders.add(spec)
                    }
                }
            }

        Regex("\b(alarm|remind(?:\s+me)?|notify(?:\s+me)?)\s+the\s+day\s+before\b", RegexOption.IGNORE_CASE)
            .findAll(input).forEach { m ->
                if (isUnprotected(m.range)) {
                    val kind = if (m.groupValues[1].lowercase().startsWith("alarm")) "a" else "n"
                    val spec = "-1d/$kind"
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:$spec")) {
                        inferredReminders.add(spec)
                    }
                }
            }

        Regex("\b(alarm|remind(?:\s+me)?|notify(?:\s+me)?)\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b", RegexOption.IGNORE_CASE)
            .findAll(input).forEach { m ->
                if (isUnprotected(m.range)) {
                    val kind = if (m.groupValues[1].lowercase().startsWith("alarm")) "a" else "n"
                    val parsedTime = parseClockTokens(m.groupValues[2], m.groupValues[3], m.groupValues[4])
                    if (parsedTime != null) {
                        val timeStr = "%02d:%02d".format(parsedTime.hour, parsedTime.minute)
                        val spec = "$timeStr/$kind"
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:$spec")) {
                            inferredReminders.add(spec)
                        }
                    }
                }
            }

        // 3. Recurrence ("daily", "every day", "every Tuesday", "every 2 weeks", "monthly on the 1st")
        Regex("\b(?:every\s+(\d+)\s+(day|week|month|year)s?|every\s+(day|week|month|year)|daily|weekly|monthly|yearly)(?:\s+on\s+the\s+(\d{1,2})(?:st|nd|rd|th)?)?\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val count = m.groupValues[1].ifEmpty { "1" }.toInt()
                    val unitWord = (m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.value }).lowercase()
                    val unit = when {
                        unitWord.startsWith("d") -> "d"
                        unitWord.startsWith("w") -> "w"
                        unitWord.startsWith("m") -> "m"
                        unitWord.startsWith("y") -> "y"
                        else -> "d"
                    }
                    val recToken = "${count}${unit}"
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.RECURRENCE, "rec:$recToken")) {
                        inferredRecurrence = recToken
                        val dom = m.groupValues[4].toIntOrNull()
                        if (dom != null && dom in 1..31) {
                            inferredDueDate = resolveDayOfMonth(today, dom)
                        }
                    }
                }
            }

        Regex("\bevery\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|fri|sat|sun)\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val dow = WEEKDAY_MAP[m.groupValues[1].lowercase()]
                    if (dow != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.RECURRENCE, "rec:1w")) {
                        inferredRecurrence = "1w"
                        inferredDueDate = resolveBareWeekday(today, now.toLocalTime(), dow, inferredDueTime)
                    }
                }
            }

        // 4. Ranges & durations ("from 2 to 3pm", "2 to 3pm", "for 1 hour", "for 30 min")
        Regex("\b(?:from\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*(?:to|-)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val endMeridiem = m.groupValues[6]
                    val startMeridiem = m.groupValues[3].ifEmpty { endMeridiem }
                    val startTime = parseClockTokens(m.groupValues[1], m.groupValues[2], startMeridiem)
                    val endTime = parseClockTokens(m.groupValues[4], m.groupValues[5], endMeridiem)
                    if (startTime != null && endTime != null) {
                        val diffMins = java.time.Duration.between(startTime, endTime).toMinutes().toInt()
                            .let { if (it <= 0) it + 12 * 60 else it }
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DURATION_RANGE, "duetime:$startTime dur:${diffMins}m")) {
                            inferredDueTime = startTime
                            inferredDurationMinutes = diffMins
                        }
                    }
                }
            }

        Regex("\bfor\s+(\d+)\s*(min(?:ute)?s?|m|hr?s?|hours?)\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val qty = m.groupValues[1].toInt()
                    val isHours = m.groupValues[2].lowercase().startsWith("h")
                    val mins = if (isHours) qty * 60 else qty
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DURATION_RANGE, "dur:${mins}m")) {
                        inferredDurationMinutes = mins
                    }
                }
            }

        // 5. Explicit clock times ("at 2pm", "at 14:00", "2:30pm", "at noon", "at midnight")
        Regex("\b(?:at\s+)?(noon|midnight)\b", RegexOption.IGNORE_CASE).find(input)?.let { m ->
            if (isUnprotected(m.range)) {
                val tm = if (m.groupValues[1].lowercase() == "noon") LocalTime.of(12, 0) else LocalTime.of(0, 0)
                if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                    inferredDueTime = tm
                }
            }
        }

        Regex("\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b|\b(?:at\s+)(\d{1,2}):(\d{2})\b|\b([01]\d|2[0-3]):([0-5]\d)\b", RegexOption.IGNORE_CASE)
            .findAll(input).forEach { m ->
                if (inferredDueTime == null && isUnprotected(m.range)) {
                    val tm = if (m.groupValues[1].isNotEmpty()) {
                        parseClockTokens(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    } else if (m.groupValues[4].isNotEmpty()) {
                        parseClockTokens(m.groupValues[4], m.groupValues[5], "")
                    } else {
                        parseClockTokens(m.groupValues[6], m.groupValues[7], "")
                    }
                    if (tm != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                        inferredDueTime = tm
                    }
                }
            }

        // Named periods ("tonight", "after lunch", "this morning", "tomorrow morning", "this afternoon", "this evening")
        Regex("\b(tonight|after\s+lunch)\b", RegexOption.IGNORE_CASE).find(input)?.let { m ->
            if (isUnprotected(m.range)) {
                val phrase = m.groupValues[1].lowercase().replace(Regex("\s+"), "_")
                val tm = if (phrase == "tonight") rules.tonightTime else rules.afterLunchTime
                if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                    if (inferredDueTime == null) inferredDueTime = tm
                    if (inferredDueDate == null) {
                        inferredDueDate = if (now.toLocalTime().isBefore(tm)) today else today.plusDays(1)
                    }
                }
            }
        }

        // 6. Relative & calendar dates
        // "today", "tomorrow" (+ optional "morning"/"afternoon"/"evening")
        Regex("\b(today|tomorrow)(?:\s+(morning|afternoon|evening|night))?\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val baseD = if (m.groupValues[1].lowercase() == "tomorrow") today.plusDays(1) else today
                    val period = m.groupValues[2].lowercase()
                    val periodTime = when (period) {
                        "morning" -> rules.morningTime
                        "afternoon" -> rules.afternoonTime
                        "evening" -> rules.eveningTime
                        "night" -> rules.tonightTime
                        else -> null
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$baseD")) {
                        inferredDueDate = baseD
                        if (inferredDueTime == null && periodTime != null) inferredDueTime = periodTime
                    }
                }
            }

        // "in N days / weeks / months / hours"
        Regex("\bin\s+(\d+)\s+(day|week|month|hour|minute|min)s?\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val qty = m.groupValues[1].toLong()
                    val unit = m.groupValues[2].lowercase()
                    when {
                        unit.startsWith("d") -> {
                            val d = today.plusDays(qty)
                            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) inferredDueDate = d
                        }
                        unit.startsWith("w") -> {
                            val d = today.plusWeeks(qty)
                            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) inferredDueDate = d
                        }
                        unit.startsWith("m") && unit == "month" -> {
                            val d = today.plusMonths(qty)
                            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) inferredDueDate = d
                        }
                        unit.startsWith("h") -> {
                            val dt = now.plusHours(qty)
                            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:${dt.toLocalDate()} duetime:${dt.toLocalTime().withSecond(0).withNano(0)}")) {
                                inferredDueDate = dt.toLocalDate()
                                inferredDueTime = dt.toLocalTime().withSecond(0).withNano(0)
                            }
                        }
                    }
                }
            }

        // "end of month" / "eom", "this weekend", "next week"
        Regex("\b(end\s+of\s+(?:the\s+)?month|eom|this\s+weekend|next\s+week)\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val phrase = m.groupValues[1].lowercase()
                    val d = when {
                        phrase.contains("month") || phrase == "eom" -> today.with(TemporalAdjusters.lastDayOfMonth())
                        phrase.contains("weekend") -> {
                            if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today
                            else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
                        }
                        else -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) {
                        inferredDueDate = d
                    }
                }
            }

        // "on the 15th" / "the 15th"
        Regex("\b(?:on\s+)?the\s+(\d{1,2})(?:st|nd|rd|th)\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val dom = m.groupValues[1].toIntOrNull()
                    if (dom != null && dom in 1..31) {
                        val d = resolveDayOfMonth(today, dom)
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) {
                            inferredDueDate = d
                        }
                    }
                }
            }

        // Month + Day ("Nov 15", "15 Nov", "on May 5th" — avoids bare "may" or "march" without day number)
        Regex("\b(?:on\s+)?(?:(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?|(\d{1,2})(?:st|nd|rd|th)?\s+(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?))\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val mName = m.groupValues[1].ifEmpty { m.groupValues[4] }.lowercase()
                    val dNum = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull()
                    val monthNum = MONTH_MAP[mName]
                    if (monthNum != null && dNum != null && dNum in 1..31) {
                        var candidate = runCatching { LocalDate.of(today.year, monthNum, dNum) }.getOrNull()
                        if (candidate != null && candidate.isBefore(today)) {
                            candidate = runCatching { LocalDate.of(today.year + 1, monthNum, dNum) }.getOrNull()
                        }
                        if (candidate != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$candidate")) {
                            inferredDueDate = candidate
                        }
                    }
                }
            }

        // Weekdays ("next Tuesday", "on Tuesday", "Tuesday")
        Regex("\b(?:(next|this|on)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|fri|sat|sun)\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val mod = m.groupValues[1].lowercase()
                    val dow = WEEKDAY_MAP[m.groupValues[2].lowercase()]
                    if (dow != null) {
                        val d = if (mod == "next") {
                            // "next Tuesday": next occurrence, and if today is Tuesday, +7 days
                            val nextOcc = today.with(TemporalAdjusters.next(dow))
                            if (nextOcc.minusDays(7) == today) nextOcc else nextOcc
                        } else {
                            resolveBareWeekday(today, now.toLocalTime(), dow, inferredDueTime ?: explicitDueTime)
                        }
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) {
                            inferredDueDate = d
                        }
                    }
                }
            }

        // Ambiguity Rule: If a time was given with no date, resolve to today if still in future, else tomorrow
        if (inferredDueDate == null && explicitDueDate == null && (inferredDueTime != null || explicitDueTime != null)) {
            val targetTime = explicitDueTime ?: inferredDueTime!!
            inferredDueDate = if (targetTime.isAfter(now.toLocalTime())) today else today.plusDays(1)
        }

        // 7. Priority keywords ("urgent", "asap", "!!", "important")
        var inferredPriority: Char? = null
        Regex("(?<=^|\s)(!!|!|\burgent\b|\basap\b|\bimportant\b)(?=\s|$)", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (explicitPriority == null && isUnprotected(m.range)) {
                    val tok = m.groupValues[1].lowercase()
                    val p = if (tok == "important" || tok == "!") 'B' else 'A'
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.PRIORITY, "($p)")) {
                        inferredPriority = p
                    }
                }
            }

        // 8. Context phrases from rules ("at home" -> @home, "at the office" -> @office)
        val inferredContexts = mutableListOf<String>()
        for ((phrase, ctxName) in rules.phraseToContext.entries.sortedByDescending { it.key.length }) {
            val rx = Regex("\b${Regex.escape(phrase)}\b", RegexOption.IGNORE_CASE)
            rx.findAll(input).forEach { m ->
                if (isUnprotected(m.range)) {
                    // Strip prepositional context phrases like "at home", "on phone" from title
                    val strip = phrase.contains(" ")
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.CONTEXT, "@$ctxName", stripFromTitle = strip)) {
                        if (ctxName !in explicitContexts && ctxName !in inferredContexts) {
                            inferredContexts.add(ctxName)
                        }
                    }
                }
            }
        }

        // 9. Project keyword rules ("mum" -> +family, "invoice" -> +work — keep keyword in title!)
        val inferredProjects = mutableListOf<String>()
        for ((keyword, projName) in rules.keywordToProject) {
            val rx = Regex("\b${Regex.escape(keyword)}\b", RegexOption.IGNORE_CASE)
            rx.find(input)?.let { m ->
                if (!consumedMask[m.range.first]) {
                    addSpan(m.range.first, m.range.last + 1, SpanCategory.PROJECT, "+$projName", stripFromTitle = false)
                    if (projName !in explicitProjects && projName !in inferredProjects) {
                        inferredProjects.add(projName)
                    }
                }
            }
        }

        // Build stripped title by removing consumed spans and cleaning dangling prepositions
        val rawTitleChars = StringBuilder()
        for (i in input.indices) {
            if (!consumedMask[i]) rawTitleChars.append(input[i])
        }
        val strippedTitle = rawTitleChars.toString()
            .replace(Regex("\s+"), " ")
            .replace(Regex("\s+(?:at|on|by|in)\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { input.trim() }

        val finalPriority = explicitPriority ?: inferredPriority
        val finalDueDate = explicitDueDate ?: inferredDueDate
        val finalDueTime = explicitDueTime ?: inferredDueTime
        val finalRecurrence = explicitRecurrence ?: inferredRecurrence
        val finalProjects = (explicitProjects + inferredProjects).distinct()
        val finalContexts = (explicitContexts + inferredContexts).distinct()

        // Default reminder attachment if task has a due date and no explicit/inferred reminder and not suppressed
        val finalReminders = when {
            suppressReminder -> emptyList()
            explicitReminders.isNotEmpty() || inferredReminders.isNotEmpty() -> (explicitReminders + inferredReminders).distinct()
            finalDueDate != null && finalDueTime != null -> listOf(rules.timedReminderDefault)
            finalDueDate != null -> listOf(rules.dateOnlyReminderDefault)
            else -> emptyList()
        }

        // Assemble canonical todo.txt line
        val lineParts = mutableListOf<String>()
        if (finalPriority != null) lineParts.add("($finalPriority)")
        lineParts.add(today.toString())
        lineParts.add(strippedTitle)
        finalProjects.forEach { lineParts.add("+$it") }
        finalContexts.forEach { lineParts.add("@$it") }
        if (finalDueDate != null) lineParts.add("due:$finalDueDate")
        if (finalDueTime != null) lineParts.add("duetime:${"%02d:%02d".format(finalDueTime.hour, finalDueTime.minute)}")
        if (explicitThreshold != null) lineParts.add("t:$explicitThreshold")
        if (inferredDurationMinutes != null) lineParts.add("dur:${inferredDurationMinutes}m")
        if (finalRecurrence != null) lineParts.add("rec:$finalRecurrence")
        finalReminders.forEach { lineParts.add("rem:$it") }
        passthroughTags.forEach { lineParts.add(it) }

        return SmartAddParseResult(
            rawInput = input,
            isNoteCapture = false,
            strippedTitle = strippedTitle,
            priority = finalPriority,
            creationDate = today,
            dueDate = finalDueDate,
            dueTime = finalDueTime,
            thresholdDate = explicitThreshold,
            durationMinutes = inferredDurationMinutes,
            recurrence = finalRecurrence,
            projects = finalProjects,
            contexts = finalContexts,
            reminders = finalReminders,
            suppressReminder = suppressReminder,
            passthroughTags = passthroughTags,
            recognizedSpans = spans.sortedBy { it.startIndex },
            formattedTodoLine = lineParts.joinToString(" ")
        )
    }

    private fun parseClockTokens(hourStr: String, minStr: String?, meridiemStr: String?): LocalTime? {
        var h = hourStr.toIntOrNull() ?: return null
        val m = minStr?.ifEmpty { "0" }?.toIntOrNull() ?: 0
        val mer = meridiemStr?.lowercase() ?: ""
        if (m !in 0..59) return null
        if (mer == "pm" && h in 1..11) h += 12
        if (mer == "am" && h == 12) h = 0
        if (h !in 0..23) return null
        return LocalTime.of(h, m)
    }

    private fun resolveBareWeekday(
        today: LocalDate,
        currentTime: LocalTime,
        targetDow: DayOfWeek,
        targetTime: LocalTime?
    ): LocalDate {
        if (today.dayOfWeek == targetDow) {
            // Same weekday: if time is specified and still in future, today; otherwise next week (+7d)
            return if (targetTime != null && targetTime.isAfter(currentTime)) {
                today
            } else {
                today.plusWeeks(1)
            }
        }
        return today.with(TemporalAdjusters.next(targetDow))
    }

    private fun resolveDayOfMonth(today: LocalDate, dom: Int): LocalDate {
        val maxThisMonth = today.lengthOfMonth()
        val clampedThis = dom.coerceAtMost(maxThisMonth)
        val thisMonthDate = today.withDayOfMonth(clampedThis)
        return if (!thisMonthDate.isBefore(today)) {
            thisMonthDate
        } else {
            val nextMonth = today.plusMonths(1)
            nextMonth.withDayOfMonth(dom.coerceAtMost(nextMonth.lengthOfMonth()))
        }
    }
}
