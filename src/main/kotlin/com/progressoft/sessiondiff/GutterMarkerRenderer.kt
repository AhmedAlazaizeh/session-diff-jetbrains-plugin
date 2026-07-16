package com.progressoft.sessiondiff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

enum class HunkKind { ADDED, DELETED, MODIFIED }

private val ADDED_BAR_COLOR = JBColor(Color(89, 168, 105), Color(89, 168, 105))
private val DELETED_BAR_COLOR = JBColor(Color(196, 0, 0), Color(199, 84, 80))
private val MODIFIED_BAR_COLOR = JBColor(Color(60, 120, 200), Color(90, 140, 220))

/** Paints the colored gutter bar for a session-diff hunk — a thin notch for pure deletions (nothing to span). */
class GutterMarkerRenderer(private val kind: HunkKind) : LineMarkerRenderer {
    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        g.color = when (kind) {
            HunkKind.ADDED -> ADDED_BAR_COLOR
            HunkKind.DELETED -> DELETED_BAR_COLOR
            HunkKind.MODIFIED -> MODIFIED_BAR_COLOR
        }
        if (kind == HunkKind.DELETED && r.height <= 2) {
            val size = JBUI.scale(6)
            g.fillPolygon(intArrayOf(r.x, r.x + size, r.x), intArrayOf(r.y - size / 2, r.y, r.y + size / 2), 3)
        } else {
            g.fillRect(r.x, r.y, JBUI.scale(4), r.height)
        }
    }
}
