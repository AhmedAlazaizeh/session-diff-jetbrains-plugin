package com.ahmedalazaizeh.sessiondiff

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle

/**
 * Renders Claude's pre-session line(s) as a ghost block above the current lines, in normal text
 * color — only the [highlightRangesPerLine] (character ranges within each line) get a highlighted
 * background, everything else reads exactly like ordinary code. [insertionPointsPerLine] marks
 * spots where new content was inserted with nothing removed from the old line at that exact point
 * (a zero-width diff on this side) — drawn as a thin caret-like line rather than a filled box, since
 * there's no old text there to highlight. Matches IntelliJ's own local-changes diff popup: a
 * modified-in-place line isn't struck through wholesale, just the part that actually changed (or
 * the point something was inserted) gets called out.
 */
class OldLinesWithHighlightsRenderer(
    private val lines: List<String>,
    private val highlightRangesPerLine: List<List<IntRange>>,
    private val insertionPointsPerLine: List<List<Int>> = emptyList(),
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fontMetrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        return (lines.maxOfOrNull { fontMetrics.stringWidth(it) } ?: 0) + JBUI.scale(8)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * lines.size.coerceAtLeast(1)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val fontMetrics = g.fontMetrics
        val lineHeight = editor.lineHeight
        val startX = targetRegion.x + JBUI.scale(4)

        lines.forEachIndexed { i, line ->
            val y = targetRegion.y + i * lineHeight
            val baseline = y + fontMetrics.ascent + (lineHeight - fontMetrics.height) / 2

            // Same two-tone treatment as the live line below it: a neutral grey wash across the
            // whole old line, a stronger dose of that same grey on just the word(s) that actually
            // changed — old/removed content reads as de-emphasized, not red.
            g.color = DELETED_LINE_BACKGROUND_COLOR
            g.fillRect(targetRegion.x, y, targetRegion.width, lineHeight)

            highlightRangesPerLine.getOrNull(i)?.forEach { range ->
                val xStart = startX + fontMetrics.stringWidth(line.substring(0, range.first))
                val xEnd = startX + fontMetrics.stringWidth(line.substring(0, range.last + 1))
                g.color = DELETED_WORD_EMPHASIS_COLOR
                g.fillRect(xStart, y, xEnd - xStart, lineHeight)
            }

            g.color = editor.colorsScheme.defaultForeground
            g.drawString(line, startX, baseline)

            insertionPointsPerLine.getOrNull(i)?.forEach { col ->
                val x = startX + fontMetrics.stringWidth(line.substring(0, col))
                g.color = DELETED_WORD_EMPHASIS_COLOR
                g.fillRect(x, y + JBUI.scale(2), JBUI.scale(2), lineHeight - JBUI.scale(4))
            }
        }
    }
}
