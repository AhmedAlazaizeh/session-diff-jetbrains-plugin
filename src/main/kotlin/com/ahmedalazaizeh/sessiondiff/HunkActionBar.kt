package com.ahmedalazaizeh.sessiondiff

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

private val BAR_BACKGROUND = JBColor(Color(232, 232, 250), Color(52, 52, 74))
private val KEEP_COLOR = JBColor(Color(46, 160, 67), Color(63, 185, 80))
private val REJECT_COLOR = JBColor(Color(209, 36, 47), Color(224, 90, 90))

/**
 * The "Keep" / "Reject" bar rendered inline in the file, Copilot-style. Unlike Copilot's pending
 * suggestions, Claude already wrote this to disk — "Keep" just dismisses the markers (nothing to
 * apply), "Reject" is the actual destructive action (reverts this hunk to the pre-session baseline).
 */
class HunkActionBar(
    project: Project,
    editor: Editor,
    sessionId: String,
    relpath: String,
    rangeMarker: RangeMarker,
    baselineHunkText: String,
    currentHunkText: String,
    onDismiss: () -> Unit,
) : JPanel(BorderLayout(12, 0)) {

    init {
        isOpaque = true
        background = BAR_BACKGROUND
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)

        val label = JLabel("Changed by Claude this session")
        label.font = label.font.deriveFont(label.font.size2D - 1f)

        val keepButton = PillButton("Keep", KEEP_COLOR)
        keepButton.addActionListener {
            ResolvedHunks.mark(sessionId, relpath, baselineHunkText, currentHunkText, HunkDecision.KEPT)
            onDismiss()
        }

        val rejectButton = PillButton("Reject", REJECT_COLOR)
        rejectButton.addActionListener {
            ResolvedHunks.mark(sessionId, relpath, baselineHunkText, currentHunkText, HunkDecision.REJECTED)
            WriteCommandAction.runWriteCommandAction(project) {
                // Read from the RangeMarker, not a captured offset — earlier hunks' rollbacks
                // shift later hunks' positions, and RangeMarker tracks that automatically.
                editor.document.replaceString(rangeMarker.startOffset, rangeMarker.endOffset, baselineHunkText)
            }
            onDismiss()
        }

        val buttons = JPanel()
        buttons.isOpaque = false
        buttons.layout = BoxLayout(buttons, BoxLayout.X_AXIS)
        buttons.add(keepButton)
        buttons.add(Box.createHorizontalStrut(6))
        buttons.add(rejectButton)

        add(label, BorderLayout.WEST)
        add(buttons, BorderLayout.EAST)
    }
}
