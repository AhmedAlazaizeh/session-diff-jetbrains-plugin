package com.ahmedalazaizeh.sessiondiff

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

sealed class Baseline {
    data class Found(val bytes: ByteArray) : Baseline()
    data class UntrackedNoBaseline(val currentBytes: ByteArray) : Baseline()
    object Missing : Baseline()
}

object BaselineResolver {

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun ownStorePath(sessionId: String, absolutePath: String): Path {
        val key = sha256(absolutePath)
        return Path(System.getProperty("user.home"), ".claude", "session-diff-history", sessionId, key)
    }

    /** Claude Code's own checkpoint fallback: minimum-version trackedFileBackups entry for this path. */
    private fun checkpointBaseline(transcriptPath: Path, sessionId: String, absolutePath: String, projectBasePath: String): ByteArray? {
        val relpath = try {
            Path(projectBasePath).relativize(Path(absolutePath)).toString()
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (relpath.startsWith("..")) return null

        var bestVersion: Int? = null
        var bestBackupFileName: String? = null

        File(transcriptPath.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: Exception) {
                return@forEachLine
            }
            if (obj.get("type").jsonString() != "file-history-snapshot") return@forEachLine
            val tfb = obj.get("snapshot").jsonObject()?.get("trackedFileBackups").jsonObject() ?: return@forEachLine
            val entry = tfb.get(relpath).jsonObject() ?: return@forEachLine
            val version = entry.get("version").jsonInt() ?: return@forEachLine
            if (bestVersion == null || version < bestVersion!!) {
                bestVersion = version
                bestBackupFileName = entry.get("backupFileName").jsonString()
            }
        }

        val backupFileName = bestBackupFileName ?: return null
        val historyFile = Path(System.getProperty("user.home"), ".claude", "file-history", sessionId, backupFileName)
        if (!historyFile.exists()) return null
        return historyFile.readBytes()
    }

    // The platform already tracks VCS state itself (no subprocess, no need for our own cache on
    // top) — ProjectLevelVcsManager/ChangeListManager answer from that instead of spawning `git`.
    // gitShowHead below is the one remaining subprocess call, kept because reading a file's content
    // at HEAD has no equivalent platform-only API without a hard dependency on the git4idea plugin —
    // it's also a rare "last resort" path, not part of the hot per-file poll loop.
    private fun isInsideVcsWorkTree(project: Project): Boolean =
        ProjectLevelVcsManager.getInstance(project).hasActiveVcss()

    private fun isVcsUntracked(project: Project, absolutePath: String): Boolean {
        if (!isInsideVcsWorkTree(project)) return false
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return false
        return ChangeListManager.getInstance(project).isUnversioned(virtualFile)
    }

    /** Last resort when a file has no snapshot and no longer exists — almost always a Bash `rm`,
     *  which our own hook (Edit/Write/NotebookEdit PreToolUse only) never sees happen. */
    private fun gitShowHead(project: Project, absolutePath: String, projectBasePath: String): ByteArray? {
        if (!isInsideVcsWorkTree(project)) return null
        val relpath = try {
            Path(projectBasePath).relativize(Path(absolutePath)).toString()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            val process = ProcessBuilder("git", "-C", projectBasePath, "show", "HEAD:$relpath")
                .redirectErrorStream(false)
                .start()
            val output = ByteArrayOutputStream()
            process.inputStream.copyTo(output)
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) null else output.toByteArray()
        } catch (e: IOException) {
            null
        }
    }

    fun resolve(project: Project, session: SessionInfo, absolutePath: String, projectBasePath: String): Baseline {
        val ownPath = ownStorePath(session.sessionId, absolutePath)
        if (ownPath.exists()) {
            return Baseline.Found(ownPath.readBytes())
        }

        val checkpointBytes = checkpointBaseline(session.transcriptPath, session.sessionId, absolutePath, projectBasePath)
        if (checkpointBytes != null) {
            return Baseline.Found(checkpointBytes)
        }

        val currentFile = Path(absolutePath)
        val isRegularFile = currentFile.exists() && currentFile.toFile().isFile
        if (!isRegularFile) {
            val gitBytes = gitShowHead(project, absolutePath, projectBasePath)
            if (gitBytes != null) return Baseline.Found(gitBytes)
        }

        val currentBytes = if (isRegularFile) currentFile.readBytes() else ByteArray(0)
        return if (isVcsUntracked(project, absolutePath)) {
            Baseline.UntrackedNoBaseline(currentBytes)
        } else {
            Baseline.Missing
        }
    }
}
