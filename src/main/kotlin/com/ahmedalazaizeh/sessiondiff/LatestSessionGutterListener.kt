package com.ahmedalazaizeh.sessiondiff

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Color
import kotlin.io.path.Path

private val NEW_LINE_BACKGROUND = JBColor(Color(226, 246, 226), Color(43, 66, 43))
private val WORD_HIGHLIGHT_BACKGROUND = JBColor(Color(255, 224, 130), Color(92, 74, 26))
private val MARKERS_KEY = Key.create<MutableList<() -> Unit>>("com.ahmedalazaizeh.sessiondiff.markers")
// What was last actually applied to this editor — lets refreshAllEditorsFor skip clearing and
// recreating every inlay/highlighter/action-bar on every 3s poll tick when nothing changed, which
// otherwise reads as the whole hunk flickering/refreshing even though nothing is actually different.
private val SIGNATURE_KEY = Key.create<String>("com.ahmedalazaizeh.sessiondiff.signature")

/**
 * Only the ACTIVE session gets inline markers — two overlapping sessions touching the same file
 * would have conflicting baselines, so there's no sane "which session" to show inline. Defaults to
 * the latest session, but the user can pin a different one via a session card's "..." menu
 * (ActiveSessionStore) — [refreshAllEditorsFor] re-runs this on every open editor when that changes,
 * since markers are otherwise only computed once, when an editor is created.
 *
 * A one-line edit renders git `--word-diff` style: only the changed word/phrase gets a highlight,
 * with the removed word struck through inline right next to it — not a separate ghost line. Multi-line
 * hunks and pure add/delete fall back to a whole-line treatment (struck-through ghost block above,
 * highlighted block below). Either way, a Keep/Reject/Show Diff action bar (a real embedded Swing
 * panel, not just a gutter icon) sits below each hunk.
 */
class LatestSessionGutterListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        applyIfChanged(event.editor, computePlan(event.editor))
    }

    companion object {

        /** Called every ~3s by the poll timer, and after any active-session change, for every open editor. */
        fun refreshAllEditorsFor(project: Project) {
            val editors = EditorFactory.getInstance().allEditors.filter { it.project == project }
            // Everything computePlan() does is disk IO (transcript scans, baseline resolution,
            // possibly a `git` subprocess) plus CPU-only diff comparison — none of it needs the EDT,
            // and this runs on every poll tick for every open editor. Only applyIfChanged()'s actual
            // Editor/Inlay/MarkupModel mutations need to happen there.
            ApplicationManager.getApplication().executeOnPooledThread {
                val plans = editors.associateWith { editor -> computePlan(editor) }
                ApplicationManager.getApplication().invokeLater(
                    { plans.forEach { (editor, plan) -> applyIfChanged(editor, plan) } },
                    ModalityState.any(),
                )
            }
        }

        /** Skips the clear+rebuild entirely when [plan]'s signature matches what's already showing. */
        private fun applyIfChanged(editor: Editor, plan: MarkerPlan?) {
            val signature = plan?.signature
            if (editor.getUserData(SIGNATURE_KEY) == signature) return
            clearMarkers(editor)
            editor.putUserData(SIGNATURE_KEY, signature)
            if (plan != null) applyPlan(plan)
        }

        private fun clearMarkers(editor: Editor) {
            editor.getUserData(MARKERS_KEY)?.forEach { dispose ->
                try {
                    dispose()
                } catch (e: Exception) {
                    // already-disposed by a per-hunk Keep/Reject click — safe to ignore.
                }
            }
            editor.putUserData(MARKERS_KEY, null)
        }

        private class MarkerPlan(
            val editor: Editor,
            val editorEx: EditorEx,
            val project: Project,
            val session: SessionInfo,
            val relpath: String,
            val beforeText: String,
            val afterText: String,
            val fragments: List<LineFragment>,
            val signature: String,
        )

        private fun computePlan(editor: Editor): MarkerPlan? {
            if (editor.editorKind != EditorKind.MAIN_EDITOR) return null // skip diff-viewer/console/preview editors
            val editorEx = editor as? EditorEx ?: return null
            val project = editor.project ?: return null
            val basePath = project.basePath ?: return null
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null

            val session = SessionDiscoveryService.activeSessionFor(basePath) ?: return null
            if (file.path !in SessionDiscoveryService.touchedFilesIn(session.transcriptPath, basePath)) return null

            val relpath = try {
                Path(basePath).relativize(Path(file.path)).toString()
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (relpath.startsWith("..")) return null

            val baseline = BaselineResolver.resolve(session, file.path, basePath)
            val beforeText = when (baseline) {
                is Baseline.Found -> String(baseline.bytes, Charsets.UTF_8)
                else -> return null // no real baseline — nothing meaningful to mark inline
            }
            // Document.getText() is a plain thread-safe read (no PSI/write-lock involved), so this
            // is fine to call from the pooled thread refreshAllEditorsFor computes plans on.
            val afterText = editor.document.text

            val fragments = ComparisonManager.getInstance().compareLines(
                beforeText, afterText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator(),
            )
            if (fragments.isEmpty()) return null

            // Encodes exactly what applyPlan renders: each hunk's position and its resolution
            // (or lack of one) — changes whenever the text OR a Keep/Reject decision changes,
            // not just the text, so a bulk "Keep All Pending" (no document edit at all) still
            // triggers a rebuild instead of leaving a stale action bar showing.
            val signature = buildString {
                append(session.sessionId).append('|').append(relpath)
                fragments.forEach { fragment ->
                    val oldHunk = beforeText.substring(fragment.startOffset1, fragment.endOffset1)
                    val newHunk = afterText.substring(fragment.startOffset2, fragment.endOffset2)
                    append('|').append(fragment.startOffset2).append(':').append(fragment.endOffset2)
                    append(':').append(ResolvedHunks.decisionFor(session.sessionId, relpath, oldHunk, newHunk))
                }
            }

            return MarkerPlan(editor, editorEx, project, session, relpath, beforeText, afterText, fragments, signature)
        }

        private fun applyPlan(plan: MarkerPlan) {
            if (plan.editor.isDisposed) return // editor may have closed while this was computing

            val editorWideDisposables = mutableListOf<() -> Unit>()
            val markup = plan.editor.markupModel
            for (fragment in plan.fragments) {
                val hunkBaselineText = plan.beforeText.substring(fragment.startOffset1, fragment.endOffset1)
                val hunkCurrentText = plan.afterText.substring(fragment.startOffset2, fragment.endOffset2)
                if (ResolvedHunks.isResolved(plan.session.sessionId, plan.relpath, hunkBaselineText, hunkCurrentText)) continue

                val hasOldText = fragment.startOffset1 != fragment.endOffset1
                val hasNewText = fragment.startOffset2 != fragment.endOffset2
                val disposables = mutableListOf<() -> Unit>()

                // Word-level inline diff (git --word-diff style) only makes sense for a genuine
                // one-line-to-one-line edit — multi-line hunks and pure add/delete fall back to the
                // whole-line ghost-block-above + full-line-highlight-below treatment.
                val isSingleLineModification = hasOldText && hasNewText &&
                    '\n' !in hunkBaselineText && '\n' !in hunkCurrentText

                if (isSingleLineModification) {
                    val innerFragments = ComparisonManager.getInstance().compareWords(
                        hunkBaselineText, hunkCurrentText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator(),
                    )
                    innerFragments.forEach { inner ->
                        val oldWord = hunkBaselineText.substring(inner.startOffset1, inner.endOffset1)
                        if (oldWord.isNotEmpty()) {
                            val insertAt = fragment.startOffset2 + inner.startOffset2
                            val wordInlay = plan.editor.inlayModel.addInlineElement(
                                insertAt, false, OldWordInlineRenderer(oldWord),
                            )
                            if (wordInlay != null) {
                                disposables.add { wordInlay.dispose() }
                                editorWideDisposables.add { wordInlay.dispose() }
                            }
                        }
                        if (inner.startOffset2 != inner.endOffset2) {
                            val highlighter = markup.addRangeHighlighter(
                                fragment.startOffset2 + inner.startOffset2,
                                fragment.startOffset2 + inner.endOffset2,
                                HighlighterLayer.LAST,
                                TextAttributes(null, WORD_HIGHLIGHT_BACKGROUND, null, null, 0),
                                HighlighterTargetArea.EXACT_RANGE,
                            )
                            disposables.add { markup.removeHighlighter(highlighter) }
                            editorWideDisposables.add { markup.removeHighlighter(highlighter) }
                        }
                    }
                } else {
                    if (hasOldText) {
                        val ghostInlay = plan.editor.inlayModel.addBlockElement(
                            fragment.startOffset2, false, true, 0, OldCodeBlockRenderer(hunkBaselineText.split("\n")),
                        )
                        if (ghostInlay != null) {
                            disposables.add { ghostInlay.dispose() }
                            editorWideDisposables.add { ghostInlay.dispose() }
                        }
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
                        editorWideDisposables.add { markup.removeHighlighter(highlighter) }
                    }
                }

                val rangeMarker = plan.editor.document.createRangeMarker(fragment.startOffset2, fragment.endOffset2)

                lateinit var actionBarInlay: com.intellij.openapi.editor.Inlay<*>
                val actionBar = HunkActionBar(
                    plan.project, plan.editor, plan.session.sessionId, plan.relpath, rangeMarker, hunkBaselineText, hunkCurrentText,
                ) {
                    disposables.forEach { it() }
                    actionBarInlay.dispose()
                }
                val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
                    plan.editorEx,
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
                editorWideDisposables.add { inlay.dispose() }
            }

            plan.editor.putUserData(MARKERS_KEY, editorWideDisposables)
        }
    }
}
