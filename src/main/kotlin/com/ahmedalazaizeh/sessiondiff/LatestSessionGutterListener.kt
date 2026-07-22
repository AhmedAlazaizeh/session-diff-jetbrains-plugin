package com.ahmedalazaizeh.sessiondiff

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.DiffFragment
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
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Color
import kotlin.io.path.Path

// Used only for the gutter strip (a small solid indicator, not a text wash) — green for a changed
// live line. Old/removed content is neutral grey (see below), not red.
val INSERTED_COLOR = JBColor(Color(30, 150, 70), Color(90, 200, 120))

// All four picked directly from a real IntelliJ diff (dark theme) with a color picker — used as
// solid fills rather than alpha-blended, so they're an exact match regardless of what the actual
// editor background turns out to be. Light-theme values are derived (not measured) since only dark
// was checked.
val INSERTED_WORD_EMPHASIS_COLOR = JBColor(Color(140, 201, 161), Color(41, 68, 54))
val INSERTED_LINE_BACKGROUND_COLOR = JBColor(Color(188, 224, 200), Color(41, 52, 50))
val DELETED_LINE_BACKGROUND_COLOR = JBColor(Color(233, 233, 235), Color(53, 55, 58))
val DELETED_WORD_EMPHASIS_COLOR = JBColor(Color(214, 214, 214), Color(72, 74, 74))

// The gutter strip is a small indicator, not a wash over readable text, so it can stay fairly solid.
const val GUTTER_STRIP_ALPHA = 150

/** Same [color], just with [alpha] swapped in — used to derive the wash/emphasis pair from a single base color. */
fun withAlpha(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha)

/**
 * Extends [color] into the gutter's line-marker strip (the same narrow column VCS paints its own
 * change bars in, just left of the line numbers) — same mechanism, just our own color. Only
 * meaningful for a highlighter on a real document line; the ghost (old) lines rendered above them
 * are synthetic inlay content with no real gutter row to paint into.
 */
fun gutterStripRenderer(color: Color): LineMarkerRenderer = LineMarkerRenderer { _, g, r ->
    g.color = withAlpha(color, GUTTER_STRIP_ALPHA)
    g.fillRect(r.x, r.y, r.width, r.height)
}

private val MARKERS_KEY = Key.create<MutableList<() -> Unit>>("com.ahmedalazaizeh.sessiondiff.markers")
// What was last actually applied to this editor — lets refreshAllEditorsFor skip clearing and
// recreating every inlay/highlighter/action-bar on every 3s poll tick when nothing changed, which
// otherwise reads as the whole hunk flickering/refreshing even though nothing is actually different.
private val SIGNATURE_KEY = Key.create<String>("com.ahmedalazaizeh.sessiondiff.signature")

/**
 * Only the ACTIVE session gets inline markers. Defaults to the latest session, but the user can pin
 * a different (older) one via a session card's "..." menu (ActiveSessionStore) —
 * [refreshAllEditorsFor] re-runs this on every open editor when that changes, since markers are
 * otherwise only computed once, when an editor is created. If a later session also touched the same
 * file, the live document mixes both sessions' edits — [computePlan] replays the pinned session's
 * own edits via [TranscriptReplay] and remaps them onto live-document offsets, dropping any hunk a
 * later edit actually collides with (no single correct live position to anchor it at), rather than
 * either showing the later session's hunks too or hiding the whole file.
 *
 * Matches IntelliJ's own local-changes diff popup: a line modified in place shows its old text above
 * and current text below, both in normal color, with only the actually-changed word/phrase called out
 * via a highlight — not the whole line struck through or flooded green. Pure add/delete (or a change
 * too different to align word-by-word) falls back to a whole-line treatment instead. Either way, a
 * Keep/Reject/Show Diff action bar (a real embedded Swing panel, not just a gutter icon) sits below
 * each hunk.
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

        /**
         * A resolved hunk ready to render: offset1/2 always describe real spans in [MarkerPlan.beforeText]
         * / [MarkerPlan.afterText] respectively — even when computed via cross-session replay + remap
         * (see [computePlan]), never in some intermediate reconstructed text's own coordinate space.
         */
        private class Hunk(
            val startOffset1: Int,
            val endOffset1: Int,
            val startOffset2: Int,
            val endOffset2: Int,
            val innerFragments: List<DiffFragment>?,
        )

        private class MarkerPlan(
            val editor: Editor,
            val editorEx: EditorEx,
            val project: Project,
            val session: SessionInfo,
            val relpath: String,
            val beforeText: String,
            val afterText: String,
            val hunks: List<Hunk>,
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

            val allSessions = SessionDiscoveryService.listSessions(basePath)
            val laterSessionsAlsoTouchedThis = allSessions.filter { other ->
                other.sessionId != session.sessionId && other.startTimeMillis > session.startTimeMillis &&
                    (file.path in SessionDiscoveryService.touchedFilesIn(other.transcriptPath, basePath) ||
                        file.path in SessionDiscoveryService.bashDeletedFilesIn(other.transcriptPath, basePath))
            }

            val relpath = try {
                Path(basePath).relativize(Path(file.path)).toString()
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (relpath.startsWith("..")) return null

            val baseline = BaselineResolver.resolve(project, session, file.path, basePath)
            val beforeText = when (baseline) {
                is Baseline.Found -> String(baseline.bytes, Charsets.UTF_8)
                else -> return null // no real baseline — nothing meaningful to mark inline
            }
            // Document.getText() is a plain thread-safe read (no PSI/write-lock involved), so this
            // is fine to call from the pooled thread refreshAllEditorsFor computes plans on.
            val afterText = editor.document.text

            // compareLinesInner (not plain compareLines) additionally computes each fragment's
            // character-level innerFragments where the old/new text can be sensibly aligned word by
            // word — that's what lets applyPlan render precise word highlights instead of whole-line
            // color for a line modified in place.
            val hunks = if (laterSessionsAlsoTouchedThis.isEmpty()) {
                ComparisonManager.getInstance().compareLinesInner(beforeText, afterText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
                    .map { f -> Hunk(f.startOffset1, f.endOffset1, f.startOffset2, f.endOffset2, f.innerFragments) }
            } else {
                // A later session also touched this file, so the live text mixes both sessions'
                // edits — diffing baseline straight against the live text would misattribute the
                // later session's hunks to this (pinned, older) one. Replay reconstructs the file
                // exactly as this session left it, isolating its own hunks correctly; those hunks'
                // "after" offsets are then in that reconstructed text's coordinate space, not the
                // live document's, so remapHunksToLiveOffsets translates them — dropping any hunk a
                // later edit actually collides with, since there's no safe span to place it at.
                val sessionEndBytes = TranscriptReplay.reconstructEndOfSession(project, allSessions, session, file.path, basePath)
                    ?: return null
                val sessionEndText = String(sessionEndBytes, Charsets.UTF_8)
                val ownFragments = ComparisonManager.getInstance().compareLinesInner(
                    beforeText, sessionEndText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator(),
                )
                val laterFragments = ComparisonManager.getInstance().compareLines(
                    sessionEndText, afterText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator(),
                )
                remapHunksToLiveOffsets(ownFragments, laterFragments)
            }
            if (hunks.isEmpty()) return null

            // Encodes exactly what applyPlan renders: each hunk's position and its resolution
            // (or lack of one) — changes whenever the text OR a Keep/Reject decision changes,
            // not just the text, so a bulk "Keep All Pending" (no document edit at all) still
            // triggers a rebuild instead of leaving a stale action bar showing.
            val signature = buildString {
                append(session.sessionId).append('|').append(relpath)
                hunks.forEach { hunk ->
                    val oldHunk = beforeText.substring(hunk.startOffset1, hunk.endOffset1)
                    val newHunk = afterText.substring(hunk.startOffset2, hunk.endOffset2)
                    append('|').append(hunk.startOffset2).append(':').append(hunk.endOffset2)
                    append(':').append(ResolvedHunks.decisionFor(session.sessionId, relpath, oldHunk, newHunk))
                }
            }

            return MarkerPlan(editor, editorEx, project, session, relpath, beforeText, afterText, hunks, signature)
        }

        private fun applyPlan(plan: MarkerPlan) {
            if (plan.editor.isDisposed) return // editor may have closed while this was computing

            val editorWideDisposables = mutableListOf<() -> Unit>()
            val markup = plan.editor.markupModel
            for (hunk in plan.hunks) {
                val hunkBaselineText = plan.beforeText.substring(hunk.startOffset1, hunk.endOffset1)
                val hunkCurrentText = plan.afterText.substring(hunk.startOffset2, hunk.endOffset2)
                if (ResolvedHunks.isResolved(plan.session.sessionId, plan.relpath, hunkBaselineText, hunkCurrentText)) continue

                val hasOldText = hunk.startOffset1 != hunk.endOffset1
                val hasNewText = hunk.startOffset2 != hunk.endOffset2
                val disposables = mutableListOf<() -> Unit>()

                // innerFragments is only non-null where the platform found the old/new text sensibly
                // alignable word-by-word (a line — or several — modified in place). Pure add/delete,
                // or a change too different to align, gets the whole-line treatment instead. The
                // platform sometimes still returns innerFragments even when most of the line differs
                // (e.g. a renamed method plus a new argument) — in that case the "precise" word
                // highlight ends up covering nearly the whole line anyway, which just looks like a
                // solid wash with no useful contrast, so treat that as unalignable too.
                val innerFragments = hunk.innerFragments
                    ?.takeIf { isWordLevelAlignmentUseful(it, hunkBaselineText, hunkCurrentText) }

                if (innerFragments != null && hasOldText && hasNewText) {
                    val oldLines = hunkBaselineText.removeSuffix("\n").split("\n")
                    val oldRangesPerLine = List(oldLines.size) { mutableListOf<IntRange>() }
                    // A pure insertion (something added with nothing removed at that spot) is a
                    // zero-width range on the old side — splitRangeAcrossLines drops those, so track
                    // them separately as insertion points instead of just losing them silently.
                    val oldInsertionPointsPerLine = List(oldLines.size) { mutableListOf<Int>() }
                    innerFragments.forEach { inner ->
                        if (inner.startOffset1 == inner.endOffset1 && inner.startOffset2 != inner.endOffset2) {
                            val (lineIndex, col) = pointToLineColumn(hunkBaselineText, inner.startOffset1)
                            oldInsertionPointsPerLine.getOrNull(lineIndex)?.add(col)
                        } else {
                            splitRangeAcrossLines(hunkBaselineText, inner.startOffset1, inner.endOffset1)
                                .forEach { (lineIndex, startCol, endCol) -> oldRangesPerLine[lineIndex].add(startCol until endCol) }
                        }
                    }
                    if (oldRangesPerLine.any { it.isNotEmpty() } || oldInsertionPointsPerLine.any { it.isNotEmpty() }) {
                        val ghostInlay = plan.editor.inlayModel.addBlockElement(
                            hunk.startOffset2, false, true, 0,
                            OldLinesWithHighlightsRenderer(oldLines, oldRangesPerLine, oldInsertionPointsPerLine),
                        )
                        if (ghostInlay != null) {
                            disposables.add { ghostInlay.dispose() }
                            editorWideDisposables.add { ghostInlay.dispose() }
                        }
                    }

                    // Same two-tone treatment IntelliJ's own diff popup uses: a faint whole-line wash
                    // in the side's own color (green for the new/current line), plus a stronger dose
                    // of that same color on just the word(s) that actually changed — not a separate
                    // neutral highlight color unrelated to add/remove.
                    val insertedColor: Color = INSERTED_COLOR
                    val lineWash = markup.addRangeHighlighter(
                        hunk.startOffset2,
                        hunk.endOffset2,
                        HighlighterLayer.WARNING,
                        TextAttributes(null, INSERTED_LINE_BACKGROUND_COLOR, null, null, 0),
                        HighlighterTargetArea.LINES_IN_RANGE,
                    )
                    lineWash.lineMarkerRenderer = gutterStripRenderer(insertedColor)
                    disposables.add { markup.removeHighlighter(lineWash) }
                    editorWideDisposables.add { markup.removeHighlighter(lineWash) }

                    innerFragments.forEach { inner ->
                        if (inner.startOffset2 != inner.endOffset2) {
                            val highlighter = markup.addRangeHighlighter(
                                hunk.startOffset2 + inner.startOffset2,
                                hunk.startOffset2 + inner.endOffset2,
                                HighlighterLayer.LAST,
                                TextAttributes(null, INSERTED_WORD_EMPHASIS_COLOR, null, null, 0),
                                HighlighterTargetArea.EXACT_RANGE,
                            )
                            disposables.add { markup.removeHighlighter(highlighter) }
                            editorWideDisposables.add { markup.removeHighlighter(highlighter) }
                        }
                    }
                } else {
                    if (hasOldText) {
                        val ghostInlay = plan.editor.inlayModel.addBlockElement(
                            hunk.startOffset2, false, true, 0, OldCodeBlockRenderer(hunkBaselineText.split("\n")),
                        )
                        if (ghostInlay != null) {
                            disposables.add { ghostInlay.dispose() }
                            editorWideDisposables.add { ghostInlay.dispose() }
                        }
                    }

                    if (hasNewText) {
                        val highlighter = markup.addRangeHighlighter(
                            hunk.startOffset2,
                            hunk.endOffset2,
                            HighlighterLayer.LAST,
                            TextAttributes(null, INSERTED_LINE_BACKGROUND_COLOR, null, null, 0),
                            HighlighterTargetArea.LINES_IN_RANGE,
                        )
                        highlighter.lineMarkerRenderer = gutterStripRenderer(INSERTED_COLOR)
                        disposables.add { markup.removeHighlighter(highlighter) }
                        editorWideDisposables.add { markup.removeHighlighter(highlighter) }
                    }
                }

                val rangeMarker = plan.editor.document.createRangeMarker(hunk.startOffset2, hunk.endOffset2)

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
                        hunk.endOffset2,
                    ),
                ) ?: continue
                actionBarInlay = inlay
                editorWideDisposables.add { inlay.dispose() }
            }

            plan.editor.putUserData(MARKERS_KEY, editorWideDisposables)
        }

        /**
         * [ownFragments] are hunks between [MarkerPlan.beforeText] (offset1 side, already correct)
         * and a reconstructed "session-end" text (offset2 side — NOT the live document).
         * [laterFragments] is the diff from that same reconstructed text to the live document,
         * i.e. everything a later session did afterward. Translates each own-fragment's offset2 span
         * into the live document's coordinate space by accumulating the net length change of every
         * later-fragment strictly before it, and drops any own-fragment whose span is actually
         * touched by a later-fragment — that's a genuine conflict (the later session further
         * changed this exact spot), and there's no single correct live position to anchor it at.
         */
        private fun remapHunksToLiveOffsets(ownFragments: List<LineFragment>, laterFragments: List<LineFragment>): List<Hunk> {
            // Net length change of every later-fragment entirely before `offset` — valid to reuse
            // across a whole own-fragment's [start, end) span once we know (via the overlap check
            // below) that no later fragment falls inside that span, so the shift can't change partway.
            fun shiftBefore(offset: Int): Int {
                var shift = 0
                for (later in laterFragments) {
                    if (later.endOffset1 > offset) break // later fragments are in document order
                    shift += (later.endOffset2 - later.startOffset2) - (later.endOffset1 - later.startOffset1)
                }
                return shift
            }

            return ownFragments.mapNotNull { f ->
                // A later fragment overlapping this hunk's span at all is a genuine conflict — the
                // later session further changed this exact spot, so there's no single correct live
                // position to anchor it at.
                val conflicts = laterFragments.any { it.startOffset1 < f.endOffset2 && it.endOffset1 > f.startOffset2 }
                if (conflicts) return@mapNotNull null
                val shift = shiftBefore(f.startOffset2)
                Hunk(f.startOffset1, f.endOffset1, f.startOffset2 + shift, f.endOffset2 + shift, f.innerFragments)
            }
        }

        /**
         * True when the differing spans are a minority of both the old and new text — worth
         * highlighting precisely. False when most of the line changed anyway (a rename plus new
         * argument, say), where a "precise" highlight would just cover almost the whole line with
         * no real contrast against the wash underneath it.
         */
        private fun isWordLevelAlignmentUseful(innerFragments: List<DiffFragment>, oldText: String, newText: String): Boolean {
            val oldChanged = innerFragments.sumOf { it.endOffset1 - it.startOffset1 }
            val newChanged = innerFragments.sumOf { it.endOffset2 - it.startOffset2 }
            val oldRatio = if (oldText.isEmpty()) 0.0 else oldChanged.toDouble() / oldText.length
            val newRatio = if (newText.isEmpty()) 0.0 else newChanged.toDouble() / newText.length
            return oldRatio < 0.6 && newRatio < 0.6
        }

        /** Converts an absolute [offset] within [text] into a (lineIndex, column) pair. */
        private fun pointToLineColumn(text: String, offset: Int): Pair<Int, Int> {
            var lineStart = 0
            var lineIndex = 0
            while (true) {
                val newlineIndex = text.indexOf('\n', lineStart)
                val lineEnd = if (newlineIndex == -1) text.length else newlineIndex
                if (newlineIndex == -1 || offset <= lineEnd) return lineIndex to (offset - lineStart).coerceIn(0, lineEnd - lineStart)
                lineStart = newlineIndex + 1
                lineIndex++
            }
        }

        /**
         * Converts an absolute [start]..[end] offset range within [text] into per-line
         * (lineIndex, startCol, endCol) triples, splitting at each line boundary the range spans.
         */
        private fun splitRangeAcrossLines(text: String, start: Int, end: Int): List<Triple<Int, Int, Int>> {
            if (start >= end) return emptyList()
            val result = mutableListOf<Triple<Int, Int, Int>>()
            var lineStart = 0
            var lineIndex = 0
            while (lineStart <= text.length) {
                val newlineIndex = text.indexOf('\n', lineStart)
                val lineEnd = if (newlineIndex == -1) text.length else newlineIndex
                val rangeStart = maxOf(start, lineStart)
                val rangeEnd = minOf(end, lineEnd)
                if (rangeStart < rangeEnd) {
                    result.add(Triple(lineIndex, rangeStart - lineStart, rangeEnd - lineStart))
                }
                if (newlineIndex == -1) break
                lineStart = newlineIndex + 1
                lineIndex++
            }
            return result
        }
    }
}
