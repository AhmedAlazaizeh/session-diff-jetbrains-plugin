package com.ahmedalazaizeh.sessiondiff

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

private val OLD_WORD_BACKGROUND = JBColor(Color(255, 221, 221), Color(77, 42, 42))
private val OLD_WORD_TEXT = JBColor(Color(153, 30, 30), Color(224, 130, 130))

/**
 * One removed word/phrase, struck through, rendered inline right next to its replacement —
 * git `--word-diff` style. Used for single-line modifications instead of [OldCodeBlockRenderer]'s
 * whole-line ghost block, which is reserved for multi-line hunks and pure add/delete.
 */
class OldWordInlineRenderer(private val text: String) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fontMetrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        return fontMetrics.stringWidth(text) + JBUI.scale(4)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val fontMetrics = g.fontMetrics

        g.color = OLD_WORD_BACKGROUND
        g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

        g.color = OLD_WORD_TEXT
        val baseline = targetRegion.y + (targetRegion.height + fontMetrics.ascent - fontMetrics.descent) / 2
        val x = targetRegion.x + JBUI.scale(2)
        g.drawString(text, x, baseline)
        val strikeY = baseline - fontMetrics.ascent / 3
        g.drawLine(x, strikeY, x + fontMetrics.stringWidth(text), strikeY)
    }
}
