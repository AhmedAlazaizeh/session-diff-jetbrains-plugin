package com.progressoft.sessiondiff

import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JButton

/**
 * A flat colored button that ignores the platform look-and-feel's own button chrome —
 * plain JButton.setBackground() clashes with Darcula's custom-painted skin (square fill behind
 * rounded default borders). Painting the pill ourselves sidesteps that entirely.
 */
private const val CORNER_ARC = 4

class PillButton(text: String, private val color: Color) : JButton(text) {
    init {
        isOpaque = false
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        foreground = Color.WHITE
        font = font.deriveFont(font.size2D - 2f)
        border = BorderFactory.createEmptyBorder(1, 7, 1, 7)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isRolloverEnabled = true
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (model.isRollover) color.brighter() else color
        g2.fillRoundRect(0, 0, width, height, CORNER_ARC, CORNER_ARC)
        g2.dispose()
        foreground = if (model.isRollover) Color.BLACK else Color.WHITE
        super.paintComponent(g)
    }
}
