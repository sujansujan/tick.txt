package com.ticktxt.app.core.nlp

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/**
 * Deterministic, 100% offline Natural-Language Capture Engine (zero LLM required):
 *
 * 1. Human-friendly Date & Time Parsing:
 *    - Colloquial & shorthand clock formats: `9:30am`, `9.30pm`, `930am`, `5p`, `9a`, `14:30`, `at 1430`,
 *      `at 9`, `by 5`, `around 3`, `before 7:30`, `gym at 6 30`, `half past 9`, `quarter to 8`, `9 o'clock`.
 *    - Smart AM/PM disambiguation that respects period words (`morning`/`tonight`), future dates,
 *      and current time of day so `"submit report by 5"` -> `17:00` and `"tomorrow morning at 6"` -> `06:00`.
 *    - Natural dates, shorthands & milestones: `tmr`/`tmrw`/`tod`/`2nite`, `day after tomorrow`,
 *      `eod`/`cob`/`eow`/`eom`, `before lunch`/`after work`/`before bed`, `friday morning`,
 *      `in half an hour`, `in two hours`, `in a couple days`, `25/11`, `11/25`, `2026-11-25`,
 *      plus defer dates (`starting monday`, `defer until next week` -> `t:YYYY-MM-DD`).
 *
 * 2. Scored 6-Signal Context (`@`) & Project (`+`) Suggestion Engine (`TagSuggestionEngine`):
 *    - Rules + stemming + bigrams + weighted statistical history (`todo.txt` & `done.txt`)
 *      + fuzzy typo-tolerant matching + action-verb medium cues + cross-tag affinity.
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

    private val WORD_NUMBER_MAP = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
        "eleven" to 11.0, "twelve" to 12.0, "fifteen" to 15.0, "twenty" to 20.0,
        "thirty" to 30.0, "forty" to 40.0, "forty-five" to 45.0, "sixty" to 60.0
    )

    private const val WEEKDAY_ALT =
        "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|fri|sat|sun"

    private const val MONTH_ALT =
        "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"

    private const val WORD_NUM_ALT =
        "a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty-five|forty|sixty"

    private const val NON_TIME_UNIT_LOOKAHEAD =
        "(?!\\s*(?:st|nd|rd|th|%|days?|weeks?|wks?|months?|mos?|years?|yrs?|hrs?|hours?|mins?|minutes?|secs?|seconds?|items?|times?|dollars?|usd|eur|gbp|kg|lbs?|km|mi|miles?|ft|px|gb|mb|tb|people|pages?|chapters?|steps?|copies|bottles?|packs?|bags?|boxes?))"

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
        Regex("\"[^\"]+\"|\\b[A-Za-z]+'s\\b|\\b(?:May|March)\\s+(?:I|we|you|madness)\\b", RegexOption.IGNORE_CASE)
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

        // =====================================================================
        // PASS 1: Explicit todo.txt tokens (highest precedence)
        // =====================================================================
        Regex("^\\s*\\(([A-Z])\\)(?=\\s|$)").find(input)?.let { m ->
            val p = m.groupValues[1][0]
            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.PRIORITY, "($p)")) {
                explicitPriority = p
            }
        }

        Regex("(?<=^|\\s)\\+([A-Za-z0-9_-]+)(?=\\s|$)").findAll(input).forEach { m ->
            val proj = m.groupValues[1]
            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.PROJECT, "+$proj")) {
                explicitProjects.add(proj)
            }
        }

        Regex("(?<=^|\\s)@([A-Za-z0-9_-]+)(?=\\s|$)").findAll(input).forEach { m ->
            val ctx = m.groupValues[1]
            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.CONTEXT, "@$ctx")) {
                explicitContexts.add(ctx)
            }
        }

        Regex("(?<=^|\\s)([A-Za-z][A-Za-z0-9_-]*):([^\\s:]+[^\\s]*)(?=\\s|$)").findAll(input).forEach { m ->
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
        var inferredThreshold: LocalDate? = null
        var inferredDurationMinutes: Int? = null
        var inferredRecurrence: String? = null
        var suppressReminder = false
        var hasLeadingReminderIntent = false
        var leadingReminderKind = "n"
        var hasImmediateRelativeOffset = false
        var exactOffsetMillis: Long? = null
        val inferredReminders = mutableListOf<String>()

        // Track meridiem-less 12h clock times (e.g. "at 9", "by 5", "half past 2") so we can
        // resolve AM vs PM accurately AFTER dates and period words ("morning", "tonight") are parsed.
        var ambiguousHourMinute: Pair<Int, Int>? = null
        var periodHint: String? = null // "morning", "afternoon", "evening", "night"
        var pendingBareWeekday: DayOfWeek? = null

        // PASS 1b: Leading reminder command prefix ("remind me to ", "alarm me to ", "notify me to ", "remember to ")
        Regex("^\\s*(?:please\\s+)?(alarm|remind|notify|remember)(?:\\s+me)?\\s+to\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val verb = m.groupValues[1].lowercase()
                    leadingReminderKind = if (verb.startsWith("alarm")) "a" else "n"
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:0m/$leadingReminderKind")) {
                        hasLeadingReminderIntent = true
                    }
                }
            }

        // =====================================================================
        // PASS 2: Explicit Reminder intents
        // =====================================================================
        Regex("\\b(?:don't|do not|no)\\s+remind(?:\\s+me)?\\b", RegexOption.IGNORE_CASE).find(input)?.let { m ->
            if (isUnprotected(m.range) && addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:none")) {
                suppressReminder = true
            }
        }

        Regex("\\b(alarm|remind(?:\\s+me)?|notify(?:\\s+me)?|notification)\\s+(\\d+(?:\\.\\d+)?)\\s*(min(?:ute)?s?|m|hr?s?|hours?|h|days?|d|weeks?|w)\\s+before\\b", RegexOption.IGNORE_CASE)
            .findAll(input).forEach { m ->
                if (isUnprotected(m.range)) {
                    val kind = if (m.groupValues[1].lowercase().startsWith("alarm")) "a" else "n"
                    val amount = m.groupValues[2].toDoubleOrNull() ?: 15.0
                    val unitChar = m.groupValues[3].lowercase()[0]
                    val spec = if (amount % 1.0 == 0.0) {
                        "-${amount.toInt()}${unitChar}/$kind"
                    } else {
                        val mins = when (unitChar) {
                            'h' -> (amount * 60).roundToLong()
                            'd' -> (amount * 1440).roundToLong()
                            'w' -> (amount * 10080).roundToLong()
                            else -> amount.roundToLong()
                        }
                        "-${mins}m/$kind"
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:$spec")) {
                        inferredReminders.add(spec)
                    }
                }
            }

        Regex("\\b(alarm|remind(?:\\s+me)?|notify(?:\\s+me)?)\\s+the\\s+day\\s+before\\b", RegexOption.IGNORE_CASE)
            .findAll(input).forEach { m ->
                if (isUnprotected(m.range)) {
                    val kind = if (m.groupValues[1].lowercase().startsWith("alarm")) "a" else "n"
                    val spec = "-1d/$kind"
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.REMINDER, "rem:$spec")) {
                        inferredReminders.add(spec)
                    }
                }
            }

        Regex("\\b(alarm|remind(?:\\s+me)?|notify(?:\\s+me)?)\\s+at\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|a|p)?\\b", RegexOption.IGNORE_CASE)
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

        // =====================================================================
        // PASS 3: Recurrence & Defer / Threshold (`t:`)
        // =====================================================================
        Regex("\\b(?:every\\s+weekday|on\\s+weekdays|weekdays)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range) && addSpan(m.range.first, m.range.last + 1, SpanCategory.RECURRENCE, "rec:1b")) {
                    inferredRecurrence = "1b"
                }
            }

        Regex("\\b(?:every\\s+(\\d+)\\s+(day|week|month|year)s?|every\\s+(day|week|month|year)|daily|weekly|monthly|yearly)(?:\\s+on\\s+the\\s+(\\d{1,2})(?:st|nd|rd|th)?)?\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredRecurrence == null && isUnprotected(m.range)) {
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

        Regex("\\bevery\\s+($WEEKDAY_ALT)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val dow = WEEKDAY_MAP[m.groupValues[1].lowercase()]
                    if (dow != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.RECURRENCE, "rec:1w")) {
                        inferredRecurrence = "1w"
                        inferredDueDate = resolveBareWeekday(today, now.toLocalTime(), dow, inferredDueTime)
                        pendingBareWeekday = dow
                    }
                }
            }

        // Defer / Threshold date ("starting tomorrow", "starting next monday", "defer until friday", "defer to 2026-12-01")
        Regex(
            "\\b(?:starting|defer(?:\\s+until|\\s+to)?)\\s+(?:(next|this)\\s+)?(today|tomorrow|tmr|tmrw|next\\s+week|next\\s+month|\\d{4}-\\d{2}-\\d{2}|$WEEKDAY_ALT)\\b",
            RegexOption.IGNORE_CASE
        ).find(input)?.let { m ->
            if (explicitThreshold == null && isUnprotected(m.range)) {
                val mod = m.groupValues[1].lowercase()
                val target = m.groupValues[2].lowercase().replace(Regex("\\s+"), " ")
                val resolvedThreshold = when {
                    target == "today" -> today
                    target in setOf("tomorrow", "tmr", "tmrw") -> today.plusDays(1)
                    target == "next week" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    target == "next month" -> today.plusMonths(1).withDayOfMonth(1)
                    target.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> runCatching { LocalDate.parse(target) }.getOrNull()
                    target in WEEKDAY_MAP -> {
                        val dow = WEEKDAY_MAP[target]!!
                        if (mod == "next") today.with(TemporalAdjusters.next(dow))
                        else resolveBareWeekday(today, now.toLocalTime(), dow, null)
                    }
                    else -> null
                }
                if (resolvedThreshold != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "t:$resolvedThreshold")) {
                    inferredThreshold = resolvedThreshold
                }
            }
        }

        // =====================================================================
        // PASS 4: Ranges & Durations ("2 to 3pm", "14:00-15:30", "from 9 to 11", "for 45 min")
        // =====================================================================
        Regex("\\b(?:from\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?\\s*(?:to|-)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val endMeridiem = m.groupValues[6]
                    val startMeridiem = m.groupValues[3].ifEmpty { endMeridiem }
                    val startTime = parseClockTokens(m.groupValues[1], m.groupValues[2], startMeridiem)
                    val endTime = parseClockTokens(m.groupValues[4], m.groupValues[5], endMeridiem)
                    if (startTime != null && endTime != null) {
                        val diffMins = Duration.between(startTime, endTime).toMinutes().toInt()
                            .let { if (it <= 0) it + 12 * 60 else it }
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DURATION_RANGE, "duetime:$startTime dur:${diffMins}m")) {
                            inferredDueTime = startTime
                            inferredDurationMinutes = diffMins
                        }
                    }
                }
            }

        // 24h or colon time ranges without am/pm ("14:00-15:30", "9:30 to 10:30")
        Regex("\\b(?:from\\s+)?(\\d{1,2}):([0-5]\\d)\\s*(?:to|-)\\s*(\\d{1,2}):([0-5]\\d)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueTime == null && isUnprotected(m.range)) {
                    val startTime = parseClockTokens(m.groupValues[1], m.groupValues[2], "")
                    val endTime = parseClockTokens(m.groupValues[3], m.groupValues[4], "")
                    if (startTime != null && endTime != null) {
                        val diffMins = Duration.between(startTime, endTime).toMinutes().toInt()
                            .let { if (it <= 0) it + 12 * 60 else it }
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DURATION_RANGE, "duetime:$startTime dur:${diffMins}m")) {
                            inferredDueTime = startTime
                            inferredDurationMinutes = diffMins
                        }
                    }
                }
            }

        Regex("\\bfor\\s+(?:half\\s+an?\\s+hour|(\\d+(?:\\.\\d+)?)\\s*(min(?:ute)?s?|m|hr?s?|hours?|h))\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val mins = if (m.groupValues[1].isEmpty()) {
                        30
                    } else {
                        val qty = m.groupValues[1].toDoubleOrNull() ?: 1.0
                        val isHours = m.groupValues[2].lowercase().startsWith("h")
                        if (isHours) (qty * 60.0).roundToLong().toInt() else qty.roundToLong().toInt()
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DURATION_RANGE, "dur:${mins}m")) {
                        inferredDurationMinutes = mins
                    }
                }
            }

        // =====================================================================
        // PASS 5: Granular Relative Offsets (digits, word-numbers & English idioms)
        // =====================================================================
        // 5a. Spoken offset idioms ("in half an hour", "in a quarter hour", "in a couple of hours", "in a few minutes")
        Regex("\\b(?:in|after)\\s+(?:a\\s+)?(half\\s+(?:an\\s+)?hour|quarter\\s+(?:of\\s+an\\s+)?hour|couple\\s+(?:of\\s+)?(min(?:ute)?s?|hr?s?|hours?|days?|weeks?)|few\\s+(min(?:ute)?s?|hr?s?|hours?|days?))\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val phrase = m.groupValues[1].lowercase()
                    val (targetDt, offsetMs, assignTime) = when {
                        phrase.startsWith("half") -> Triple(now.plusMinutes(30), 30 * 60_000L, true)
                        phrase.startsWith("quarter") -> Triple(now.plusMinutes(15), 15 * 60_000L, true)
                        phrase.startsWith("couple") -> {
                            val u = m.groupValues[2].lowercase()
                            when {
                                u.startsWith("m") -> Triple(now.plusMinutes(2), 2 * 60_000L, true)
                                u.startsWith("h") -> Triple(now.plusHours(2), 2 * 3_600_000L, true)
                                u.startsWith("d") -> Triple(now.plusDays(2), null, false)
                                else -> Triple(now.plusWeeks(2), null, false)
                            }
                        }
                        else -> { // "few ..."
                            val u = m.groupValues[3].lowercase()
                            when {
                                u.startsWith("m") -> Triple(now.plusMinutes(15), 15 * 60_000L, true)
                                u.startsWith("h") -> Triple(now.plusHours(3), 3 * 3_600_000L, true)
                                else -> Triple(now.plusDays(3), null, false)
                            }
                        }
                    }
                    val dLocal = targetDt.toLocalDate()
                    val tLocal = LocalTime.of(targetDt.hour, targetDt.minute)
                    val label = if (assignTime) "due:$dLocal duetime:${"%02d:%02d".format(tLocal.hour, tLocal.minute)}" else "due:$dLocal"
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, label)) {
                        inferredDueDate = dLocal
                        if (assignTime) {
                            inferredDueTime = tLocal
                            hasImmediateRelativeOffset = true
                            exactOffsetMillis = offsetMs
                        }
                    }
                }
            }

        // 5b. Compound "in 1h 30m" / "in 1 hr and 30 mins"
        Regex("\\b(?:in|after)\\s+(\\d+)\\s*(?:hr?s?|hours?|h)\\s*(?:and\\s*)?(\\d+)\\s*(?:min(?:ute)?s?|m)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val hrs = m.groupValues[1].toLongOrNull() ?: 0L
                    val mins = m.groupValues[2].toLongOrNull() ?: 0L
                    val targetDt = now.plusHours(hrs).plusMinutes(mins)
                    val tStr = "%02d:%02d".format(targetDt.hour, targetDt.minute)
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:${targetDt.toLocalDate()} duetime:$tStr")) {
                        inferredDueDate = targetDt.toLocalDate()
                        inferredDueTime = LocalTime.of(targetDt.hour, targetDt.minute)
                        hasImmediateRelativeOffset = true
                        exactOffsetMillis = (hrs * 3600L + mins * 60L) * 1000L
                    }
                }
            }

        // 5c. "in <number or word-number> <unit>" ("in 1 min", "in two hours", "in 1.5 hrs", "in five days", "in 1 month")
        Regex(
            "\\b(?:in|after)\\s+($WORD_NUM_ALT|\\d+(?:\\.\\d+)?)\\s*(sec(?:ond)?s?|s|min(?:ute)?s?|m|hr?s?|hours?|h|days?|d|weeks?|wks?|w|fortnights?|months?|mos?|mo|years?|yrs?|y)\\b",
            RegexOption.IGNORE_CASE
        ).find(input)?.let { m ->
            if (inferredDueDate == null && isUnprotected(m.range)) {
                val rawAmt = m.groupValues[1].lowercase()
                val amount = WORD_NUMBER_MAP[rawAmt] ?: rawAmt.toDoubleOrNull() ?: 1.0
                val unit = m.groupValues[2].lowercase()
                val isSubDayOrExact = unit.startsWith("s") ||
                    unit in setOf("m", "min", "mins", "minute", "minutes") ||
                    unit.startsWith("h") ||
                    (amount % 1.0 != 0.0)

                val targetDt: LocalDateTime = when {
                    unit.startsWith("s") -> now.plusSeconds(amount.roundToLong().coerceAtLeast(60L))
                    unit in setOf("m", "min", "mins", "minute", "minutes") -> now.plusMinutes(amount.roundToLong())
                    unit.startsWith("h") -> now.plusMinutes((amount * 60.0).roundToLong())
                    unit.startsWith("d") -> {
                        if (amount % 1.0 == 0.0) now.plusDays(amount.toLong())
                        else now.plusMinutes((amount * 1440.0).roundToLong())
                    }
                    unit.startsWith("fortnight") -> now.plusWeeks((amount * 2).roundToLong())
                    unit.startsWith("w") -> {
                        if (amount % 1.0 == 0.0) now.plusWeeks(amount.toLong())
                        else now.plusMinutes((amount * 10080.0).roundToLong())
                    }
                    unit.startsWith("mo") || unit.startsWith("month") -> {
                        val wholeMonths = amount.toLong()
                        val extraDays = ((amount - wholeMonths) * 30.0).roundToLong()
                        now.plusMonths(wholeMonths).plusDays(extraDays)
                    }
                    unit.startsWith("y") -> {
                        val wholeYears = amount.toLong()
                        val extraDays = ((amount - wholeYears) * 365.0).roundToLong()
                        now.plusYears(wholeYears).plusDays(extraDays)
                    }
                    else -> now.plusMinutes(amount.roundToLong())
                }

                val dLocal = targetDt.toLocalDate()
                val tLocal = LocalTime.of(targetDt.hour, targetDt.minute)
                val assignTime = isSubDayOrExact || hasLeadingReminderIntent || unit.startsWith("mo") || unit.startsWith("y")
                val resolvedLabel = if (assignTime) "due:$dLocal duetime:${"%02d:%02d".format(tLocal.hour, tLocal.minute)}" else "due:$dLocal"

                if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, resolvedLabel)) {
                    inferredDueDate = dLocal
                    if (assignTime) {
                        inferredDueTime = tLocal
                        hasImmediateRelativeOffset = true
                        exactOffsetMillis = when {
                            unit.startsWith("s") -> (amount * 1000.0).roundToLong().coerceAtLeast(1000L)
                            unit in setOf("m", "min", "mins", "minute", "minutes") -> (amount * 60_000.0).roundToLong()
                            unit.startsWith("h") -> (amount * 3_600_000.0).roundToLong()
                            unit.startsWith("d") -> (amount * 86_400_000.0).roundToLong()
                            unit.startsWith("w") -> (amount * 604_800_000.0).roundToLong()
                            else -> null
                        }
                    }
                }
            }
        }

        // 5d. "a week from now" / "2 days from today"
        Regex("\\b($WORD_NUM_ALT|\\d+)\\s+(day|week|month)s?\\s+from\\s+(?:now|today)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val amt = (WORD_NUMBER_MAP[m.groupValues[1].lowercase()] ?: m.groupValues[1].toDoubleOrNull() ?: 1.0).toLong()
                    val d = when (m.groupValues[2].lowercase()) {
                        "day" -> today.plusDays(amt)
                        "week" -> today.plusWeeks(amt)
                        else -> today.plusMonths(amt)
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) {
                        inferredDueDate = d
                    }
                }
            }

        // =====================================================================
        // PASS 6: Natural & Shorthand Clock Times (far beyond "at 9:30 am")
        // =====================================================================
        // 6a. Spoken English clock idioms ("half past 9", "quarter past 3", "quarter to 8", "9 o'clock")
        Regex("\\b(?:(?:at|by|before|around|until|~)\\s+)?half\\s+(?:past\\s+)?(\\d{1,2})(?:\\s*(a\\.?m\\.?|p\\.?m\\.?))?\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueTime == null && ambiguousHourMinute == null && isUnprotected(m.range)) {
                    val h = m.groupValues[1].toIntOrNull()
                    val mer = m.groupValues[2]
                    if (h != null && h in 1..23) {
                        if (mer.isNotEmpty() || h > 12) {
                            val tm = parseClockTokens(h.toString(), "30", mer)
                            if (tm != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                                inferredDueTime = tm
                            }
                        } else if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:${"%02d:30".format(h)}")) {
                            ambiguousHourMinute = h to 30
                        }
                    }
                }
            }

        Regex("\\b(?:(?:at|by|before|around|until|~)\\s+)?quarter\\s+(past|to|till|of)\\s+(\\d{1,2})(?:\\s*(a\\.?m\\.?|p\\.?m\\.?))?\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueTime == null && ambiguousHourMinute == null && isUnprotected(m.range)) {
                    val dir = m.groupValues[1].lowercase()
                    val rawH = m.groupValues[2].toIntOrNull()
                    val mer = m.groupValues[3]
                    if (rawH != null && rawH in 1..23) {
                        val (h, min) = if (dir == "past") {
                            rawH to 15
                        } else {
                            val prev = if (rawH == 1) 12 else rawH - 1
                            prev to 45
                        }
                        if (mer.isNotEmpty() || rawH > 12) {
                            val tm = parseClockTokens(h.toString(), min.toString(), mer)
                            if (tm != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                                inferredDueTime = tm
                            }
                        } else if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:${"%02d:%02d".format(h, min)}")) {
                            ambiguousHourMinute = h to min
                        }
                    }
                }
            }

        Regex("\\b(?:(?:at|by|before|around|until|~)\\s+)?(\\d{1,2})\\s*o'?clock\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueTime == null && ambiguousHourMinute == null && isUnprotected(m.range)) {
                    val h = m.groupValues[1].toIntOrNull()
                    if (h != null && h in 1..12) {
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:${"%02d:00".format(h)}")) {
                            ambiguousHourMinute = h to 0
                        }
                    }
                }
            }

        // 6b. Noon / Midday / Midnight
        Regex("\\b(?:(?:at|by|before|around|until)\\s+)?(noon|midday|midnight)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueTime == null && ambiguousHourMinute == null && isUnprotected(m.range)) {
                    val w = m.groupValues[1].lowercase()
                    val tm = if (w == "midnight") LocalTime.of(0, 0) else LocalTime.of(12, 0)
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                        inferredDueTime = tm
                    }
                }
            }

        // 6c. Explicit AM/PM & compact times: "9:30am", "9.30 pm", "9:30 a.m.", "930am", "5p", "9a", "by 2pm"
        Regex(
            "\\b(?:(?:at|by|before|around|until|~)\\s+)?(?:(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)|(\\d{1,2})(\\d{2})\\s*(am|pm)|(\\d{1,2})(?:[:.](\\d{2}))?(a|p))(?=$|\\s|[,;!?])",
            RegexOption.IGNORE_CASE
        ).findAll(input).forEach { m ->
            if (inferredDueTime == null && ambiguousHourMinute == null && isUnprotected(m.range)) {
                val tm = when {
                    m.groupValues[1].isNotEmpty() -> parseClockTokens(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    m.groupValues[4].isNotEmpty() -> parseClockTokens(m.groupValues[4], m.groupValues[5], m.groupValues[6])
                    else -> parseClockTokens(m.groupValues[7], m.groupValues[8], m.groupValues[9])
                }
                if (tm != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                    inferredDueTime = tm
                }
            }
        }

        // 6d. Military / 4-digit 24h times ("1430h", "0930hrs", "at 1430", "by 1730" excluding 1900..2099 years)
        Regex("\\b(?:(?:at|by|before|around|until|~)\\s+)?([01]\\d|2[0-3])([0-5]\\d)\\s*(?:h|hrs?)\\b|\\b(?:at|by|before|around|until|~)\\s+([01]\\d|2[0-3])([0-5]\\d)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueTime == null && ambiguousHourMinute == null && isUnprotected(m.range)) {
                    val (hStr, mStr, hadUnit) = if (m.groupValues[1].isNotEmpty()) {
                        Triple(m.groupValues[1], m.groupValues[2], true)
                    } else {
                        Triple(m.groupValues[3], m.groupValues[4], false)
                    }
                    val fullNum = (hStr + mStr).toIntOrNull() ?: 0
                    val looksLikeYear = !hadUnit && fullNum in 1900..2099
                    if (!looksLikeYear) {
                        val tm = parseClockTokens(hStr, mStr, "")
                        if (tm != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                            inferredDueTime = tm
                        }
                    }
                }
            }

        // 6e. Colon or dotted times without am/pm ("14:30", "09:00", "9:30", "2:15", "at 9.30", "by 5.15")
        Regex("\\b(?:(?:at|by|before|around|until|~)\\s+)?(\\d{1,2}):([0-5]\\d)\\b|\\b(?:at|by|before|around|until|~)\\s+(\\d{1,2})\\.([0-5]\\d)\\b", RegexOption.IGNORE_CASE)
            .findAll(input).forEach { m ->
                if (inferredDueTime == null && ambiguousHourMinute == null && isUnprotected(m.range)) {
                    val (hStr, mStr) = if (m.groupValues[1].isNotEmpty()) {
                        m.groupValues[1] to m.groupValues[2]
                    } else {
                        m.groupValues[3] to m.groupValues[4]
                    }
                    val h = hStr.toIntOrNull()
                    val min = mStr.toIntOrNull()
                    if (h != null && min != null && h in 0..23 && min in 0..59) {
                        val isExplicit24h = h == 0 || h >= 13 || (hStr.length == 2 && hStr.startsWith("0"))
                        if (isExplicit24h) {
                            val tm = LocalTime.of(h, min)
                            if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                                inferredDueTime = tm
                            }
                        } else if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:${"%02d:%02d".format(h, min)}")) {
                            ambiguousHourMinute = h to min
                        }
                    }
                }
            }

        // 6f. Bare clock hours with time preposition ("do this at 9", "submit by 5", "meeting around 3", "gym at 6 30")
        Regex(
            "\\b(?:at|by|before|around|until|~)\\s+(\\d{1,2})(?:\\s+([0-5]\\d))?\\b$NON_TIME_UNIT_LOOKAHEAD",
            RegexOption.IGNORE_CASE
        ).findAll(input).forEach { m ->
            if (inferredDueTime == null && ambiguousHourMinute == null && isUnprotected(m.range)) {
                val h = m.groupValues[1].toIntOrNull()
                val min = m.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: 0
                if (h != null && h in 1..23 && min in 0..59) {
                    if (h >= 13) {
                        val tm = LocalTime.of(h, min)
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                            inferredDueTime = tm
                        }
                    } else if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:${"%02d:%02d".format(h, min)}")) {
                        ambiguousHourMinute = h to min
                    }
                }
            }
        }

        // 6g. Workday & everyday named periods ("eod", "cob", "tonight", "2nite", "after lunch", "before lunch", "after work", "before bed")
        Regex("\\b(?:(?:by|before|until)\\s+)?(eod|end\\s+of\\s+(?:the\\s+)?day|cob|close\\s+of\\s+business)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val tm = rules.eodTime
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                        if (inferredDueTime == null && ambiguousHourMinute == null) inferredDueTime = tm
                        if (inferredDueDate == null) {
                            inferredDueDate = if (now.toLocalTime().isBefore(tm)) today else today.plusDays(1)
                        }
                    }
                }
            }

        Regex("\\b(tonight|tonite|2nite|after\\s+lunch|before\\s+lunch|at\\s+lunch|lunchtime|after\\s+work|before\\s+bed|bedtime)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val phrase = m.groupValues[1].lowercase().replace(Regex("\\s+"), "_")
                    val (tm, hint) = when (phrase) {
                        "tonight", "tonite", "2nite" -> rules.tonightTime to "night"
                        "after_lunch" -> rules.afterLunchTime to "afternoon"
                        "before_lunch" -> LocalTime.of(11, 30) to "morning"
                        "at_lunch", "lunchtime" -> LocalTime.of(12, 30) to "afternoon"
                        "after_work" -> LocalTime.of(18, 0) to "evening"
                        else -> LocalTime.of(22, 0) to "night"
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$tm")) {
                        periodHint = hint
                        if (inferredDueTime == null && ambiguousHourMinute == null) inferredDueTime = tm
                        if (inferredDueDate == null) {
                            inferredDueDate = if (now.toLocalTime().isBefore(tm)) today else today.plusDays(1)
                        }
                    }
                }
            }

        // =====================================================================
        // PASS 7: Calendar & Relative Dates (abbreviations, milestones, numeric, weekdays)
        // =====================================================================
        // 7a. "day after tomorrow" / "overmorrow"
        Regex("\\b(?:(?:by|before|until|on)\\s+)?(?:the\\s+)?(day\\s+after\\s+tomorrow|overmorrow)(?:\\s+(morning|afternoon|evening|night))?\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val baseD = today.plusDays(2)
                    val period = m.groupValues[2].lowercase()
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$baseD")) {
                        inferredDueDate = baseD
                        if (period.isNotEmpty()) {
                            periodHint = period
                            if (inferredDueTime == null && ambiguousHourMinute == null) {
                                inferredDueTime = periodToDefaultTime(period, rules)
                            }
                        }
                    }
                }
            }

        // 7b. "today" / "tdy" / "2day" / "tomorrow" / "tmr" / "tmrw" / "tomo" / "tomorow" / "2mrw" (+ optional period)
        Regex("\\b(?:(?:by|before|until|on)\\s+)?(today|tdy|2day|tomorrow|tmrw|tmr|tomo|tomorow|2mrw)(?:\\s+(morning|afternoon|evening|night))?\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val tok = m.groupValues[1].lowercase()
                    val isTomorrow = tok in setOf("tomorrow", "tmr", "tmrw", "tomo", "tomorow", "2mrw")
                    val baseD = if (isTomorrow) today.plusDays(1) else today
                    val period = m.groupValues[2].lowercase()
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$baseD")) {
                        inferredDueDate = baseD
                        if (period.isNotEmpty()) {
                            periodHint = period
                            if (inferredDueTime == null && ambiguousHourMinute == null) {
                                inferredDueTime = periodToDefaultTime(period, rules)
                            }
                        }
                    }
                }
            }

        // 7c. "this morning / this afternoon / this evening / later today"
        Regex("\\b(this\\s+(morning|afternoon|evening)|later\\s+today)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (isUnprotected(m.range)) {
                    val period = m.groupValues[2].lowercase()
                    val fallbackTime = when {
                        period.isNotEmpty() -> periodToDefaultTime(period, rules)
                        else -> now.plusHours(2).toLocalTime().withSecond(0).withNano(0)
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$today")) {
                        if (inferredDueDate == null) inferredDueDate = today
                        if (period.isNotEmpty()) periodHint = period
                        if (inferredDueTime == null && ambiguousHourMinute == null && fallbackTime != null) {
                            inferredDueTime = fallbackTime
                        }
                    }
                }
            }

        // 7d. Milestones: eow, eom, this/next weekend, next week, next month
        Regex("\\b(?:(?:by|before|until|on)\\s+)?(eow|end\\s+of\\s+(?:the\\s+)?week|eom|end\\s+of\\s+(?:the\\s+)?month|next\\s+weekend|this\\s+weekend|next\\s+week|next\\s+month)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val phrase = m.groupValues[1].lowercase().replace(Regex("\\s+"), " ")
                    val (d, defaultTm) = when {
                        phrase == "eow" || phrase.endsWith("of week") || phrase.endsWith("of the week") -> {
                            val fri = if (today.dayOfWeek == DayOfWeek.FRIDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
                            fri to rules.eodTime
                        }
                        phrase == "eom" || phrase.contains("of month") || phrase.contains("of the month") ->
                            today.with(TemporalAdjusters.lastDayOfMonth()) to null
                        phrase == "next weekend" ->
                            today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).plusWeeks(1) to null
                        phrase == "this weekend" -> {
                            val sat = if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today
                            else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
                            sat to null
                        }
                        phrase == "next month" -> today.plusMonths(1).withDayOfMonth(1) to null
                        else -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) to null
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) {
                        inferredDueDate = d
                        if (inferredDueTime == null && ambiguousHourMinute == null && defaultTm != null) {
                            inferredDueTime = defaultTm
                        }
                    }
                }
            }

        // 7e. Bare ISO date ("2026-11-25")
        Regex("\\b(?:(?:by|before|until|on|due)\\s+)?(\\d{4})-(\\d{2})-(\\d{2})\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val d = runCatching {
                        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                    }.getOrNull()
                    if (d != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) {
                        inferredDueDate = d
                    }
                }
            }

        // 7f. Slash numeric dates ("25/11", "11/25", "11/25/2026", "25/11/26")
        Regex("\\b(?:(?:by|before|until|on|due)\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (inferredDueDate == null && isUnprotected(m.range)) {
                    val a = m.groupValues[1].toIntOrNull()
                    val b = m.groupValues[2].toIntOrNull()
                    val rawYear = m.groupValues[3].toIntOrNull()
                    val explicitYear = when {
                        rawYear == null -> null
                        rawYear < 100 -> 2000 + rawYear
                        else -> rawYear
                    }
                    if (a != null && b != null && a in 1..31 && b in 1..31) {
                        val (monthNum, dayNum) = when {
                            a > 12 && b in 1..12 -> b to a // Unambiguous DD/MM (e.g. 25/11)
                            b > 12 && a in 1..12 -> a to b // Unambiguous MM/DD (e.g. 11/25)
                            a in 1..12 && b in 1..31 -> a to b // Default MM/DD
                            else -> null to null
                        }
                        if (monthNum != null && dayNum != null) {
                            val yr = explicitYear ?: today.year
                            var candidate = runCatching { LocalDate.of(yr, monthNum, dayNum) }.getOrNull()
                            if (explicitYear == null && candidate != null && candidate.isBefore(today)) {
                                candidate = runCatching { LocalDate.of(today.year + 1, monthNum, dayNum) }.getOrNull()
                            }
                            if (candidate != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$candidate")) {
                                inferredDueDate = candidate
                            }
                        }
                    }
                }
            }

        // 7g. Ordinal day ("the 15th", "by the 1st")
        Regex("\\b(?:(?:by|before|until|on)\\s+)?the\\s+(\\d{1,2})(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)
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

        // 7h. Named month + day or day + month ("Nov 15", "15 Nov", "25-Nov", "Nov 15, 2026") + optional period
        Regex(
            "\\b(?:(?:by|before|until|on)\\s+)?(?:($MONTH_ALT)[\\s-]+(\\d{1,2})(?:st|nd|rd|th)?|(\\d{1,2})(?:st|nd|rd|th)?[\\s-]+($MONTH_ALT))(?:,?\\s*(20\\d{2}))?(?:\\s+(morning|afternoon|evening|night))?\\b",
            RegexOption.IGNORE_CASE
        ).find(input)?.let { m ->
            if (inferredDueDate == null && isUnprotected(m.range)) {
                val mName = m.groupValues[1].ifEmpty { m.groupValues[4] }.lowercase()
                val dNum = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull()
                val explicitYr = m.groupValues[5].toIntOrNull()
                val period = m.groupValues[6].lowercase()
                val monthNum = MONTH_MAP[mName]
                if (monthNum != null && dNum != null && dNum in 1..31) {
                    val yr = explicitYr ?: today.year
                    var candidate = runCatching { LocalDate.of(yr, monthNum, dNum) }.getOrNull()
                    if (explicitYr == null && candidate != null && candidate.isBefore(today)) {
                        candidate = runCatching { LocalDate.of(today.year + 1, monthNum, dNum) }.getOrNull()
                    }
                    if (candidate != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$candidate")) {
                        inferredDueDate = candidate
                        if (period.isNotEmpty()) {
                            periodHint = period
                            if (inferredDueTime == null && ambiguousHourMinute == null) {
                                inferredDueTime = periodToDefaultTime(period, rules)
                            }
                        }
                    }
                }
            }
        }

        // 7i. Weekday ("Tuesday", "next Tuesday", "by Friday", "Fri morning", "this Thu evening")
        Regex(
            "\\b(?:(by|before|until|next|this|on)\\s+)?($WEEKDAY_ALT)(?:\\s+(morning|afternoon|evening|night))?\\b",
            RegexOption.IGNORE_CASE
        ).find(input)?.let { m ->
            if (inferredDueDate == null && isUnprotected(m.range)) {
                val mod = m.groupValues[1].lowercase()
                val dow = WEEKDAY_MAP[m.groupValues[2].lowercase()]
                val period = m.groupValues[3].lowercase()
                if (dow != null) {
                    val d = if (mod == "next") {
                        today.with(TemporalAdjusters.next(dow))
                    } else {
                        pendingBareWeekday = dow
                        resolveBareWeekday(today, now.toLocalTime(), dow, inferredDueTime ?: explicitDueTime)
                    }
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "due:$d")) {
                        inferredDueDate = d
                        if (period.isNotEmpty()) {
                            periodHint = period
                            if (inferredDueTime == null && ambiguousHourMinute == null) {
                                inferredDueTime = periodToDefaultTime(period, rules)
                            }
                        }
                    }
                }
            }
        }

        // 7j. Standalone period ("in the morning", "in the evening", "morning", "afternoon", "evening")
        Regex("\\b(?:in\\s+the\\s+)?(morning|afternoon|evening)\\b", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (periodHint == null && isUnprotected(m.range)) {
                    val period = m.groupValues[1].lowercase()
                    val defaultTm = periodToDefaultTime(period, rules)
                    if (defaultTm != null && addSpan(m.range.first, m.range.last + 1, SpanCategory.DATE_TIME, "duetime:$defaultTm")) {
                        periodHint = period
                        if (inferredDueTime == null && ambiguousHourMinute == null) {
                            inferredDueTime = defaultTm
                        }
                    }
                }
            }

        // =====================================================================
        // PASS 7k: Finalize Smart AM/PM Resolution & Date Rollover
        // =====================================================================
        if (inferredDueTime == null && ambiguousHourMinute != null) {
            val (ambH, ambM) = ambiguousHourMinute!!
            inferredDueTime = resolveSmartAmPm(
                hour12 = ambH,
                minute = ambM,
                periodHint = periodHint,
                targetDate = explicitDueDate ?: inferredDueDate,
                today = today,
                currentTime = now.toLocalTime()
            )
            // Re-check bare weekday if time was finalized after weekday resolution
            if (pendingBareWeekday != null && explicitDueDate == null) {
                inferredDueDate = resolveBareWeekday(today, now.toLocalTime(), pendingBareWeekday!!, inferredDueTime)
            }
        }

        if (inferredDueDate == null && explicitDueDate == null && (inferredDueTime != null || explicitDueTime != null)) {
            val targetTime = explicitDueTime ?: inferredDueTime!!
            inferredDueDate = if (targetTime.isAfter(now.toLocalTime())) today else today.plusDays(1)
        }

        // =====================================================================
        // PASS 8: Priority keywords
        // =====================================================================
        var inferredPriority: Char? = null
        Regex("(?<=^|\\s)(!!|!|\\burgent\\b|\\basap\\b|\\bimportant\\b|\\bhigh\\s+priority\\b)(?=\\s|$)", RegexOption.IGNORE_CASE)
            .find(input)?.let { m ->
                if (explicitPriority == null && isUnprotected(m.range)) {
                    val tok = m.groupValues[1].lowercase()
                    val p = if (tok == "important" || tok == "!") 'B' else 'A'
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.PRIORITY, "($p)")) {
                        inferredPriority = p
                    }
                }
            }

        // =====================================================================
        // PASS 9 & 10: Scored 6-Signal Context (@) & Project (+) Inference
        // =====================================================================
        val tagResult = TagSuggestionEngine.inferTags(
            input = input,
            rules = rules,
            explicitProjects = explicitProjects,
            explicitContexts = explicitContexts,
            consumedMask = consumedMask,
            protectedMask = protectedMask,
            addSpan = ::addSpan
        )

        // Build clean stripped title
        val rawTitleChars = StringBuilder()
        for (i in input.indices) {
            if (!consumedMask[i]) rawTitleChars.append(input[i])
        }
        val strippedTitle = rawTitleChars.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^\\s*(?:to|and)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:at|on|by|in|for|to|around|before|until|~)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { input.trim() }

        val finalPriority = explicitPriority ?: inferredPriority
        val finalDueDate = explicitDueDate ?: inferredDueDate
        val finalDueTime = explicitDueTime ?: inferredDueTime
        val finalThreshold = explicitThreshold ?: inferredThreshold
        val finalRecurrence = explicitRecurrence ?: inferredRecurrence
        val finalProjects = (explicitProjects + tagResult.inferredProjects).distinct()
        val finalContexts = (explicitContexts + tagResult.inferredContexts).distinct()

        val finalReminders = when {
            suppressReminder -> emptyList()
            explicitReminders.isNotEmpty() || inferredReminders.isNotEmpty() -> (explicitReminders + inferredReminders).distinct()
            hasImmediateRelativeOffset || hasLeadingReminderIntent -> listOf("0m/$leadingReminderKind")
            finalDueDate != null && finalDueTime != null -> listOf(rules.timedReminderDefault)
            finalDueDate != null -> listOf(rules.dateOnlyReminderDefault)
            else -> emptyList()
        }

        val lineParts = mutableListOf<String>()
        if (finalPriority != null) lineParts.add("($finalPriority)")
        lineParts.add(today.toString())
        lineParts.add(strippedTitle)
        finalProjects.forEach { lineParts.add("+$it") }
        finalContexts.forEach { lineParts.add("@$it") }
        if (finalDueDate != null) lineParts.add("due:$finalDueDate")
        if (finalDueTime != null) lineParts.add("duetime:${"%02d:%02d".format(finalDueTime.hour, finalDueTime.minute)}")
        if (finalThreshold != null) lineParts.add("t:$finalThreshold")
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
            exactOffsetMillis = exactOffsetMillis,
            thresholdDate = finalThreshold,
            durationMinutes = inferredDurationMinutes,
            recurrence = finalRecurrence,
            projects = finalProjects,
            contexts = finalContexts,
            reminders = finalReminders,
            suppressReminder = suppressReminder,
            passthroughTags = passthroughTags,
            recognizedSpans = spans.sortedBy { it.startIndex },
            suggestedTags = tagResult.suggestedTags,
            formattedTodoLine = lineParts.joinToString(" ")
        )
    }

    private fun periodToDefaultTime(period: String, rules: TickRulesConfig): LocalTime? = when (period) {
        "morning" -> rules.morningTime
        "afternoon" -> rules.afternoonTime
        "evening" -> rules.eveningTime
        "night" -> rules.tonightTime
        else -> null
    }

    private fun parseClockTokens(hourStr: String, minStr: String?, meridiemStr: String?): LocalTime? {
        var h = hourStr.toIntOrNull() ?: return null
        val m = minStr?.ifEmpty { "0" }?.toIntOrNull() ?: 0
        val mer = meridiemStr?.lowercase()?.replace(".", "") ?: ""
        if (m !in 0..59) return null
        val isPm = mer == "pm" || mer == "p"
        val isAm = mer == "am" || mer == "a"
        if (isPm && h in 1..11) h += 12
        if (isAm && h == 12) h = 0
        if (h !in 0..23) return null
        return LocalTime.of(h, m)
    }

    /**
     * Human-centric AM/PM disambiguation for meridiem-less times ("at 9", "by 5", "half past 2", "call bob 8:30"):
     * 1. Explicit period hint (`morning` -> AM, `afternoon`/`evening`/`night` -> PM) always wins.
     * 2. Future date specified (`tomorrow by 5`, `friday at 9`): `1..6` -> PM (`13..18`), `7..12` -> `07..12`.
     * 3. Same-day / no date specified:
     *    - `12` -> `12:00`
     *    - `1..6` -> PM (`13..18`)
     *    - `7..11`: AM if still ahead today; else PM if still ahead today and `<= 22:59`; else AM (rolls to tomorrow).
     */
    internal fun resolveSmartAmPm(
        hour12: Int,
        minute: Int,
        periodHint: String?,
        targetDate: LocalDate?,
        today: LocalDate,
        currentTime: LocalTime
    ): LocalTime {
        val h = hour12.coerceIn(1, 12)
        val m = minute.coerceIn(0, 59)

        when (periodHint) {
            "morning" -> return LocalTime.of(if (h == 12) 0 else h, m)
            "afternoon", "evening", "night" -> return LocalTime.of(if (h == 12) 12 else h + 12, m)
        }

        if (h == 12) return LocalTime.of(12, m)

        val isFutureDate = targetDate != null && targetDate.isAfter(today)
        if (isFutureDate) {
            return if (h in 1..6) LocalTime.of(h + 12, m) else LocalTime.of(h, m)
        }

        if (h in 1..6) {
            return LocalTime.of(h + 12, m)
        }

        // h in 7..11 for today / unspecified date
        val amCandidate = LocalTime.of(h, m)
        val pmCandidate = LocalTime.of(h + 12, m)
        return when {
            amCandidate.isAfter(currentTime) -> amCandidate
            h <= 10 && pmCandidate.isAfter(currentTime) -> pmCandidate
            else -> amCandidate
        }
    }

    private fun resolveBareWeekday(
        today: LocalDate,
        currentTime: LocalTime,
        targetDow: DayOfWeek,
        targetTime: LocalTime?
    ): LocalDate {
        if (today.dayOfWeek == targetDow) {
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
