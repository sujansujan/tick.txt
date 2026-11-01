package com.ticktxt.app.core.nlp

import java.time.LocalTime

data class TickRulesConfig(
    val timedReminderDefault: String = "-15m/n",
    val dateOnlyReminderDefault: String = "09:00/n",
    val morningTime: LocalTime = LocalTime.of(9, 0),
    val afternoonTime: LocalTime = LocalTime.of(14, 0),
    val eveningTime: LocalTime = LocalTime.of(19, 0),
    val tonightTime: LocalTime = LocalTime.of(20, 0),
    val afterLunchTime: LocalTime = LocalTime.of(13, 0),
    val snoozePresets: List<String> = listOf("5m", "15m", "1h", "tomorrow_morning"),
    val maxSnoozes: Int = 5,
    val keywordToProject: Map<String, String> = DEFAULT_PROJECT_RULES,
    val phraseToContext: Map<String, String> = DEFAULT_CONTEXT_RULES
) {
    companion object {
        val DEFAULT_PROJECT_RULES = linkedMapOf(
            "mum" to "family",
            "mom" to "family",
            "dad" to "family",
            "sister" to "family",
            "brother" to "family",
            "parents" to "family",
            "family" to "family",
            "invoice" to "work",
            "client" to "work",
            "standup" to "work",
            "deploy" to "work",
            "sprint" to "work",
            "milk" to "groceries",
            "bread" to "groceries",
            "eggs" to "groceries",
            "groceries" to "groceries",
            "dentist" to "health",
            "doctor" to "health",
            "gym" to "health"
        )

        val DEFAULT_CONTEXT_RULES = linkedMapOf(
            "at home" to "home",
            "at the office" to "office",
            "in the office" to "office",
            "at work" to "office",
            "at the shop" to "errands",
            "at the store" to "errands",
            "on phone" to "phone",
            "by phone" to "phone",
            "on a call" to "phone"
        )

        val DEFAULT_RULES_FILE_CONTENT = """
            # tick.rules.txt — plain text, human-editable rules for tick.txt
            # Edit by hand or let tick.txt append rules when you correct a smart-add chip.

            [defaults]
            timed_reminder = -15m/n
            date_only_reminder = 09:00/n
            morning = 09:00
            afternoon = 14:00
            evening = 19:00
            tonight = 20:00
            after_lunch = 13:00
            snooze_presets = 5m, 15m, 1h, tomorrow_morning
            max_snoozes = 5

            [projects]
            mum, mom, dad, sister, brother, parents, family -> +family
            invoice, client, standup, deploy, sprint -> +work
            milk, bread, eggs, groceries -> +groceries
            dentist, doctor, gym -> +health

            [contexts]
            "at home" -> @home
            "at the office", "in the office", "at work" -> @office
            "at the shop", "at the store" -> @errands
            "on phone", "by phone", "on a call" -> @phone
        """.trimIndent() + "\n"
    }
}

/**
 * Parses and updates `tick.rules.txt`.
 * Supports human comments (`#`), `[defaults]`, `[projects]`, and `[contexts]` sections,
 * and allows appending learned keyword associations when a user corrects a guess.
 */
object RulesEngine {

    fun parse(content: String): TickRulesConfig {
        if (content.isBlank()) return TickRulesConfig()

        var section = ""
        var timedRem = "-15m/n"
        var dateOnlyRem = "09:00/n"
        var morning = LocalTime.of(9, 0)
        var afternoon = LocalTime.of(14, 0)
        var evening = LocalTime.of(19, 0)
        var tonight = LocalTime.of(20, 0)
        var afterLunch = LocalTime.of(13, 0)
        var snoozePresets = listOf("5m", "15m", "1h", "tomorrow_morning")
        var maxSnoozes = 5

        val projects = LinkedHashMap<String, String>(TickRulesConfig.DEFAULT_PROJECT_RULES)
        val contexts = LinkedHashMap<String, String>(TickRulesConfig.DEFAULT_CONTEXT_RULES)

        for (rawLine in content.lines()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).lowercase().trim()
                continue
            }

            when (section) {
                "defaults" -> {
                    val parts = line.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2) {
                        val k = parts[0].lowercase()
                        val v = parts[1]
                        when (k) {
                            "timed_reminder" -> timedRem = v
                            "date_only_reminder" -> dateOnlyRem = v
                            "morning" -> parseTime(v)?.let { morning = it }
                            "afternoon" -> parseTime(v)?.let { afternoon = it }
                            "evening" -> parseTime(v)?.let { evening = it }
                            "tonight" -> parseTime(v)?.let { tonight = it }
                            "after_lunch" -> parseTime(v)?.let { afterLunch = it }
                            "snooze_presets" -> snoozePresets = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            "max_snoozes" -> v.toIntOrNull()?.let { maxSnoozes = it }
                        }
                    }
                }
                "projects" -> {
                    val parts = line.split("->", limit = 2).map { it.trim() }
                    if (parts.size == 2) {
                        val targetProject = parts[1].removePrefix("+").trim()
                        val triggers = parts[0].split(",").map { it.trim().trim('"').lowercase() }
                        for (t in triggers) {
                            if (t.isNotEmpty() && targetProject.isNotEmpty()) {
                                projects[t] = targetProject
                            }
                        }
                    }
                }
                "contexts" -> {
                    val parts = line.split("->", limit = 2).map { it.trim() }
                    if (parts.size == 2) {
                        val targetContext = parts[1].removePrefix("@").trim()
                        val triggers = parts[0].split(",").map { it.trim().trim('"').lowercase() }
                        for (t in triggers) {
                            if (t.isNotEmpty() && targetContext.isNotEmpty()) {
                                contexts[t] = targetContext
                            }
                        }
                    }
                }
            }
        }

        return TickRulesConfig(
            timedReminderDefault = timedRem,
            dateOnlyReminderDefault = dateOnlyRem,
            morningTime = morning,
            afternoonTime = afternoon,
            eveningTime = evening,
            tonightTime = tonight,
            afterLunchTime = afterLunch,
            snoozePresets = snoozePresets,
            maxSnoozes = maxSnoozes,
            keywordToProject = projects,
            phraseToContext = contexts
        )
    }

    fun appendLearnedProjectRule(existingContent: String, keyword: String, project: String): String {
        val cleanKeyword = keyword.trim().lowercase()
        val cleanProject = project.removePrefix("+").trim()
        val ruleLine = "$cleanKeyword -> +$cleanProject"
        return if ("[projects]" in existingContent) {
            existingContent.replaceFirst("[projects]", "[projects]\n$ruleLine")
        } else {
            existingContent.trimEnd() + "\n\n[projects]\n$ruleLine\n"
        }
    }

    private fun parseTime(s: String): LocalTime? {
        return try {
            LocalTime.parse(s.trim())
        } catch (_: Exception) {
            null
        }
    }
}
