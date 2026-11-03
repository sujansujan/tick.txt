package com.ticktxt.app.core.nlp

import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
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
    val phraseToContext: Map<String, String> = DEFAULT_CONTEXT_RULES,
    val wordToContext: Map<String, String> = emptyMap(),
    val knownProjects: Set<String> = emptySet(),
    val knownContexts: Set<String> = emptySet()
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
            "report" to "work",
            "presentation" to "work",
            "milk" to "groceries",
            "bread" to "groceries",
            "eggs" to "groceries",
            "groceries" to "groceries",
            "dentist" to "health",
            "doctor" to "health",
            "gym" to "health",
            "workout" to "health",
            "medicine" to "health",
            "rent" to "finance",
            "tax" to "finance",
            "taxes" to "finance",
            "budget" to "finance"
        )

        val DEFAULT_CONTEXT_RULES = linkedMapOf(
            "at home" to "home",
            "at the office" to "office",
            "in the office" to "office",
            "at work" to "office",
            "at the shop" to "errands",
            "at the store" to "errands",
            "at the market" to "errands",
            "at supermarket" to "errands",
            "on phone" to "phone",
            "by phone" to "phone",
            "on a call" to "phone",
            "by email" to "email",
            "via email" to "email",
            "at desk" to "desk",
            "on laptop" to "desk"
        )

        val DEFAULT_RULES_FILE_CONTENT = """
            # tick.rules.txt — plain text, human-editable rules for tick.txt
            # Edit by hand or let tick.txt append rules automatically as you tag tasks.

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
            invoice, client, standup, deploy, sprint, report, presentation -> +work
            milk, bread, eggs, groceries -> +groceries
            dentist, doctor, gym, workout, medicine -> +health
            rent, tax, taxes, budget -> +finance

            [contexts]
            "at home" -> @home
            "at the office", "in the office", "at work" -> @office
            "at the shop", "at the store", "at the market" -> @errands
            "on phone", "by phone", "on a call" -> @phone
            "by email", "via email" -> @email
            "at desk", "on laptop" -> @desk
        """.trimIndent() + "\n"

        val LEARNING_STOPWORDS = setOf(
            "the", "and", "for", "with", "from", "into", "about", "after", "before",
            "check", "review", "fix", "build", "update", "call", "email", "pay", "buy",
            "clean", "finish", "prepare", "send", "test", "visit", "inspect", "audit",
            "debug", "deploy", "write", "plan", "schedule", "order", "pickup", "pick",
            "meet", "talk", "work", "follow", "remind", "today", "tomorrow", "tonight",
            "morning", "afternoon", "evening", "night", "monday", "tuesday", "wednesday",
            "thursday", "friday", "saturday", "sunday", "task", "todo", "new", "some"
        )
    }
}

/**
 * Parses and updates `tick.rules.txt`, and builds a self-learning co-occurrence model
 * from the user's existing `todo.txt` and `done.txt` history (zero LLM required).
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

    /**
     * Layer 2 & 3 Offline Intelligence:
     * Scans existing `todo.txt` + `done.txt` tasks to extract:
     * 1. Known `+projects` and `@contexts` vocabulary (for direct entity matching)
     * 2. Co-occurrence frequencies `P(+project | word)` and `P(@context | word)` so words that
     *    historically appeared with a project or context automatically infer those tags!
     */
    fun withHistoricalLearning(
        baseConfig: TickRulesConfig,
        openTasks: List<TodoTask>,
        doneLines: List<String>
    ): TickRulesConfig {
        val allTasks = ArrayList<TodoTask>(openTasks.size + doneLines.size)
        allTasks.addAll(openTasks)
        doneLines.forEachIndexed { idx, line ->
            if (line.isNotBlank()) {
                allTasks.add(TodoParser.parseLine(line, idx + 1))
            }
        }

        val knownProjs = LinkedHashSet<String>()
        val knownCtxs = LinkedHashSet<String>()
        val projCounts = HashMap<String, HashMap<String, Int>>()
        val ctxCounts = HashMap<String, HashMap<String, Int>>()

        for (task in allTasks) {
            if (task.isBlankOrComment) continue
            task.projects.forEach { knownProjs.add(it.lowercase()) }
            task.contexts.forEach { knownCtxs.add(it.lowercase()) }

            if (task.projects.isEmpty() && task.contexts.isEmpty()) continue
            val words = task.description
                .lowercase()
                .split(Regex("[^a-z0-9_-]+"))
                .filter { it.length >= 3 && it !in TickRulesConfig.LEARNING_STOPWORDS }

            for (w in words) {
                for (p in task.projects) {
                    val pMap = projCounts.getOrPut(w) { HashMap() }
                    pMap[p] = (pMap[p] ?: 0) + 1
                }
                for (c in task.contexts) {
                    val cMap = ctxCounts.getOrPut(w) { HashMap() }
                    cMap[c] = (cMap[c] ?: 0) + 1
                }
            }
        }

        val mergedProjects = LinkedHashMap<String, String>(baseConfig.keywordToProject)
        for ((word, map) in projCounts) {
            if (word !in mergedProjects) {
                val best = map.maxByOrNull { it.value }?.key
                if (best != null) {
                    mergedProjects[word] = best
                }
            }
        }

        val learnedWordToContext = LinkedHashMap<String, String>()
        for ((word, map) in ctxCounts) {
            val best = map.maxByOrNull { it.value }?.key
            if (best != null) {
                learnedWordToContext[word] = best
            }
        }

        return baseConfig.copy(
            keywordToProject = mergedProjects,
            wordToContext = learnedWordToContext,
            knownProjects = knownProjs,
            knownContexts = knownCtxs
        )
    }

    /**
     * Layer 4 Feedback Learning:
     * When the user saves or tags a task with a `+project` or `@context`, extracts the primary
     * topic noun and appends the learned mapping to `tick.rules.txt` if not already present.
     */
    fun learnFromTask(existingContent: String, task: TodoTask): String {
        val candidateWords = task.description
            .lowercase()
            .split(Regex("[^a-z0-9_-]+"))
            .filter { it.length >= 3 && it !in TickRulesConfig.LEARNING_STOPWORDS }
        val primaryWord = candidateWords.firstOrNull() ?: return existingContent

        var updated = existingContent
        val currentParsed = parse(existingContent)

        val firstProj = task.projects.firstOrNull()
        if (firstProj != null && primaryWord !in currentParsed.keywordToProject && !primaryWord.equals(firstProj, ignoreCase = true)) {
            updated = appendLearnedProjectRule(updated, primaryWord, firstProj)
        }

        val firstCtx = task.contexts.firstOrNull()
        if (firstCtx != null && "at $primaryWord" !in currentParsed.phraseToContext && !primaryWord.equals(firstCtx, ignoreCase = true)) {
            updated = appendLearnedContextRule(updated, primaryWord, firstCtx)
        }

        return updated
    }

    fun appendLearnedProjectRule(existingContent: String, keyword: String, project: String): String {
        val cleanKeyword = keyword.trim().lowercase()
        val cleanProject = project.removePrefix("+").trim()
        val ruleLine = "$cleanKeyword -> +$cleanProject"
        if (existingContent.contains(ruleLine, ignoreCase = true)) return existingContent
        return if ("[projects]" in existingContent) {
            existingContent.replaceFirst("[projects]", "[projects]\n$ruleLine")
        } else {
            existingContent.trimEnd() + "\n\n[projects]\n$ruleLine\n"
        }
    }

    fun appendLearnedContextRule(existingContent: String, phrase: String, contextTag: String): String {
        val cleanPhrase = phrase.trim().lowercase()
        val cleanContext = contextTag.removePrefix("@").trim()
        val ruleLine = "\"$cleanPhrase\" -> @$cleanContext"
        if (existingContent.contains(ruleLine, ignoreCase = true)) return existingContent
        return if ("[contexts]" in existingContent) {
            existingContent.replaceFirst("[contexts]", "[contexts]\n$ruleLine")
        } else {
            existingContent.trimEnd() + "\n\n[contexts]\n$ruleLine\n"
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
