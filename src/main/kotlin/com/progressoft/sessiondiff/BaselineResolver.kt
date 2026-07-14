package com.progressoft.sessiondiff

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
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
            if (obj.get("type")?.asString != "file-history-snapshot") return@forEachLine
            val tfb = obj.getAsJsonObject("snapshot")?.getAsJsonObject("trackedFileBackups") ?: return@forEachLine
            val entry = tfb.getAsJsonObject(relpath) ?: return@forEachLine
            val version = entry.get("version")?.asInt ?: return@forEachLine
            if (bestVersion == null || version < bestVersion!!) {
                bestVersion = version
                bestBackupFileName = entry.get("backupFileName")?.asString
            }
        }

        val backupFileName = bestBackupFileName ?: return null
        val historyFile = Path(System.getProperty("user.home"), ".claude", "file-history", sessionId, backupFileName)
        if (!historyFile.exists()) return null
        return historyFile.readBytes()
    }

    private fun isGitUntracked(absolutePath: String, projectBasePath: String): Boolean {
        return try {
            val process = ProcessBuilder("git", "-C", projectBasePath, "status", "--porcelain=v1", "--", absolutePath)
                .redirectErrorStream(false)
                .start()
            val output = ByteArrayOutputStream()
            process.inputStream.copyTo(output)
            process.waitFor()
            output.toString().lines().any { it.startsWith("??") }
        } catch (e: Exception) {
            false
        }
    }

    fun resolve(session: SessionInfo, absolutePath: String, projectBasePath: String): Baseline {
        val ownPath = ownStorePath(session.sessionId, absolutePath)
        if (ownPath.exists()) {
            return Baseline.Found(ownPath.readBytes())
        }

        val checkpointBytes = checkpointBaseline(session.transcriptPath, session.sessionId, absolutePath, projectBasePath)
        if (checkpointBytes != null) {
            return Baseline.Found(checkpointBytes)
        }

        val currentFile = Path(absolutePath)
        val currentBytes = if (currentFile.exists()) currentFile.readBytes() else ByteArray(0)
        return if (isGitUntracked(absolutePath, projectBasePath)) {
            Baseline.UntrackedNoBaseline(currentBytes)
        } else {
            Baseline.Missing
        }
    }
}
