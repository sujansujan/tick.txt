package com.ticktxt.app.ui.ascii

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.ticktxt.app.ui.theme.TickSemanticPalette

// Pre-compiled Regexes
val DUE_TAG_REGEX = Regex("\\bdue:\\S+")
val DUETIME_TAG_REGEX = Regex("\\bduetime:\\S+")
val CHECKBOX_LINE_REGEX = Regex("^(\\s*[-*]\\s+\\[)([ xX])(\\]\\s*.*)$")
val INLINE_MD_TOKEN_REGEX = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`)")

fun toggleTagInText(rawText: String, tag: String): String {
    val tokens = rawText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
    if (tokens.contains(tag)) {
        tokens.removeAll { it == tag }
    } else {
        tokens.add(tag)
    }
    return tokens.joinToString(" ")
}

fun toggleMarkdownCheckboxAtLine(rawMarkdown: String, targetLineIndex: Int): String {
    val lines = rawMarkdown.lines().toMutableList()
    if (targetLineIndex !in lines.indices) return rawMarkdown
    val line = lines[targetLineIndex]
    val match = CHECKBOX_LINE_REGEX.matchEntire(line)
    if (match != null) {
        val currentMark = match.groupValues[2]
        val nextMark = if (currentMark.equals("x", ignoreCase = true)) " " else "x"
        lines[targetLineIndex] = "${match.groupValues[1]}$nextMark${match.groupValues[3]}"
    }
    return lines.joinToString("\n")
}

fun wrapTextToChunks(text: String, maxChars: Int): List<String> {
    val clean = text.trim()
    if (clean.isEmpty()) return listOf("")
    val safeMax = maxChars.coerceAtLeast(10)
    if (clean.length <= safeMax) return listOf(clean)

    val words = clean.split(Regex("\\s+"))
    val chunks = mutableListOf<String>()
    var current = StringBuilder()

    for (word in words) {
        if (current.isEmpty()) {
            if (word.length <= safeMax) {
                current.append(word)
            } else {
                var idx = 0
                while (idx < word.length) {
                    val end = (idx + safeMax).coerceAtMost(word.length)
                    val piece = word.substring(idx, end)
                    if (end == word.length) {
                        current.append(piece)
                    } else {
                        chunks.add(piece)
                    }
                    idx = end
                }
            }
        } else if (current.length + 1 + word.length <= safeMax) {
            current.append(' ').append(word)
        } else {
            chunks.add(current.toString())
            current = StringBuilder()
            if (word.length <= safeMax) {
                current.append(word)
            } else {
                var idx = 0
                while (idx < word.length) {
                    val end = (idx + safeMax).coerceAtMost(word.length)
                    val piece = word.substring(idx, end)
                    if (end == word.length) {
                        current.append(piece)
                    } else {
                        chunks.add(piece)
                    }
                    idx = end
                }
            }
        }
    }
    if (current.isNotEmpty()) {
        chunks.add(current.toString())
    }
    return chunks.ifEmpty { listOf("") }
}

data class WrappedNoteCardRow(
    val originalLineIdx: Int,
    val prefixToken: String,
    val prefixColorType: String,
    val bodyText: String,
    val isCheckedItem: Boolean = false,
    val isHeading: Boolean = false,
    val isQuote: Boolean = false,
    val isCheckboxFirstRow: Boolean = false,
    val isCheckboxItemRow: Boolean = false
)

fun buildWrappedCardRows(bodyContent: String, maxCharsPerLine: Int): List<WrappedNoteCardRow> {
    val result = mutableListOf<WrappedNoteCardRow>()
    val lines = bodyContent.lines()

    for ((idx, rawLine) in lines.withIndex()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue

        val checkboxMatch = CHECKBOX_LINE_REGEX.matchEntire(rawLine)
        when {
            checkboxMatch != null -> {
                val isChecked = checkboxMatch.groupValues[2].equals("x", ignoreCase = true)
                val itemText = checkboxMatch.groupValues[3].removePrefix("]").trim()
                val chunks = wrapTextToChunks(itemText, (maxCharsPerLine - 4).coerceAtLeast(10))
                chunks.forEachIndexed { cIdx, chunk ->
                    result.add(
                        WrappedNoteCardRow(
                            originalLineIdx = idx,
                            prefixToken = if (cIdx == 0) (if (isChecked) "[x] " else "[ ] ") else "    ",
                            prefixColorType = if (isChecked) "check_on" else "check_off",
                            bodyText = chunk,
                            isCheckedItem = isChecked,
                            isCheckboxFirstRow = (cIdx == 0),
                            isCheckboxItemRow = true
                        )
                    )
                }
            }

            trimmed.startsWith("#") -> {
                val headingText = trimmed.trimStart('#').trim()
                val chunks = wrapTextToChunks(headingText, (maxCharsPerLine - 2).coerceAtLeast(10))
                chunks.forEachIndexed { cIdx, chunk ->
                    result.add(
                        WrappedNoteCardRow(
                            originalLineIdx = idx,
                            prefixToken = if (cIdx == 0) "# " else "  ",
                            prefixColorType = "heading",
                            bodyText = chunk,
                            isHeading = true
                        )
                    )
                }
            }

            trimmed.startsWith(">") -> {
                val quoteText = trimmed.removePrefix(">").trim()
                val chunks = wrapTextToChunks(quoteText, (maxCharsPerLine - 2).coerceAtLeast(10))
                chunks.forEach { chunk ->
                    result.add(
                        WrappedNoteCardRow(
                            originalLineIdx = idx,
                            prefixToken = "| ",
                            prefixColorType = "quote",
                            bodyText = chunk,
                            isQuote = true
                        )
                    )
                }
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val bulletBody = trimmed.substring(2).trim()
                val chunks = wrapTextToChunks(bulletBody, (maxCharsPerLine - 2).coerceAtLeast(10))
                chunks.forEachIndexed { cIdx, chunk ->
                    result.add(
                        WrappedNoteCardRow(
                            originalLineIdx = idx,
                            prefixToken = if (cIdx == 0) "- " else "  ",
                            prefixColorType = "bullet",
                            bodyText = chunk
                        )
                    )
                }
            }

            else -> {
                val chunks = wrapTextToChunks(trimmed, maxCharsPerLine.coerceAtLeast(12))
                chunks.forEach { chunk ->
                    result.add(
                        WrappedNoteCardRow(
                            originalLineIdx = idx,
                            prefixToken = "",
                            prefixColorType = "plain",
                            bodyText = chunk
                        )
                    )
                }
            }
        }
    }
    return result
}

/**
 * Semantic syntax highlighter for task text on the AMOLED Full-Screen Notification
 * and task previews: highlights `(A)`/`(B)`/`(C)` priorities, `+project` tags, `@context` tags,
 * `due:`/`duetime:` dates, `rec:`, `rem:` specs, and `note:` links.
 */
fun buildSemanticTaskAnnotatedString(
    rawTitle: String,
    palette: TickSemanticPalette,
    boldBase: Boolean = true
): AnnotatedString {
    return buildAnnotatedString {
        val tokens = rawTitle.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        tokens.forEachIndexed { idx, token ->
            if (idx > 0) append(" ")
            when {
                token == "(A)" -> withStyle(SpanStyle(color = palette.priorityA, fontWeight = FontWeight.Bold)) { append(token) }
                token == "(B)" -> withStyle(SpanStyle(color = palette.priorityB, fontWeight = FontWeight.Bold)) { append(token) }
                token == "(C)" -> withStyle(SpanStyle(color = palette.priorityC, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("+") && token.length > 1 -> withStyle(SpanStyle(color = palette.project, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("@") && token.length > 1 -> withStyle(SpanStyle(color = palette.contextTag, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("due:", ignoreCase = true) || token.startsWith("duetime:", ignoreCase = true) ->
                    withStyle(SpanStyle(color = palette.priorityB, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("rec:", ignoreCase = true) ->
                    withStyle(SpanStyle(color = palette.contextTag, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("rem:", ignoreCase = true) ->
                    withStyle(SpanStyle(color = palette.reminder, fontWeight = FontWeight.Bold)) { append(token) }
                token.startsWith("note:", ignoreCase = true) ->
                    withStyle(SpanStyle(color = palette.noteLink, fontWeight = FontWeight.Bold)) { append(token) }
                else -> withStyle(
                    SpanStyle(
                        color = palette.fg,
                        fontWeight = if (boldBase) FontWeight.Bold else FontWeight.Normal
                    )
                ) { append(token) }
            }
        }
    }
}

fun renderInlineMarkdown(
    text: String,
    palette: TickSemanticPalette,
    baseColor: Color = palette.fg
): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        for (match in INLINE_MD_TOKEN_REGEX.findAll(text)) {
            if (match.range.first > cursor) {
                withStyle(SpanStyle(color = baseColor)) {
                    append(text.substring(cursor, match.range.first))
                }
            }
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                    withStyle(SpanStyle(color = palette.fg, fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("*") && token.endsWith("*") && token.length > 2 -> {
                    withStyle(SpanStyle(color = palette.fg, fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                    withStyle(
                        SpanStyle(
                            color = palette.reminder,
                            background = palette.bgElevated,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(" " + token.substring(1, token.length - 1) + " ")
                    }
                }
                else -> {
                    withStyle(SpanStyle(color = baseColor)) {
                        append(token)
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            withStyle(SpanStyle(color = baseColor)) {
                append(text.substring(cursor))
            }
        }
    }
}
