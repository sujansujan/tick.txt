package com.ticktxt.app.ui.ascii

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.ui.theme.LocalTickFontFamily
import com.ticktxt.app.ui.theme.LocalTickPalette

// Pre-allocated 240-char border string so recompositions never allocate strings
private val DASH_FILL_240 = "-".repeat(240)

/**
 * Uniform ASCII Design System Primitives (`+--[Title]---+`, `+---...---+`, `| ... |`).
 * Zero Compose `.border()` rectangles—100% character-grid borders that never wrap,
 * rendered in `palette.border` so chrome recedes cleanly behind Dracula/Alucard ink.
 */
@Composable
fun AsciiHorizontalBorder(
    corner: String = "+",
    fillChar: Char = '-',
    color: Color = LocalTickPalette.current.border
) {
    val fillText = remember(fillChar) {
        if (fillChar == '-') DASH_FILL_240 else fillChar.toString().repeat(240)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = corner,
            color = color,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = fillText,
            color = color,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = corner,
            color = color,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
fun AsciiTitledTopBorder(
    badgeText: String,
    borderColor: Color = LocalTickPalette.current.border,
    badgeColor: Color = LocalTickPalette.current.priorityB,
    rightBadgeText: String? = null,
    rightBadgeColor: Color = LocalTickPalette.current.fgMuted,
    onRightBadgeClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "+--[",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = badgeText,
            color = badgeColor,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = "]",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = DASH_FILL_240,
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f)
        )
        if (rightBadgeText != null) {
            Text(
                text = "[",
                color = borderColor,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1
            )
            Text(
                text = rightBadgeText,
                color = rightBadgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1,
                modifier = if (onRightBadgeClick != null) Modifier.clickable { onRightBadgeClick() } else Modifier
            )
            Text(
                text = "]--+",
                color = borderColor,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1
            )
        } else {
            Text(
                text = "+",
                color = borderColor,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1
            )
        }
    }
}

@Composable
fun AsciiBoxLeftRightRow(
    left: String,
    right: String,
    leftColor: Color = LocalTickPalette.current.fg,
    rightColor: Color = LocalTickPalette.current.fgMuted,
    borderColor: Color = LocalTickPalette.current.border,
    fontSizeSp: Int = 14,
    boldLeft: Boolean = false,
    boldRight: Boolean = false,
    underlineRight: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = left,
            color = leftColor,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 2).sp,
            fontWeight = if (boldLeft) FontWeight.Bold else FontWeight.Normal,
            fontFamily = LocalTickFontFamily.current,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (right.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = right,
                color = rightColor,
                fontSize = (fontSizeSp - 1).coerceAtLeast(11).sp,
                lineHeight = (fontSizeSp + 1).sp,
                fontWeight = if (boldRight) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (underlineRight) TextDecoration.Underline else TextDecoration.None,
                fontFamily = LocalTickFontFamily.current,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = " |",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

/**
 * Multi-line-safe ASCII body row: tracks visual line count via `onTextLayout`
 * and repeats left/right `|` rails on every wrapped line so ASCII walls never break.
 */
@Composable
fun AsciiBoxBodyRow(
    text: String,
    textColor: Color = LocalTickPalette.current.fg,
    borderColor: Color = LocalTickPalette.current.border,
    fontSizeSp: Int = 14,
    bold: Boolean = false,
    strikethrough: Boolean = false,
    singleLine: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    var lineCount by remember(text, fontSizeSp, singleLine) { mutableIntStateOf(1) }
    val leftRail = remember(lineCount) { List(lineCount) { "| " }.joinToString("\n") }
    val rightRail = remember(lineCount) { List(lineCount) { " |" }.joinToString("\n") }
    val rowLineHeight = (fontSizeSp + 3).sp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = leftRail,
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = rowLineHeight,
            fontFamily = FontFamily.Monospace,
            softWrap = false
        )
        Text(
            text = text,
            color = textColor,
            fontSize = fontSizeSp.sp,
            lineHeight = rowLineHeight,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = LocalTickFontFamily.current,
            textDecoration = if (strikethrough) TextDecoration.LineThrough else TextDecoration.None,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            softWrap = !singleLine,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            onTextLayout = { layoutResult ->
                if (!singleLine) {
                    val count = layoutResult.lineCount.coerceAtLeast(1)
                    if (count != lineCount) lineCount = count
                }
            },
            modifier = Modifier.weight(1f)
        )
        Text(
            text = rightRail,
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = rowLineHeight,
            fontFamily = FontFamily.Monospace,
            softWrap = false
        )
    }
}

@Composable
fun AsciiBoxAnnotatedBodyRow(
    annotatedText: AnnotatedString,
    borderColor: Color = LocalTickPalette.current.border,
    fontSizeSp: Int = 14,
    singleLine: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    var lineCount by remember(annotatedText, fontSizeSp, singleLine) { mutableIntStateOf(1) }
    val leftRail = remember(lineCount) { List(lineCount) { "| " }.joinToString("\n") }
    val rightRail = remember(lineCount) { List(lineCount) { " |" }.joinToString("\n") }
    val rowLineHeight = (fontSizeSp + 3).sp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = leftRail,
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = rowLineHeight,
            fontFamily = FontFamily.Monospace,
            softWrap = false
        )
        Text(
            text = annotatedText,
            fontSize = fontSizeSp.sp,
            lineHeight = rowLineHeight,
            fontFamily = LocalTickFontFamily.current,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            softWrap = !singleLine,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            onTextLayout = { layoutResult ->
                if (!singleLine) {
                    val count = layoutResult.lineCount.coerceAtLeast(1)
                    if (count != lineCount) lineCount = count
                }
            },
            modifier = Modifier.weight(1f)
        )
        Text(
            text = rightRail,
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = rowLineHeight,
            fontFamily = FontFamily.Monospace,
            softWrap = false
        )
    }
}

/**
 * Terminal reverse-video option selector: active option renders inverted (`activeColor` block
 * with `palette.bg` ink), unselected options remain clean unadorned text (no underline clutter).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> AsciiBoxOptionRow(
    label: String,
    options: List<Pair<T, String>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    activeColor: Color = LocalTickPalette.current.project,
    borderColor: Color = LocalTickPalette.current.border,
    fontSizeSp: Int = 13
) {
    val palette = LocalTickPalette.current
    val appFont = LocalTickFontFamily.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (label.isNotEmpty()) {
                Text(
                    text = "$label:",
                    color = palette.fgMuted,
                    fontSize = (fontSizeSp - 1).sp,
                    fontFamily = appFont,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            options.forEach { (value, text) ->
                val isSelected = value == selectedValue
                Text(
                    text = if (isSelected) " $text " else text,
                    color = if (isSelected) palette.bg else palette.fg,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = (fontSizeSp - 1).sp,
                    fontFamily = appFont,
                    modifier = Modifier
                        .then(if (isSelected) Modifier.background(activeColor) else Modifier)
                        .clickable { onSelect(value) }
                        .padding(vertical = 2.dp, horizontal = if (isSelected) 2.dp else 3.dp)
                )
            }
        }
        Text(
            text = " |",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
fun AsciiBoxActionRow(
    actions: List<Triple<String, Color, () -> Unit>>,
    borderColor: Color = LocalTickPalette.current.border,
    fontSizeSp: Int = 13,
    underline: Boolean = false
) {
    val appFont = LocalTickFontFamily.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "| ",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { (label, color, onClick) ->
                Text(
                    text = label,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 2).sp,
                    fontFamily = appFont,
                    textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
                    modifier = Modifier
                        .clickable { onClick() }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                )
            }
        }
        Text(
            text = " |",
            color = borderColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1
        )
    }
}

/**
 * Uniform Pure-ASCII `[+ Add]` floating action box resting on `palette.bgSurface`.
 */
@Composable
fun AsciiFloatingAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTickPalette.current
    val monoFont = FontFamily.Monospace
    Column(
        modifier = modifier
            .background(palette.bgSurface)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "+---------+",
            color = palette.border,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            fontFamily = monoFont
        )
        Text(
            text = "| [+ Add] |",
            color = palette.reminder,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontFamily = monoFont
        )
        Text(
            text = "+---------+",
            color = palette.border,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            fontFamily = monoFont
        )
    }
}
