package com.progressoft.sessiondiff

import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path

object DiffPresenter {

    fun showDiffForSession(project: Project, session: SessionInfo) {
        val projectBasePath = project.basePath ?: return
        val touchedFiles = touchedFilesFor(session)
        if (touchedFiles.isEmpty()) return

        val untrackedWarnings = mutableListOf<String>()
        val requests = mutableListOf<SimpleDiffRequest>()

        for (absolutePath in touchedFiles.sorted()) {
            val relpath = try {
                Path(projectBasePath).relativize(Path(absolutePath)).toString()
            } catch (e: IllegalArgumentException) {
                continue // outside project root — skip, matches session-diff.py's outside_cwd handling
            }
            if (relpath.startsWith("..")) continue

            val baseline = BaselineResolver.resolve(session, absolutePath, projectBasePath)
            val beforeBytes = when (baseline) {
                is Baseline.Found -> baseline.bytes
                is Baseline.UntrackedNoBaseline -> {
                    untrackedWarnings.add(relpath)
                    ByteArray(0)
                }
                Baseline.Missing -> ByteArray(0)
            }

            val currentFile = File(absolutePath)
            val afterBytes = if (currentFile.exists()) currentFile.readBytes() else ByteArray(0)

            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(currentFile.name)
            val diffContentFactory = DiffContentFactory.getInstance()
            val beforeContent = diffContentFactory.create(project, String(beforeBytes), fileType)
            val afterContent = diffContentFactory.create(project, String(afterBytes), fileType)

            requests.add(SimpleDiffRequest(relpath, beforeContent, afterContent, "Before", "After"))
        }

        if (untrackedWarnings.isNotEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Sessions")
                .createNotification(
                    "No baseline — untracked when touched",
                    "Whole-file diff shown, not just Claude's part: ${untrackedWarnings.joinToString(", ")}",
                    NotificationType.WARNING,
                )
                .notify(project)
        }

        val bashWarnings = BashWarningDetector.bashWarningsFor(session.transcriptPath)
        if (bashWarnings.isNotEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Sessions")
                .createNotification(
                    "Not checkpointed — verify manually",
                    "Bash commands that may have deleted/moved files:\n${bashWarnings.joinToString("\n")}",
                    NotificationType.WARNING,
                )
                .notify(project)
        }

        if (requests.isEmpty()) return
        val chain = SimpleDiffRequestChain(requests)
        DiffManager.getInstance().showDiff(project, chain, com.intellij.diff.DiffDialogHints.DEFAULT)
    }

    private fun touchedFilesFor(session: SessionInfo): Set<String> {
        val touched = mutableSetOf<String>()
        File(session.transcriptPath.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: Exception) {
                return@forEachLine
            }
            val message = obj.getAsJsonObject("message") ?: return@forEachLine
            val content = message.getAsJsonArray("content") ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                if (blockObj.get("type")?.asString != "tool_use") continue
                val name = blockObj.get("name")?.asString ?: continue
                if (name !in setOf("Edit", "Write", "NotebookEdit")) continue
                val input = blockObj.getAsJsonObject("input") ?: continue
                val filePath = input.get("file_path")?.asString ?: input.get("notebook_path")?.asString
                if (filePath != null) touched.add(filePath)
            }
        }
        return touched
    }
}
