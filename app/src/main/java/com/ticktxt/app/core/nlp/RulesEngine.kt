package com.ticktxt.app.core.nlp

import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import java.time.LocalTime
import kotlin.math.ln

/**
 * Statistical offline co-occurrence model learned from `todo.txt` (recency weight 1.5)
 * and `done.txt` (history weight 1.0). Captures:
 * - Unigram & bigram token -> `+project` / `@context` weighted counts
 * - Total token occurrences (for smoothed precision `count / (total + 1)`)
 * - Cross-tag workflow affinity (`P(@context | +project)` and `P(+project | @context)`)
 * - Global tag priors (so frequent personal tags surface as fallback suggestions)
 */
data class HistoricalTagModel(
    val tokenTotals: Map<String, Double> = emptyMap(),
    val tokenToProject: Map<String, Map<String, Double>> = emptyMap(),
    val tokenToContext: Map<String, Map<String, Double>> = emptyMap(),
    val projectToContext: Map<String, Map<String, Double>> = emptyMap(),
    val contextToProject: Map<String, Map<String, Double>> = emptyMap(),
    val projectTotals: Map<String, Double> = emptyMap(),
    val contextTotals: Map<String, Double> = emptyMap()
) {
    fun projectEvidenceForToken(token: String): Map<String, Double> {
        val counts = tokenToProject[token] ?: return emptyMap()
        val total = (tokenTotals[token] ?: 0.0).coerceAtLeast(1.0)
        return counts.mapValues { (_, c) ->
            val precision = c / (total + 1.0)
            val support = (ln(1.0 + c) / ln(4.0)).coerceIn(0.35, 1.0)
            precision * support
        }
    }

    fun contextEvidenceForToken(token: String): Map<String, Double> {
        val counts = tokenToContext[token] ?: return emptyMap()
        val total = (tokenTotals[token] ?: 0.0).coerceAtLeast(1.0)
        return counts.mapValues { (_, c) ->
            val precision = c / (total + 1.0)
            val support = (ln(1.0 + c) / ln(4.0)).coerceIn(0.35, 1.0)
            precision * support
        }
    }

    fun contextsForProject(project: String): Map<String, Double> {
        val counts = projectToContext[project.lowercase()] ?: return emptyMap()
        val total = (projectTotals[project.lowercase()] ?: 0.0).coerceAtLeast(1.0)
        return counts.mapValues { (_, c) ->
            val condProb = c / (total + 1.5)
            val support = (ln(1.0 + c) / ln(3.5)).coerceIn(0.3, 1.0)
            condProb * support
        }
    }

    fun projectsForContext(context: String): Map<String, Double> {
        val counts = contextToProject[context.lowercase()] ?: return emptyMap()
        val total = (contextTotals[context.lowercase()] ?: 0.0).coerceAtLeast(1.0)
        return counts.mapValues { (_, c) ->
            val condProb = c / (total + 1.5)
            val support = (ln(1.0 + c) / ln(3.5)).coerceIn(0.3, 1.0)
            condProb * support
        }
    }
}

data class TickRulesConfig(
    val timedReminderDefault: String = "-15m/n",
    val dateOnlyReminderDefault: String = "09:00/n",
    val morningTime: LocalTime = LocalTime.of(9, 0),
    val afternoonTime: LocalTime = LocalTime.of(14, 0),
    val eveningTime: LocalTime = LocalTime.of(19, 0),
    val tonightTime: LocalTime = LocalTime.of(20, 0),
    val afterLunchTime: LocalTime = LocalTime.of(13, 0),
    val eodTime: LocalTime = LocalTime.of(17, 0),
    val snoozePresets: List<String> = listOf("5m", "15m", "1h", "tomorrow_morning"),
    val maxSnoozes: Int = 5,
    val keywordToProject: Map<String, String> = DEFAULT_PROJECT_RULES,
    val phraseToContext: Map<String, String> = DEFAULT_CONTEXT_RULES,
    val wordToContext: Map<String, String> = emptyMap(),
    val knownProjects: Set<String> = emptySet(),
    val knownContexts: Set<String> = emptySet(),
    val historicalModel: HistoricalTagModel = HistoricalTagModel()
) {
    companion object {
        val DEFAULT_PROJECT_RULES = linkedMapOf(
            // Family
            "mum" to "family",
            "mom" to "family",
            "dad" to "family",
            "sister" to "family",
            "brother" to "family",
            "parents" to "family",
            "family" to "family",
            "grandma" to "family",
            "grandpa" to "family",
            "birthday" to "family",
            // Work
            "invoice" to "work",
            "client" to "work",
            "standup" to "work",
            "deploy" to "work",
            "sprint" to "work",
            "report" to "work",
            "presentation" to "work",
            "roadmap" to "work",
            "proposal" to "work",
            "slides" to "work",
            "release" to "work",
            "onboarding" to "work",
            "timesheet" to "work",
            "payroll" to "work",
            "deadline" to "work",
            "quarterly" to "work",
            // Groceries
            "milk" to "groceries",
            "bread" to "groceries",
            "eggs" to "groceries",
            "groceries" to "groceries",
            "grocery" to "groceries",
            "veggies" to "groceries",
            "vegetables" to "groceries",
            "fruits" to "groceries",
            "butter" to "groceries",
            "cheese" to "groceries",
            "supermarket" to "groceries",
            // Health
            "dentist" to "health",
            "doctor" to "health",
            "gym" to "health",
            "workout" to "health",
            "medicine" to "health",
            "prescription" to "health",
            "pharmacy" to "health",
            "therapist" to "health",
            "checkup" to "health",
            "vitamins" to "health",
            "clinic" to "health",
            // Finance
            "rent" to "finance",
            "tax" to "finance",
            "taxes" to "finance",
            "budget" to "finance",
            "mortgage" to "finance",
            "insurance" to "finance",
            "refund" to "finance",
            "bill" to "finance",
            "utility" to "finance",
            "bank" to "finance",
            "credit" to "finance",
            // Travel
            "flight" to "travel",
            "hotel" to "travel",
            "passport" to "travel",
            "visa" to "travel",
            "luggage" to "travel",
            "itinerary" to "travel",
            "airport" to "travel",
            // Study / Learning
            "homework" to "study",
            "assignment" to "study",
            "exam" to "study",
            "lecture" to "study",
            "thesis" to "study",
            "course" to "study",
            "textbook" to "study",
            // Home maintenance
            "laundry" to "home",
            "plumber" to "home",
            "electrician" to "home",
            "vacuum" to "home",
            "dishes" to "home",
            "trash" to "home"
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
            "at the mall" to "errands",
            "at the bank" to "errands",
            "at the pharmacy" to "errands",
            "on phone" to "phone",
            "by phone" to "phone",
            "on a call" to "phone",
            "on zoom" to "phone",
            "via zoom" to "phone",
            "on whatsapp" to "phone",
            "via whatsapp" to "phone",
            "by email" to "email",
            "via email" to "email",
            "over email" to "email",
            "at desk" to "desk",
            "at my desk" to "desk",
            "on laptop" to "desk",
            "on computer" to "desk",
            "on pc" to "desk",
            "waiting for" to "waiting",
            "waiting on" to "waiting",
            "at gym" to "gym",
            "at the gym" to "gym",
            "in the car" to "car",
            "online" to "online"
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
            eod = 17:00
            snooze_presets = 5m, 15m, 1h, tomorrow_morning
            max_snoozes = 5

            [projects]
            mum, mom, dad, sister, brother, parents, family -> +family
            invoice, client, standup, deploy, sprint, report, presentation -> +work
            milk, bread, eggs, groceries, supermarket -> +groceries
            dentist, doctor, gym, workout, medicine, pharmacy -> +health
            rent, tax, taxes, budget, bill, insurance -> +finance
            flight, hotel, passport, visa, airport -> +travel

            [contexts]
            "at home" -> @home
            "at the office", "in the office", "at work" -> @office
            "at the shop", "at the store", "at the market" -> @errands
            "on phone", "by phone", "on a call" -> @phone
            "by email", "via email" -> @email
            "at desk", "on laptop" -> @desk
            "waiting for", "waiting on" -> @waiting
        """.trimIndent() + "\n"

        val LEARNING_STOPWORDS = setOf(
            "the", "and", "for", "with", "from", "into", "about", "after", "before", "until",
            "around", "over", "under", "through", "between", "this", "that", "these", "those",
            "check", "review", "fix", "build", "update", "call", "email", "pay", "buy",
            "clean", "finish", "prepare", "send", "test", "visit", "inspect", "audit",
            "debug", "deploy", "write", "plan", "schedule", "order", "pickup", "pick",
            "meet", "talk", "work", "follow", "remind", "make", "get", "take", "give",
            "keep", "start", "stop", "open", "close", "move", "put", "set", "run", "try",
            "today", "tomorrow", "tonight", "tmr", "tmrw", "tod", "eod", "eow", "eom",
            "morning", "afternoon", "evening", "night", "noon", "midnight", "later",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "mon", "tue", "tues", "wed", "thu", "thurs", "fri", "sat", "sun",
            "task", "todo", "new", "some", "any", "all", "more", "most", "very", "just",
            "stuff", "thing", "things", "something", "everything", "nothing", "someone",
            "everyone", "anyone", "people", "again", "back", "still", "soon", "here", "there"
        )
    }
}

/**
 * Parses and updates `tick.rules.txt`, and builds a weighted statistical co-occurrence
 * and cross-tag affinity model from `todo.txt` + `done.txt` (100% offline, zero LLM).
 */
object RulesEngine {

    private val NON_STEM_SUFFIXES = setOf(
        "meeting", "morning", "evening", "nothing", "something", "everything",
        "parking", "packing", "shopping", "booking", "billing", "onboarding",
        "building", "wedding", "training", "marketing", "accounting", "cleaning",
        "clothes", "groceries", "vitamins", "glasses", "series", "species",
        "status", "campus", "focus", "bonus", "bus", "gas", "class", "pass", "glass",
        "bed", "red", "fed", "med", "wed", "need", "feed", "seed", "speed"
    )

    /**
     * Fast, conservative English normalizer/stemmer so plurals and inflections
     * ("invoices" -> "invoice", "groceries" -> "grocery", "deployed" -> "deploy",
     * "meetings" -> "meeting") match rules and history reliably without over-stemming.
     */
    fun normalizeStem(rawWord: String): String {
        val w = rawWord.lowercase().trim()
        if (w.length <= 3 || w in NON_STEM_SUFFIXES) return w
        return when {
            w.endsWith("ies") && w.length >= 5 -> w.dropLast(3) + "y"
            (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("zes") || w.endsWith("sses")) && w.length >= 5 ->
                w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") && w.length >= 4 ->
                w.dropLast(1)
            w.endsWith("ing") && w.length >= 6 && w !in NON_STEM_SUFFIXES ->
                w.dropLast(3)
            w.endsWith("ed") && w.length >= 5 && !w.endsWith("eed") && w !in NON_STEM_SUFFIXES ->
                w.dropLast(2)
            else -> w
        }
    }

    fun tokenizeForLearning(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9_-]+"))
            .filter { it.length >= 3 && !it.all { ch -> ch.isDigit() } && it !in TickRulesConfig.LEARNING_STOPWORDS }
    }

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
        var eod = LocalTime.of(17, 0)
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
                            "eod" -> parseTime(v)?.let { eod = it }
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
            eodTime = eod,
            snoozePresets = snoozePresets,
            maxSnoozes = maxSnoozes,
            keywordToProject = projects,
            phraseToContext = contexts
        )
    }

    /**
     * Builds the weighted statistical `HistoricalTagModel` across `todo.txt` (weight 1.5)
     * and `done.txt` (weight 1.0), indexing exact tokens, stems, bigrams, and cross-tag
     * `+project <-> @context` affinities.
     */
    fun withHistoricalLearning(
        baseConfig: TickRulesConfig,
        openTasks: List<TodoTask>,
        doneLines: List<String>
    ): TickRulesConfig {
        val weightedTasks = ArrayList<Pair<TodoTask, Double>>(openTasks.size + doneLines.size)
        openTasks.forEach { if (!it.isBlankOrComment) weightedTasks.add(it to 1.5) }
        doneLines.forEachIndexed { idx, line ->
            if (line.isNotBlank()) {
                val parsed = TodoParser.parseLine(line, idx + 1)
                if (!parsed.isBlankOrComment) weightedTasks.add(parsed to 1.0)
            }
        }

        val knownProjs = LinkedHashSet<String>()
        val knownCtxs = LinkedHashSet<String>()
        val tokenTotals = HashMap<String, Double>()
        val tokenToProject = HashMap<String, HashMap<String, Double>>()
        val tokenToContext = HashMap<String, HashMap<String, Double>>()
        val projectToContext = HashMap<String, HashMap<String, Double>>()
        val contextToProject = HashMap<String, HashMap<String, Double>>()
        val projectTotals = HashMap<String, Double>()
        val contextTotals = HashMap<String, Double>()

        fun addTokenFeatures(features: List<String>, projects: List<String>, contexts: List<String>, weight: Double) {
            for (feat in features) {
                tokenTotals[feat] = (tokenTotals[feat] ?: 0.0) + weight
                for (p in projects) {
                    val pMap = tokenToProject.getOrPut(feat) { HashMap() }
                    pMap[p] = (pMap[p] ?: 0.0) + weight
                }
                for (c in contexts) {
                    val cMap = tokenToContext.getOrPut(feat) { HashMap() }
                    cMap[c] = (cMap[c] ?: 0.0) + weight
                }
            }
        }

        for ((task, weight) in weightedTasks) {
            val projs = task.projects.map { it.lowercase() }.distinct()
            val ctxs = task.contexts.map { it.lowercase() }.distinct()
            projs.forEach {
                knownProjs.add(it)
                projectTotals[it] = (projectTotals[it] ?: 0.0) + weight
            }
            ctxs.forEach {
                knownCtxs.add(it)
                contextTotals[it] = (contextTotals[it] ?: 0.0) + weight
            }

            // Cross-tag workflow affinity
            for (p in projs) {
                for (c in ctxs) {
                    val pcMap = projectToContext.getOrPut(p) { HashMap() }
                    pcMap[c] = (pcMap[c] ?: 0.0) + weight
                    val cpMap = contextToProject.getOrPut(c) { HashMap() }
                    cpMap[p] = (cpMap[p] ?: 0.0) + weight
                }
            }

            val words = tokenizeForLearning(task.description)
            if (words.isEmpty()) continue

            val features = LinkedHashSet<String>()
            for (w in words) {
                features.add(w)
                val stem = normalizeStem(w)
                if (stem != w && stem.length >= 3) features.add(stem)
            }
            // Bigrams capture multi-word concepts ("credit card", "pull request", "team standup")
            for (i in 0 until words.size - 1) {
                features.add("${words[i]} ${words[i + 1]}")
            }

            addTokenFeatures(features.toList(), projs, ctxs, weight)
        }

        val model = HistoricalTagModel(
            tokenTotals = tokenTotals,
            tokenToProject = tokenToProject,
            tokenToContext = tokenToContext,
            projectToContext = projectToContext,
            contextToProject = contextToProject,
            projectTotals = projectTotals,
            contextTotals = contextTotals
        )

        // Backwards-compatible strong word->tag maps (only when evidence is genuinely high-precision)
        val mergedProjects = LinkedHashMap<String, String>(baseConfig.keywordToProject)
        for ((token, pMap) in tokenToProject) {
            if (!token.contains(' ') && token !in mergedProjects) {
                val total = tokenTotals[token] ?: 0.0
                val best = pMap.maxByOrNull { it.value }
                if (best != null && best.value >= 2.0 && (best.value / total) >= 0.65) {
                    mergedProjects[token] = best.key
                }
            }
        }

        val learnedWordToContext = LinkedHashMap<String, String>()
        for ((token, cMap) in tokenToContext) {
            if (!token.contains(' ')) {
                val total = tokenTotals[token] ?: 0.0
                val best = cMap.maxByOrNull { it.value }
                if (best != null && best.value >= 2.0 && (best.value / total) >= 0.65) {
                    learnedWordToContext[token] = best.key
                }
            }
        }

        return baseConfig.copy(
            keywordToProject = mergedProjects,
            wordToContext = learnedWordToContext,
            knownProjects = knownProjs,
            knownContexts = knownCtxs,
            historicalModel = model
        )
    }

    /**
     * Layer 4 Feedback Learning:
     * When the user saves or tags a task with a `+project`, extracts the cleanest topic noun
     * (never stopwords or already-covered stems) and appends the mapping to `tick.rules.txt`.
     * Context rules are only persisted when a clean spatial/medium phrase wasn't already covered,
     * avoiding bare-word pollution.
     */
    fun learnFromTask(
        existingContent: String,
        task: TodoTask,
        dismissedTags: Set<String> = emptySet()
    ): String {
        val candidateWords = tokenizeForLearning(task.description)
        val primaryWord = candidateWords.firstOrNull() ?: return existingContent
        val primaryStem = normalizeStem(primaryWord)

        var updated = existingContent
        val currentParsed = parse(existingContent)
        val existingProjStems = currentParsed.keywordToProject.keys.map { normalizeStem(it) }.toSet()

        val firstProj = task.projects.firstOrNull { "+$it" !in dismissedTags }
        if (
            firstProj != null &&
            primaryWord !in currentParsed.keywordToProject &&
            primaryStem !in existingProjStems &&
            !primaryWord.equals(firstProj, ignoreCase = true)
        ) {
            updated = appendLearnedProjectRule(updated, primaryWord, firstProj)
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
