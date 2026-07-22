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

private val GHOST_BACKGROUND = JBColor(Color(255, 221, 221), Color(77, 42, 42))
private val GHOST_TEXT = JBColor(Color(153, 30, 30), Color(224, 130, 130))

/** Renders Claude's pre-session lines as struck-through "ghost" text above the current lines — same look as GitHub Copilot's inline diff preview. */
class OldCodeBlockRenderer(private val lines: List<String>) : EditorCustomElementRenderer {

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

        g.color = GHOST_BACKGROUND
        g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

        g.color = GHOST_TEXT
        lines.forEachIndexed { i, line ->
            val baseline = targetRegion.y + i * lineHeight + fontMetrics.ascent
            val x = targetRegion.x + JBUI.scale(4)
            g.drawString(line, x, baseline)
            val strikeY = baseline - fontMetrics.ascent / 3
            g.drawLine(x, strikeY, x + fontMetrics.stringWidth(line), strikeY)
        }
    }
}
