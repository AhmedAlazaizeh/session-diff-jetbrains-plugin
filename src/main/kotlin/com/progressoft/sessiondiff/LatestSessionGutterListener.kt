package com.progressoft.sessiondiff

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.ui.JBColor
import java.awt.Color
import kotlin.io.path.Path

private val NEW_LINE_BACKGROUND = JBColor(Color(226, 246, 226), Color(43, 66, 43))

/**
 * Only the LATEST session gets inline markers — two overlapping sessions touching the
 * same file would have conflicting baselines, so there's no sane "which session" to show inline.
 *
 * Renders like GitHub Copilot's inline diff preview: removed/changed lines show as struck-through
 * "ghost" text above the current lines (via a block Inlay), current/changed lines get a green
 * background, and a Keep/Reject/Show Diff action bar (a real embedded Swing panel, not just a
 * gutter icon) sits below each hunk.
 */
class LatestSessionGutterListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return // skip diff-viewer/console/preview editors
        val editorEx = editor as? EditorEx ?: return
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
            val hunkBaselineText = beforeText.substring(fragment.startOffset1, fragment.endOffset1)
            val hunkCurrentText = afterText.substring(fragment.startOffset2, fragment.endOffset2)
            if (ResolvedHunks.isResolved(session.sessionId, relpath, hunkBaselineText, hunkCurrentText)) continue

            val hasOldText = fragment.startOffset1 != fragment.endOffset1
            val hasNewText = fragment.startOffset2 != fragment.endOffset2
            val disposables = mutableListOf<() -> Unit>()

            if (hasOldText) {
                val oldLines = beforeText.substring(fragment.startOffset1, fragment.endOffset1).split("\n")
                val ghostInlay = editor.inlayModel.addBlockElement(
                    fragment.startOffset2, false, true, 0, OldCodeBlockRenderer(oldLines),
                )
                if (ghostInlay != null) disposables.add { ghostInlay.dispose() }
            }

            if (hasNewText) {
                val highlighter = markup.addRangeHighlighter(
                    fragment.startOffset2,
                    fragment.endOffset2,
                    HighlighterLayer.LAST,
                    TextAttributes(null, NEW_LINE_BACKGROUND, null, null, 0),
                    HighlighterTargetArea.LINES_IN_RANGE,
                )
                disposables.add { markup.removeHighlighter(highlighter) }
            }

            val rangeMarker = editor.document.createRangeMarker(fragment.startOffset2, fragment.endOffset2)

            lateinit var actionBarInlay: com.intellij.openapi.editor.Inlay<*>
            val actionBar = HunkActionBar(
                project, editor, session.sessionId, relpath, rangeMarker, hunkBaselineText, hunkCurrentText,
            ) {
                disposables.forEach { it() }
                actionBarInlay.dispose()
            }
            val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
                editorEx,
                actionBar,
                EditorEmbeddedComponentManager.Properties(
                    EditorEmbeddedComponentManager.ResizePolicy.none(),
                    null,
                    false,
                    false,
                    0,
                    fragment.endOffset2,
                ),
            ) ?: continue
            actionBarInlay = inlay
        }
    }
}
