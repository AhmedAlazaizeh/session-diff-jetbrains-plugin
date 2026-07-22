package com.ahmedalazaizeh.sessiondiff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.io.IOException
import kotlin.io.path.Path

object DiffPresenter {

    // One diff tab per session — clicking a file in an already-open session's diff closes that
    // tab and reopens fresh at the right index instead of opening a second tab.
    private val sessionDiffFiles = mutableMapOf<String, ChainDiffVirtualFile>()

    /** Whether this session's diff tab is genuinely still open (not just present in our own cache — the user may have closed it directly). */
    fun isDiffTabOpenFor(project: Project, session: SessionInfo): Boolean {
        val virtualFile = sessionDiffFiles[session.sessionId] ?: return false
        return FileEditorManager.getInstance(project).isFileOpen(virtualFile)
    }

    /** Change summary (added/deleted line counts, new/deleted/modified) for every file this session touched. */
    fun fileSummaries(project: Project, session: SessionInfo): List<FileChangeSummary> {
        val projectBasePath = project.basePath ?: return emptyList()
        val editedFiles = SessionDiscoveryService.touchedFilesIn(session.transcriptPath, projectBasePath)
        val bashDeletedFiles = SessionDiscoveryService.bashDeletedFilesIn(session.transcriptPath, projectBasePath)
        return (editedFiles + bashDeletedFiles)
            .mapNotNull { absolutePath -> summaryFor(session, projectBasePath, absolutePath) }
            .sortedBy { it.relpath }
    }

    /** Marks every still-pending hunk in this file as Kept — the file list's "Keep" button, whole-file at once. */
    fun keepAllPending(project: Project, session: SessionInfo, relpath: String) {
        val projectBasePath = project.basePath ?: return
        val absolutePath = Path(projectBasePath, relpath).toString()
        val baseline = BaselineResolver.resolve(session, absolutePath, projectBasePath) as? Baseline.Found ?: return
        val currentFile = File(absolutePath)
        if (!currentFile.isFile) return
        val afterBytes = currentFile.readBytes()
        if (isBinary(baseline.bytes) || isBinary(afterBytes)) return

        val beforeText = String(baseline.bytes, Charsets.UTF_8)
        val afterText = String(afterBytes, Charsets.UTF_8)
        // ponytail: same fragment computation as summaryFor() above — small enough that a shared
        // helper isn't obviously worth it yet; revisit if a third call site needs this.
        val fragments = ComparisonManager.getInstance().compareLines(
            beforeText, afterText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator(),
        )
        fragments.forEach { fragment ->
            val oldHunk = beforeText.substring(fragment.startOffset1, fragment.endOffset1)
            val newHunk = afterText.substring(fragment.startOffset2, fragment.endOffset2)
            if (ResolvedHunks.decisionFor(session.sessionId, relpath, oldHunk, newHunk) == null) {
                ResolvedHunks.mark(session.sessionId, relpath, oldHunk, newHunk, HunkDecision.KEPT)
            }
        }
    }

    /**
     * Reverts the whole file to its pre-session baseline and removes it from the file list — the
     * "Reject" button. A file Claude created this session (empty baseline) gets deleted outright
     * rather than emptied; a file Claude deleted gets recreated with its pre-session content.
     */
    fun rejectWholeFile(project: Project, session: SessionInfo, relpath: String) {
        val projectBasePath = project.basePath ?: return
        val absolutePath = Path(projectBasePath, relpath).toString()
        val baseline = BaselineResolver.resolve(session, absolutePath, projectBasePath) as? Baseline.Found ?: return
        val currentFile = File(absolutePath)
        if (currentFile.isDirectory) return
        val afterExists = currentFile.isFile

        when {
            baseline.bytes.isEmpty() && afterExists -> {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath) ?: return
                WriteAction.run<Throwable> { virtualFile.delete(this) }
            }
            !afterExists -> {
                val parentPath = currentFile.parent ?: return
                val parentDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentPath) ?: return
                WriteAction.run<Throwable> {
                    val newFile = parentDir.createChildData(this, currentFile.name)
                    newFile.setBinaryContent(baseline.bytes)
                }
            }
            else -> {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath) ?: return
                WriteAction.run<Throwable> { virtualFile.setBinaryContent(baseline.bytes) }
            }
        }
        // Fingerprint the post-revert state, not a bare flag — if Claude touches this file again
        // later in the same session, its content will no longer match and it reappears on its own.
        val revertedBytes = if (currentFile.isFile) currentFile.readBytes() else ByteArray(0)
        DismissedFiles.dismiss(session.sessionId, relpath, revertedBytes.contentHashCode().toLong())
    }

    private fun summaryFor(session: SessionInfo, projectBasePath: String, absolutePath: String): FileChangeSummary? {
        val relpath = try {
            Path(projectBasePath).relativize(Path(absolutePath)).toString()
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (relpath.startsWith("..")) return null // outside project root — matches session-diff.py's outside_cwd handling

        val baseline = BaselineResolver.resolve(session, absolutePath, projectBasePath)
        val beforeBytes = when (baseline) {
            is Baseline.Found -> baseline.bytes
            is Baseline.UntrackedNoBaseline -> ByteArray(0)
            Baseline.Missing -> ByteArray(0)
        }
        val currentFile = File(absolutePath)
        val afterExists = currentFile.isFile
        val afterBytes = if (afterExists) currentFile.readBytes() else ByteArray(0)
        // Fingerprint of the file's current content — if this still matches what it was when
        // dismissed, nothing has touched it since and it stays hidden; if Claude edited it again,
        // the fingerprint differs and it falls through to a fresh (likely PENDING) status below.
        val currentFingerprint = afterBytes.contentHashCode().toLong()
        if (DismissedFiles.isDismissed(session.sessionId, relpath, currentFingerprint)) return null

        val category = when {
            !afterExists && beforeBytes.isNotEmpty() -> ChangeCategory.DELETED
            beforeBytes.isEmpty() && afterExists -> ChangeCategory.NEW
            else -> ChangeCategory.MODIFIED
        }

        if (isBinary(beforeBytes) || isBinary(afterBytes)) {
            return FileChangeSummary(relpath, category, linesAdded = 0, linesDeleted = 0, isBinary = true, reviewStatus = FileReviewStatus.PENDING)
        }

        val beforeText = String(beforeBytes, Charsets.UTF_8)
        val afterText = String(afterBytes, Charsets.UTF_8)

        // Approximate line stats only — reuses the platform's own diff engine (the same one behind
        // the actual diff view) rather than hand-rolling a diff algorithm just to count lines.
        val fragments = ComparisonManager.getInstance().compareLines(
            beforeText, afterText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator(),
        )
        val added = fragments.sumOf { it.endLine2 - it.startLine2 }
        val deleted = fragments.sumOf { it.endLine1 - it.startLine1 }

        val decisions = fragments.map { fragment ->
            val oldHunk = beforeText.substring(fragment.startOffset1, fragment.endOffset1)
            val newHunk = afterText.substring(fragment.startOffset2, fragment.endOffset2)
            ResolvedHunks.decisionFor(session.sessionId, relpath, oldHunk, newHunk)
        }
        val reviewStatus = when {
            decisions.any { it == null } -> FileReviewStatus.PENDING
            decisions.any { it == HunkDecision.REJECTED } -> FileReviewStatus.HAS_REJECTIONS
            else -> FileReviewStatus.ACCEPTED
        }
        // Every hunk was resolved via the inline editor action bar, one at a time, with no bulk
        // file-list button ever clicked — nothing left to review, so drop it like a whole-file Reject would.
        if (reviewStatus != FileReviewStatus.PENDING) {
            DismissedFiles.dismiss(session.sessionId, relpath, currentFingerprint)
        }

        return FileChangeSummary(relpath, category, added, deleted, isBinary = false, reviewStatus = reviewStatus)
    }

    // Git's own heuristic: a NUL byte anywhere in the first chunk means binary.
    private fun isBinary(bytes: ByteArray): Boolean = bytes.take(8000).any { it == 0.toByte() }

    /**
     * Opens a native diff view for every file this session touched, focused on [relpath] — one
     * tab per session (not one per file), with the diff viewer's own Prev/Next to cycle through
     * the rest. Uses DiffEditorTabFilesManager + ChainDiffVirtualFile (the same mechanism behind
     * VCS's own multi-file diff tabs) specifically because it embeds as a real editor tab.
     *
     * SimpleDiffRequestChain bakes its initial position into a `private final ListSelection` at
     * construction (confirmed by decompiling it) — there's no supported way to move an
     * already-displayed chain's position afterwards. So instead of mutating in place, close any
     * existing tab for this session and reopen fresh at the right index; still only ever one tab
     * per session, just rebuilt rather than surgically updated.
     */
    fun showDiffForFile(project: Project, session: SessionInfo, relpath: String) {
        val projectBasePath = project.basePath ?: return
        val relpaths = fileSummaries(project, session).map { it.relpath }
        if (relpaths.isEmpty()) return
        val index = relpaths.indexOf(relpath).coerceAtLeast(0).coerceAtMost(relpaths.size - 1)

        sessionDiffFiles[session.sessionId]?.let { FileEditorManager.getInstance(project).closeFile(it) }

        val requests = relpaths.mapNotNull { buildRequest(project, session, projectBasePath, it) }
        if (requests.isEmpty()) return
        val chain = SimpleDiffRequestChain(requests, index)
        val virtualFile = ChainDiffVirtualFile(chain, session.title)
        sessionDiffFiles[session.sessionId] = virtualFile
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(virtualFile, true)
    }

    private fun buildRequest(project: Project, session: SessionInfo, projectBasePath: String, relpath: String): SimpleDiffRequest? {
        val absolutePath = Path(projectBasePath, relpath).toString()
        val baseline = BaselineResolver.resolve(session, absolutePath, projectBasePath)
        val beforeBytes = when (baseline) {
            is Baseline.Found -> baseline.bytes
            is Baseline.UntrackedNoBaseline -> {
                notify(
                    project,
                    "No baseline — untracked when touched",
                    "Whole-file diff shown, not just Claude's part: $relpath",
                    NotificationType.WARNING,
                )
                ByteArray(0)
            }
            Baseline.Missing -> ByteArray(0)
        }

        val currentFile = File(absolutePath)
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(currentFile.name)
        val diffContentFactory = DiffContentFactory.getInstance()
        val (before, after) = try {
            // createFromBytes does its own charset detection from the actual bytes —
            // String(bytes) would silently use the JVM's platform-default charset instead.
            val before = diffContentFactory.createFromBytes(project, beforeBytes, fileType, "before/$relpath")
            // createFile ties the content to the real VirtualFile — required for "Go to Source" to
            // work in the diff viewer. createFromBytes always produces synthetic, non-navigable content.
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(currentFile)
            val after: DiffContent = if (virtualFile != null) {
                diffContentFactory.createFile(project, virtualFile)!!
            } else {
                diffContentFactory.createFromBytes(project, ByteArray(0), fileType, "after/$relpath")
            }
            before to after
        } catch (e: IOException) {
            notify(project, "Could not open diff", "Failed to read $relpath", NotificationType.ERROR)
            return null
        }

        return SimpleDiffRequest(relpath, before, after, "Before", "After")
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Sessions")
            .createNotification(title, content, type)
            .notify(project)
    }
}
