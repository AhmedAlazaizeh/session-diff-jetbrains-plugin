package com.progressoft.sessiondiff

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import javax.swing.Icon
import kotlin.io.path.Path

/**
 * Only the LATEST session gets inline gutter markers — two overlapping sessions touching the
 * same file would have conflicting baselines, so there's no sane "which session" to show inline.
 */
class LatestSessionGutterListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val basePath = project.basePath ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return

        val session = SessionDiscoveryService.listSessions(basePath).firstOrNull() ?: return
        if (file.path !in SessionDiscoveryService.touchedFilesIn(session.transcriptPath)) return

        val relpath = try {
            Path(basePath).relativize(Path(file.path)).toString()
        } catch (e: IllegalArgumentException) {
            return
        }
        if (relpath.startsWith("..")) return

        val baseline = BaselineResolver.resolve(session, file.path, basePath)
        val beforeText = when (baseline) {
            is Baseline.Found -> String(baseline.bytes, Charsets.UTF_8)
            else -> return // no real baseline — nothing meaningful to mark inline
        }
        val afterText = editor.document.text

        val fragments = ComparisonManager.getInstance().compareLines(
            beforeText, afterText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator(),
        )
        if (fragments.isEmpty()) return

        val markup = editor.markupModel
        for (fragment in fragments) {
            val kind = when {
                fragment.startOffset1 == fragment.endOffset1 -> HunkKind.ADDED
                fragment.startOffset2 == fragment.endOffset2 -> HunkKind.DELETED
                else -> HunkKind.MODIFIED
            }
            val highlighter = markup.addRangeHighlighter(
                fragment.startOffset2,
                fragment.endOffset2,
                HighlighterLayer.LAST,
                null,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.setLineMarkerRenderer(GutterMarkerRenderer(kind))
            val hunkBaselineText = beforeText.substring(fragment.startOffset1, fragment.endOffset1)
            highlighter.setGutterIconRenderer(
                HunkGutterIconRenderer(project, editor, session, relpath, fragment.startOffset2, fragment.endOffset2, hunkBaselineText),
            )
        }
    }
}

private class HunkGutterIconRenderer(
    private val project: Project,
    private val editor: Editor,
    private val session: SessionInfo,
    private val relpath: String,
    private val startOffset: Int,
    private val endOffset: Int,
    private val baselineHunkText: String,
) : com.intellij.openapi.editor.markup.GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.Actions.Diff
    override fun getTooltipText(): String = "Changed by Claude this session"

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)

    override fun getPopupMenuActions(): ActionGroup {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Rollback", "Revert this change back to before this session", AllIcons.Diff.Revert) {
            override fun actionPerformed(e: AnActionEvent) {
                WriteCommandAction.runWriteCommandAction(project) {
                    val currentEnd = minOf(endOffset, editor.document.textLength)
                    val currentStart = minOf(startOffset, currentEnd)
                    editor.document.replaceString(currentStart, currentEnd, baselineHunkText)
                }
            }
        })
        group.add(object : AnAction("Show Full File Diff", null, AllIcons.Actions.Diff) {
            override fun actionPerformed(e: AnActionEvent) {
                DiffPresenter.showDiffForFile(project, session, relpath)
            }
        })
        return group
    }
}
