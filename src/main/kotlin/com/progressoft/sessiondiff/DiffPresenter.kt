package com.progressoft.sessiondiff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import java.io.File
import java.io.IOException
import kotlin.io.path.Path

object DiffPresenter {

    /** Change summary (added/deleted line counts, new/deleted/modified) for every file this session touched. */
    fun fileSummaries(project: Project, session: SessionInfo): List<FileChangeSummary> {
        val projectBasePath = project.basePath ?: return emptyList()
        return SessionDiscoveryService.touchedFilesIn(session.transcriptPath)
            .mapNotNull { absolutePath -> summaryFor(session, projectBasePath, absolutePath) }
            .sortedBy { it.relpath }
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
        val afterExists = currentFile.exists()
        val afterBytes = if (afterExists) currentFile.readBytes() else ByteArray(0)
        val category = when {
            !afterExists && beforeBytes.isNotEmpty() -> ChangeCategory.DELETED
            beforeBytes.isEmpty() && afterExists -> ChangeCategory.NEW
            else -> ChangeCategory.MODIFIED
        }

        if (isBinary(beforeBytes) || isBinary(afterBytes)) {
            return FileChangeSummary(relpath, category, linesAdded = 0, linesDeleted = 0, isBinary = true)
        }

        // Approximate line stats only — reuses the platform's own diff engine (the same one behind
        // the actual diff view) rather than hand-rolling a diff algorithm just to count lines.
        val fragments = ComparisonManager.getInstance().compareLines(
            String(beforeBytes, Charsets.UTF_8),
            String(afterBytes, Charsets.UTF_8),
            ComparisonPolicy.DEFAULT,
            EmptyProgressIndicator(),
        )
        val added = fragments.sumOf { it.endLine2 - it.startLine2 }
        val deleted = fragments.sumOf { it.endLine1 - it.startLine1 }

        return FileChangeSummary(relpath, category, added, deleted, isBinary = false)
    }

    // Git's own heuristic: a NUL byte anywhere in the first chunk means binary.
    private fun isBinary(bytes: ByteArray): Boolean = bytes.take(8000).any { it == 0.toByte() }

    /** Opens a native diff view for a single file this session touched. */
    fun showDiffForFile(project: Project, session: SessionInfo, relpath: String) {
        val projectBasePath = project.basePath ?: return
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
        val afterBytes = if (currentFile.exists()) currentFile.readBytes() else ByteArray(0)

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(currentFile.name)
        val diffContentFactory = DiffContentFactory.getInstance()
        val diffContent = try {
            // createFromBytes does its own charset detection from the actual bytes —
            // String(bytes) would silently use the JVM's platform-default charset instead.
            val before = diffContentFactory.createFromBytes(project, beforeBytes, fileType, "before/$relpath")
            val after = diffContentFactory.createFromBytes(project, afterBytes, fileType, "after/$relpath")
            before to after
        } catch (e: IOException) {
            notify(project, "Could not open diff", "Failed to read $relpath", NotificationType.ERROR)
            return
        }

        val request = SimpleDiffRequest(relpath, diffContent.first, diffContent.second, "Before", "After")
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Sessions")
            .createNotification(title, content, type)
            .notify(project)
    }
}
