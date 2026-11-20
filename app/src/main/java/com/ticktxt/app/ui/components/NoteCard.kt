package com.ticktxt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.core.notes.NoteFile
import com.ticktxt.app.ui.ascii.AsciiBoxBodyRow
import com.ticktxt.app.ui.ascii.AsciiHorizontalBorder
import com.ticktxt.app.ui.ascii.AsciiTitledTopBorder
import com.ticktxt.app.ui.ascii.CHECKBOX_LINE_REGEX
import com.ticktxt.app.ui.ascii.buildWrappedCardRows
import com.ticktxt.app.ui.ascii.renderInlineMarkdown
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette

@Composable
fun KeepAsciiMarkdownCard(
    note: NoteFile,
    fontSizeSp: Int,
    maxHalfScreenVisualLines: Int = 14,
    backlinkCount: Int = 0,
    onTapCard: () -> Unit,
    onToggleChecklistLine: (Int) -> Unit
) {
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current
    val monoFont = FontFamily.Monospace
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val charWidthDp = remember(fontSizeSp, appFont) {
            val px = textMeasurer.measure(
                text = "M",
                style = TextStyle(fontFamily = appFont, fontSize = fontSizeSp.sp)
            ).size.width.coerceAtLeast(1)
            with(density) { px.toDp() }
        }
        // Subtract ~5 cells for left/right ASCII walls ("| " and " |") + breathing room
        val dynamicMaxChars = ((maxWidth / charWidthDp).toInt() - 5).coerceAtLeast(12)

        val allWrappedRows = remember(note.bodyContent, dynamicMaxChars) {
            buildWrappedCardRows(note.bodyContent, dynamicMaxChars)
        }
        val visibleRows = remember(allWrappedRows, maxHalfScreenVisualLines) {
            allWrappedRows.take(maxHalfScreenVisualLines)
        }
        val hiddenRowCount = (allWrappedRows.size - visibleRows.size).coerceAtLeast(0)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bg)
                .clickable { onTapCard() }
        ) {
            AsciiTitledTopBorder(
                badgeText = "# ${note.filename}",
                badgeColor = palette.project,
                rightBadgeText = if (backlinkCount > 0) "<-$backlinkCount" else null,
                rightBadgeColor = palette.noteLink
            )

            if (visibleRows.isEmpty()) {
                AsciiBoxBodyRow(
                    text = "(Empty note)",
                    textColor = palette.fgMuted,
                    fontSizeSp = (fontSizeSp - 1).coerceAtLeast(11),
                    singleLine = true,
                    onClick = { onTapCard() }
                )
            } else {
                visibleRows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "| ",
                            color = palette.border,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontFamily = monoFont,
                            softWrap = false,
                            maxLines = 1
                        )

                        if (row.prefixToken.isNotEmpty()) {
                            val prefixColor = when (row.prefixColorType) {
                                "check_on" -> palette.reminder
                                "check_off" -> palette.project
                                "heading" -> palette.project
                                "bullet" -> palette.priorityB
                                "quote" -> palette.contextTag
                                else -> palette.fgMuted
                            }
                            Text(
                                text = row.prefixToken,
                                color = prefixColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp + 2).sp,
                                fontFamily = monoFont,
                                softWrap = false,
                                maxLines = 1,
                                modifier = if (row.isCheckboxItemRow) {
                                    Modifier
                                        .clickable { onToggleChecklistLine(row.originalLineIdx) }
                                        .padding(end = 2.dp)
                                } else {
                                    Modifier
                                }
                            )
                        }

                        val bodyBaseColor = when {
                            row.isCheckedItem -> palette.fgMuted
                            row.isHeading -> palette.project
                            row.isQuote -> palette.contextTag
                            else -> palette.fg
                        }
                        Text(
                            text = renderInlineMarkdown(row.bodyText, palette, bodyBaseColor),
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp + 2).sp,
                            fontWeight = if (row.isHeading) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (row.isQuote) FontStyle.Italic else FontStyle.Normal,
                            fontFamily = appFont,
                            textDecoration = if (row.isCheckedItem) TextDecoration.LineThrough else TextDecoration.None,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTapCard() }
                        )

                        Text(
                            text = " |",
                            color = palette.border,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontFamily = monoFont,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }

                if (hiddenRowCount > 0) {
                    AsciiBoxBodyRow(
                        text = "... (+$hiddenRowCount more lines)",
                        textColor = palette.fgMuted,
                        fontSizeSp = (fontSizeSp - 2).coerceAtLeast(10),
                        singleLine = true,
                        onClick = { onTapCard() }
                    )
                }
            }

            AsciiHorizontalBorder()
        }
    }
}

@Composable
fun MarkdownFormattedRow(
    rawLine: String,
    fontSizeSp: Int,
    singleLine: Boolean = false,
    onTapCheckbox: () -> Unit,
    onTapLineToEdit: () -> Unit
) {
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current
    val monoFont = FontFamily.Monospace
    val trimmed = rawLine.trim()
    val checkboxMatch = CHECKBOX_LINE_REGEX.matchEntire(rawLine)

    var lineCount by remember(rawLine, fontSizeSp, singleLine) { mutableIntStateOf(1) }
    val leftRail = remember(lineCount) { List(lineCount) { "| " }.joinToString("\n") }
    val rightRail = remember(lineCount) { List(lineCount) { " |" }.joinToString("\n") }
    val rowLineHeight = (fontSizeSp + 3).sp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = leftRail,
            color = palette.border,
            fontSize = 12.sp,
            lineHeight = rowLineHeight,
            fontFamily = monoFont,
            softWrap = false
        )

        when {
            checkboxMatch != null -> {
                val isChecked = checkboxMatch.groupValues[2].equals("x", ignoreCase = true)
                val itemText = checkboxMatch.groupValues[3].removePrefix("]").trim()
                Text(
                    text = if (isChecked) "[x] " else "[ ] ",
                    color = if (isChecked) palette.reminder else palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = rowLineHeight,
                    fontFamily = monoFont,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable { onTapCheckbox() }
                        .padding(vertical = 1.dp)
                )
                Text(
                    text = renderInlineMarkdown(
                        itemText.ifEmpty { " " },
                        palette,
                        if (isChecked) palette.fgMuted else palette.fg
                    ),
                    fontSize = fontSizeSp.sp,
                    lineHeight = rowLineHeight,
                    fontFamily = appFont,
                    textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    onTextLayout = { if (!singleLine) lineCount = it.lineCount.coerceAtLeast(1) },
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }

            trimmed.startsWith("#") -> {
                val headingText = trimmed.trimStart('#').trim()
                Text(
                    text = "# $headingText",
                    color = palette.project,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = rowLineHeight,
                    fontFamily = appFont,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    onTextLayout = { if (!singleLine) lineCount = it.lineCount.coerceAtLeast(1) },
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }

            trimmed.startsWith(">") -> {
                val quoteText = trimmed.removePrefix(">").trim()
                Text(
                    text = renderInlineMarkdown("| $quoteText", palette, palette.contextTag),
                    fontStyle = FontStyle.Italic,
                    fontSize = (fontSizeSp - 1).coerceAtLeast(11).sp,
                    lineHeight = rowLineHeight,
                    fontFamily = appFont,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    onTextLayout = { if (!singleLine) lineCount = it.lineCount.coerceAtLeast(1) },
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val bulletBody = trimmed.substring(2).trim()
                Text(
                    text = "- ",
                    color = palette.priorityB,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = rowLineHeight,
                    fontFamily = monoFont,
                    softWrap = false,
                    maxLines = 1
                )
                Text(
                    text = renderInlineMarkdown(bulletBody, palette, palette.fg),
                    fontSize = fontSizeSp.sp,
                    lineHeight = rowLineHeight,
                    fontFamily = appFont,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    onTextLayout = { if (!singleLine) lineCount = it.lineCount.coerceAtLeast(1) },
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }

            else -> {
                Text(
                    text = renderInlineMarkdown(rawLine.ifEmpty { " " }, palette, palette.fg),
                    fontSize = fontSizeSp.sp,
                    lineHeight = rowLineHeight,
                    fontFamily = appFont,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                    onTextLayout = { if (!singleLine) lineCount = it.lineCount.coerceAtLeast(1) },
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTapLineToEdit() }
                        .padding(vertical = 1.dp)
                )
            }
        }

        Text(
            text = rightRail,
            color = palette.border,
            fontSize = 12.sp,
            lineHeight = rowLineHeight,
            fontFamily = monoFont,
            softWrap = false
        )
    }
}
