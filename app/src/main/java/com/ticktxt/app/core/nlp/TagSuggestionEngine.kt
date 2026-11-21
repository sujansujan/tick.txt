package com.ticktxt.app.core.nlp

import kotlin.math.abs
import kotlin.math.min

/**
 * Multi-layer scored Context (`@`) & Project (`+`) Recommendation Engine.
 *
 * Combines 6 orthogonal offline signals into calibrated confidence scores:
 * 1. Explicit `tick.rules.txt` keyword & phrase rules (exact + stemmed)
 * 2. Grammatical spatial/medium prepositions ("at factory" -> @factory, "via slack" -> @slack)
 * 3. Weighted statistical history (`todo.txt` + `done.txt` unigrams, stems, and bigrams)
 * 4. Known vocabulary exact, stem, and typo-tolerant Damerau-Levenshtein (distance-1) matching
 * 5. Action-verb & GTD medium lexicon ("call" -> @phone, "email" -> @email, "waiting for" -> @waiting)
 * 6. Cross-tag workflow affinity (`P(@ctx | +proj)` and `P(+proj | @ctx)`)
 * Plus a clean novel verb-object / topic fallback when no project meets the auto-apply threshold.
 */
object TagSuggestionEngine {

    private const val AUTO_APPLY_THRESHOLD = 0.60
    private const val SUGGESTION_THRESHOLD = 0.20

    val NON_ENTITY_WORDS = setOf(
        // Articles, pronouns, demonstratives
        "a", "an", "the", "my", "our", "your", "his", "her", "their", "its",
        "this", "that", "these", "those", "some", "any", "all", "both", "each", "every",
        "it", "me", "us", "them", "him", "she", "he", "we", "they", "you", "one",
        // Prepositions, conjunctions, adverbs
        "up", "out", "off", "on", "in", "at", "to", "for", "with", "about", "into",
        "over", "after", "before", "until", "around", "from", "via", "by", "through",
        "again", "later", "soon", "now", "here", "there", "back", "still", "also",
        "just", "really", "very", "well", "fast", "early", "late", "only", "even",
        // Vague non-project nouns/adjectives
        "something", "everything", "nothing", "anything", "someone", "everyone", "anyone",
        "nobody", "stuff", "thing", "things", "people", "person", "folk", "folks",
        "done", "ready", "sure", "more", "less", "first", "last", "next", "new", "old",
        "good", "bad", "big", "small", "right", "left", "same", "other", "another",
        "item", "items", "task", "tasks", "todo", "note", "notes", "part", "time", "times",
        // Temporal & calendar words
        "today", "tomorrow", "tonight", "tmr", "tmrw", "tom", "tod", "tdy", "2day", "2mrw", "2nite",
        "eod", "eow", "eom", "cob", "noon", "midday", "midnight", "morning", "afternoon",
        "evening", "night", "weekend", "week", "month", "year", "day", "days", "weeks",
        "min", "mins", "minute", "minutes", "hr", "hrs", "hour", "hours", "sec", "seconds",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mon", "tue", "tues", "wed", "thu", "thurs", "fri", "sat", "sun",
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december", "jan", "feb", "mar", "apr",
        "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        // Built-in generic contexts
        "home", "office", "work", "shop", "store", "errands", "phone", "call"
    )

    private val VERB_TO_MEDIUM_CONTEXT = linkedMapOf(
        // @phone
        "call" to ("phone" to 0.78),
        "ring" to ("phone" to 0.78),
        "dial" to ("phone" to 0.78),
        "text" to ("phone" to 0.72),
        "sms" to ("phone" to 0.78),
        "whatsapp" to ("phone" to 0.78),
        "telegram" to ("phone" to 0.74),
        "facetime" to ("phone" to 0.78),
        "zoom" to ("phone" to 0.74),
        "voicemail" to ("phone" to 0.78),
        "callback" to ("phone" to 0.78),
        // @email
        "email" to ("email" to 0.80),
        "mail" to ("email" to 0.72),
        "reply" to ("email" to 0.68),
        "inbox" to ("email" to 0.68),
        // @errands
        "buy" to ("errands" to 0.74),
        "purchase" to ("errands" to 0.74),
        "shop" to ("errands" to 0.74),
        "pickup" to ("errands" to 0.74),
        "refill" to ("errands" to 0.70),
        "return" to ("errands" to 0.50),
        // @desk
        "code" to ("desk" to 0.76),
        "refactor" to ("desk" to 0.78),
        "compile" to ("desk" to 0.78),
        "commit" to ("desk" to 0.76),
        "debug" to ("desk" to 0.76),
        "deploy" to ("desk" to 0.74),
        "merge" to ("desk" to 0.72),
        "spreadsheet" to ("desk" to 0.76),
        "slides" to ("desk" to 0.70),
        "figma" to ("desk" to 0.78),
        "github" to ("desk" to 0.78),
        "jira" to ("desk" to 0.76),
        "draft" to ("desk" to 0.64),
        "research" to ("desk" to 0.62),
        // @kitchen
        "cook" to ("kitchen" to 0.76),
        "bake" to ("kitchen" to 0.76),
        // @home
        "vacuum" to ("home" to 0.74),
        "laundry" to ("home" to 0.72),
        "dishes" to ("home" to 0.74),
        "mow" to ("home" to 0.74),
        // @online
        "download" to ("online" to 0.68),
        "upload" to ("online" to 0.68),
        "subscribe" to ("online" to 0.66),
        "unsubscribe" to ("online" to 0.70),
        "book" to ("online" to 0.52)
    )

    private val BIGRAM_TO_PROJECT = linkedMapOf(
        "credit card" to "finance",
        "bank statement" to "finance",
        "tax return" to "finance",
        "pull request" to "work",
        "code review" to "work",
        "team meeting" to "work",
        "blood test" to "health",
        "eye exam" to "health",
        "oil change" to "car",
        "car wash" to "car",
        "flight ticket" to "travel",
        "hotel booking" to "travel",
        "boarding pass" to "travel",
        "water plants" to "home",
        "take trash" to "home"
    )

    private val BIGRAM_TO_CONTEXT = linkedMapOf(
        "pick up" to "errands",
        "drop off" to "errands",
        "dry cleaning" to "errands",
        "post office" to "errands",
        "waiting for" to "waiting",
        "waiting on" to "waiting",
        "follow up" to "agenda"
    )

    private val SPATIAL_PREP_REGEX = Regex(
        "\\b(?:at|in|on|from|via|over)\\s+(?:the\\s+|a\\s+|an\\s+|my\\s+|our\\s+)?([A-Za-z][A-Za-z0-9_-]{2,24})\\b",
        RegexOption.IGNORE_CASE
    )
    private val ARTICLE_PREP_REGEX = Regex(
        "\\b(?:at|in|on|from|via|over)\\s+(?:the|a|an|my|our)\\s+",
        RegexOption.IGNORE_CASE
    )
    private val VERB_OBJECT_REGEX = Regex(
        "\\b(?:check|review|fix|build|update|call|email|pay|buy|clean|finish|prepare|send|test|visit|inspect|audit|debug|deploy|write|plan|schedule|order|pickup|pick\\s+up|meet|talk\\s+to|work\\s+on|follow\\s+up\\s+on|renew|ship|submit|draft|organize|research|design|repair|install|configure|backup|cancel|book)\\s+(?:the\\s+|a\\s+|an\\s+|my\\s+|our\\s+|on\\s+|up\\s+)?([A-Za-z][A-Za-z0-9_-]{2,24})\\b",
        RegexOption.IGNORE_CASE
    )
    private val TOPIC_PREP_REGEX = Regex(
        "\\b(?:about|regarding|for|concerning)\\s+(?:the\\s+|a\\s+|an\\s+|my\\s+|our\\s+)?([A-Za-z][A-Za-z0-9_-]{2,24})\\b",
        RegexOption.IGNORE_CASE
    )

    private data class CandidateAccumulator(
        var score: Double = 0.0,
        var bestReason: String = "",
        var bestReasonWeight: Double = 0.0,
        var spanStart: Int = -1,
        var spanEnd: Int = -1,
        var stripFromTitle: Boolean = false
    ) {
        fun addEvidence(weight: Double, reason: String, start: Int = -1, end: Int = -1, strip: Boolean = false) {
            score = (score + weight).coerceAtMost(1.35)
            if (weight > bestReasonWeight) {
                bestReasonWeight = weight
                bestReason = reason
                if (start >= 0 && end > start) {
                    spanStart = start
                    spanEnd = end
                    stripFromTitle = strip
                }
            }
        }
    }

    data class InferenceOutput(
        val inferredProjects: List<String>,
        val inferredContexts: List<String>,
        val suggestedTags: List<TagSuggestion>
    )

    private data class TokenOccurrence(
        val word: String,
        val stem: String,
        val start: Int,
        val end: Int
    )

    fun inferTags(
        input: String,
        rules: TickRulesConfig,
        explicitProjects: List<String>,
        explicitContexts: List<String>,
        consumedMask: BooleanArray,
        protectedMask: BooleanArray,
        addSpan: (start: Int, end: Int, category: SpanCategory, resolvedToken: String, stripFromTitle: Boolean) -> Boolean
    ): InferenceOutput {
        fun isUnprotectedRange(range: IntRange): Boolean =
            range.all { it in input.indices && !protectedMask[it] && !consumedMask[it] }

        val projectScores = LinkedHashMap<String, CandidateAccumulator>()
        val contextScores = LinkedHashMap<String, CandidateAccumulator>()

        fun addProject(name: String, weight: Double, reason: String, start: Int = -1, end: Int = -1) {
            val clean = name.lowercase().trim()
            if (clean.isEmpty() || clean in explicitProjects.map { it.lowercase() }) return
            projectScores.getOrPut(clean) { CandidateAccumulator() }
                .addEvidence(weight, reason, start, end, strip = false)
        }

        fun addContext(name: String, weight: Double, reason: String, start: Int = -1, end: Int = -1, strip: Boolean = false) {
            val clean = name.lowercase().trim()
            if (clean.isEmpty() || clean in explicitContexts.map { it.lowercase() }) return
            contextScores.getOrPut(clean) { CandidateAccumulator() }
                .addEvidence(weight, reason, start, end, strip = strip)
        }

        // =====================================================================
        // STEP A: Context Phrases (`tick.rules.txt` [contexts]) & Spatial Prepositions
        // =====================================================================
        for ((phrase, ctxName) in rules.phraseToContext.entries.sortedByDescending { it.key.length }) {
            val rx = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
            rx.findAll(input).forEach { m ->
                if (isUnprotectedRange(m.range)) {
                    val strip = phrase.contains(" ")
                    if (strip) {
                        if (addSpan(m.range.first, m.range.last + 1, SpanCategory.CONTEXT, "@$ctxName", true)) {
                            addContext(ctxName, 0.96, "rule:$phrase", m.range.first, m.range.last + 1, strip = true)
                        }
                    } else {
                        addContext(ctxName, 0.88, "rule:$phrase", m.range.first, m.range.last + 1, strip = false)
                    }
                }
            }
        }

        // Spatial/medium prepositions: "at factory" -> @factory, "via slack" -> @slack, "from clinic" -> @clinic
        SPATIAL_PREP_REGEX.findAll(input).forEach { m ->
            if (isUnprotectedRange(m.range)) {
                val candidateCtx = m.groupValues[1].lowercase()
                val prep = m.value.substringBefore(" ").lowercase()
                val hasArticle = ARTICLE_PREP_REGEX.containsMatchIn(m.value)
                if (candidateCtx !in NON_ENTITY_WORDS && (prep == "at" || prep == "via" || prep == "from" || prep == "over" || hasArticle)) {
                    if (addSpan(m.range.first, m.range.last + 1, SpanCategory.CONTEXT, "@$candidateCtx", true)) {
                        addContext(candidateCtx, 0.92, "place:$prep", m.range.first, m.range.last + 1, strip = true)
                    }
                }
            }
        }

        // Extract unconsumed tokens & bigrams for multi-layer scoring
        val tokens = mutableListOf<TokenOccurrence>()
        Regex("\\b[A-Za-z][A-Za-z0-9_-]{1,28}\\b").findAll(input).forEach { m ->
            if (!consumedMask[m.range.first]) {
                val w = m.value.lowercase()
                tokens.add(TokenOccurrence(w, RulesEngine.normalizeStem(w), m.range.first, m.range.last + 1))
            }
        }

        // Precompute stemmed rule index for O(1) stem lookups
        val stemToRuleProject = HashMap<String, Pair<String, String>>() // stem -> (proj, trigger)
        for ((kw, proj) in rules.keywordToProject) {
            stemToRuleProject.putIfAbsent(RulesEngine.normalizeStem(kw), proj to kw)
        }

        val knownProjByStem = rules.knownProjects.associateBy { RulesEngine.normalizeStem(it) }
        val knownCtxByStem = rules.knownContexts.associateBy { RulesEngine.normalizeStem(it) }
        val model = rules.historicalModel

        // =====================================================================
        // STEP B: Bigram Domain Lexicon & Historical Bigram Evidence
        // =====================================================================
        for (i in 0 until tokens.size - 1) {
            val t1 = tokens[i]
            val t2 = tokens[i + 1]
            val bigram = "${t1.word} ${t2.word}"

            BIGRAM_TO_PROJECT[bigram]?.let { proj ->
                addProject(proj, 0.90, "phrase:$bigram", t1.start, t2.end)
            }
            BIGRAM_TO_CONTEXT[bigram]?.let { ctx ->
                addContext(ctx, 0.84, "phrase:$bigram", t1.start, t2.end)
            }

            // Historical bigram co-occurrence (stronger signal than unigrams!)
            for ((proj, ev) in model.projectEvidenceForToken(bigram)) {
                addProject(proj, ev * 0.95, "hist:$bigram", t1.start, t2.end)
            }
            for ((ctx, ev) in model.contextEvidenceForToken(bigram)) {
                addContext(ctx, ev * 0.90, "hist:$bigram", t1.start, t2.end)
            }
        }

        // =====================================================================
        // STEP C: Per-Token Multi-Layer Scoring (Rules, History, Known Vocab, Fuzzy, Verbs)
        // =====================================================================
        for (tok in tokens) {
            val w = tok.word
            val stem = tok.stem

            // 1. User / default keyword rules (exact > stem)
            val exactRuleProj = rules.keywordToProject[w]
            if (exactRuleProj != null) {
                addProject(exactRuleProj, 0.95, "rule:$w", tok.start, tok.end)
            } else {
                val stemRule = stemToRuleProject[stem]
                if (stemRule != null && stem.length >= 4) {
                    addProject(stemRule.first, 0.84, "stem:${stemRule.second}", tok.start, tok.end)
                }
            }

            // 2. Known +projects vocabulary (exact > stem > fuzzy distance-1)
            when {
                w in rules.knownProjects -> addProject(w, 0.88, "known:+$w", tok.start, tok.end)
                stem in knownProjByStem && stem.length >= 4 ->
                    addProject(knownProjByStem[stem]!!, 0.78, "known:+${knownProjByStem[stem]}", tok.start, tok.end)
                w.length >= 5 && w !in NON_ENTITY_WORDS -> {
                    findFuzzyMatch(w, rules.knownProjects)?.let { fuzzyProj ->
                        addProject(fuzzyProj, 0.66, "fuzzy:+$fuzzyProj", tok.start, tok.end)
                    }
                }
            }

            // 3. Known @contexts vocabulary (exact > stem > fuzzy distance-1)
            when {
                w in rules.knownContexts -> addContext(w, 0.85, "known:@$w", tok.start, tok.end)
                stem in knownCtxByStem && stem.length >= 4 ->
                    addContext(knownCtxByStem[stem]!!, 0.75, "known:@${knownCtxByStem[stem]}", tok.start, tok.end)
                w.length >= 5 && w !in NON_ENTITY_WORDS -> {
                    findFuzzyMatch(w, rules.knownContexts)?.let { fuzzyCtx ->
                        addContext(fuzzyCtx, 0.64, "fuzzy:@$fuzzyCtx", tok.start, tok.end)
                    }
                }
            }

            // 4. Statistical historical co-occurrence (exact + stem)
            val histProjs = model.projectEvidenceForToken(w).ifEmpty {
                if (stem != w) model.projectEvidenceForToken(stem).mapValues { it.value * 0.85 } else emptyMap()
            }
            for ((proj, ev) in histProjs) {
                addProject(proj, ev * 0.88, "hist:$w", tok.start, tok.end)
            }

            val histCtxs = model.contextEvidenceForToken(w).ifEmpty {
                if (stem != w) model.contextEvidenceForToken(stem).mapValues { it.value * 0.85 } else emptyMap()
            }
            for ((ctx, ev) in histCtxs) {
                addContext(ctx, ev * 0.85, "hist:$w", tok.start, tok.end)
            }

            // Legacy wordToContext compatibility
            rules.wordToContext[w]?.let { legacyCtx ->
                if (legacyCtx !in histCtxs) {
                    addContext(legacyCtx, 0.68, "hist:$w", tok.start, tok.end)
                }
            }

            // 5. Action-verb & medium cues (only as gentle/medium signal; exact/spatial rules always win)
            val verbHit = VERB_TO_MEDIUM_CONTEXT[w] ?: VERB_TO_MEDIUM_CONTEXT[stem]
            if (verbHit != null) {
                addContext(verbHit.first, verbHit.second, "verb:$w", tok.start, tok.end)
            }
        }

        // =====================================================================
        // STEP D: Cross-Tag Workflow Affinity (`P(@ctx | +proj)` & `P(+proj | @ctx)`)
        // =====================================================================
        val anchorProjects = (explicitProjects.map { it.lowercase() } +
            projectScores.filter { it.value.score >= AUTO_APPLY_THRESHOLD }.keys).distinct()
        for (p in anchorProjects) {
            for ((ctx, affinity) in model.contextsForProject(p)) {
                addContext(ctx, affinity * 0.58, "with:+$p")
            }
        }

        val anchorContexts = (explicitContexts.map { it.lowercase() } +
            contextScores.filter { it.value.score >= AUTO_APPLY_THRESHOLD }.keys).distinct()
        for (c in anchorContexts) {
            for ((proj, affinity) in model.projectsForContext(c)) {
                addProject(proj, affinity * 0.48, "with:@$c")
            }
        }

        // =====================================================================
        // STEP E: Novel Transitive Verb-Object / Topical Preposition Fallback
        // (Only fires if NO explicit project AND NO project reached AUTO_APPLY_THRESHOLD)
        // =====================================================================
        val hasStrongProject = explicitProjects.isNotEmpty() ||
            projectScores.values.any { it.score >= AUTO_APPLY_THRESHOLD }

        val topContextNames = (explicitContexts.map { it.lowercase() } +
            contextScores.filter { it.value.score >= AUTO_APPLY_THRESHOLD }.keys).toSet()

        if (!hasStrongProject) {
            VERB_OBJECT_REGEX.findAll(input).forEach { m ->
                val objGroup = m.groups[1] ?: return@forEach
                val objWord = objGroup.value.lowercase()
                val objRange = objGroup.range
                if (objWord !in NON_ENTITY_WORDS && objWord !in topContextNames && isUnprotectedRange(objRange)) {
                    addProject(objWord, 0.66, "object:$objWord", objRange.first, objRange.last + 1)
                }
            }
        }

        if (explicitProjects.isEmpty() && projectScores.values.none { it.score >= AUTO_APPLY_THRESHOLD }) {
            TOPIC_PREP_REGEX.findAll(input).forEach { m ->
                val topicGroup = m.groups[1] ?: return@forEach
                val topicWord = topicGroup.value.lowercase()
                val topicRange = topicGroup.range
                if (topicWord !in NON_ENTITY_WORDS && topicWord !in topContextNames && isUnprotectedRange(topicRange)) {
                    addProject(topicWord, 0.62, "topic:$topicWord", topicRange.first, topicRange.last + 1)
                }
            }
        }

        // =====================================================================
        // STEP F: Select Auto-Applied Top Winners + Ranked Runner-Up Suggestions
        // =====================================================================
        val sortedProjects = projectScores.entries.sortedByDescending { it.value.score }
        val sortedContexts = contextScores.entries.sortedByDescending { it.value.score }

        val inferredProjects = mutableListOf<String>()
        val inferredContexts = mutableListOf<String>()
        val suggestions = mutableListOf<TagSuggestion>()

        // Auto-apply top-1 project above threshold (unless user already typed an explicit +project)
        val topProj = sortedProjects.firstOrNull()
        if (explicitProjects.isEmpty() && topProj != null && topProj.value.score >= AUTO_APPLY_THRESHOLD) {
            val acc = topProj.value
            val accepted = if (acc.spanStart >= 0 && acc.spanEnd > acc.spanStart) {
                addSpan(acc.spanStart, acc.spanEnd, SpanCategory.PROJECT, "+${topProj.key}", false)
            } else {
                true
            }
            if (accepted) {
                inferredProjects.add(topProj.key)
            }
        }

        // Auto-apply top-1 context above threshold (spatial prepositions already added their span)
        val topCtx = sortedContexts.firstOrNull()
        if (
            topCtx != null &&
            topCtx.value.score >= AUTO_APPLY_THRESHOLD &&
            (explicitContexts.isEmpty() || topCtx.value.stripFromTitle)
        ) {
            val acc = topCtx.value
            val accepted = if (acc.stripFromTitle) {
                true // Span already registered & stripped in Step A
            } else if (acc.spanStart >= 0 && acc.spanEnd > acc.spanStart) {
                addSpan(acc.spanStart, acc.spanEnd, SpanCategory.CONTEXT, "@${topCtx.key}", false)
            } else {
                true
            }
            if (accepted) {
                inferredContexts.add(topCtx.key)
            }
        }

        // Build ranked TagSuggestion list (auto-applied first, then smart runner-ups)
        for ((proj, acc) in sortedProjects) {
            if (acc.score < SUGGESTION_THRESHOLD) continue
            val isAuto = proj in inferredProjects
            suggestions.add(
                TagSuggestion(
                    tag = "+$proj",
                    kind = TagKind.PROJECT,
                    score = acc.score,
                    reason = acc.bestReason,
                    autoApplied = isAuto
                )
            )
        }
        for ((ctx, acc) in sortedContexts) {
            if (acc.score < SUGGESTION_THRESHOLD) continue
            val isAuto = ctx in inferredContexts
            suggestions.add(
                TagSuggestion(
                    tag = "@$ctx",
                    kind = TagKind.CONTEXT,
                    score = acc.score,
                    reason = acc.bestReason,
                    autoApplied = isAuto
                )
            )
        }

        return InferenceOutput(
            inferredProjects = inferredProjects,
            inferredContexts = inferredContexts,
            suggestedTags = suggestions.sortedWith(
                compareByDescending<TagSuggestion> { it.autoApplied }
                    .thenByDescending { it.score }
            ).take(10)
        )
    }

    /**
     * Fast Damerau-Levenshtein distance <= 1 check (handles single typo: substitution,
     * insertion, deletion, or adjacent character transposition like "grocries" -> "groceries").
     */
    private fun findFuzzyMatch(word: String, vocabulary: Set<String>): String? {
        var best: String? = null
        for (candidate in vocabulary) {
            if (candidate.length < 5) continue
            if (abs(candidate.length - word.length) > 1) continue
            if (damerauDistanceAtMostOne(word, candidate)) {
                if (best == null || candidate.length > best.length) {
                    best = candidate
                }
            }
        }
        return best
    }

    internal fun damerauDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (abs(la - lb) > 1) return false

        var i = 0
        var j = 0
        var edits = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) {
                i++; j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    // Adjacent transposition (e.g. "architecure" / "wrok")
                    la == lb && i + 1 < la && a[i] == b[j + 1] && a[i + 1] == b[j] -> {
                        i += 2; j += 2
                    }
                    la == lb -> {
                        i++; j++
                    }
                    la > lb -> i++
                    else -> j++
                }
            }
        }
        if (i < la || j < lb) edits++
        return edits <= 1
    }
}
