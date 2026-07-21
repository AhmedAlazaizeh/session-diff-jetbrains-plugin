package com.progressoft.sessiondiff

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.exists

object SessionDiscoveryService {

    private val TOOL_NAMES_WITH_FILE = setOf("Edit", "Write", "NotebookEdit")
    private val RM_COMMAND_RE = Regex("""(?:^|[;&|]\s*)rm\s+((?:-\S+\s+)*)(\S+)""")

    fun projectsDir(projectBasePath: String): Path {
        val encoded = projectBasePath.replace("/", "-")
        return Path(System.getProperty("user.home"), ".claude", "projects", encoded)
    }

    fun listSessions(projectBasePath: String): List<SessionInfo> {
        val dir = projectsDir(projectBasePath)
        if (!dir.exists()) return emptyList()

        return dir.listDirectoryEntries("*.jsonl")
            .mapNotNull { parseTranscript(it, projectBasePath) }
            .sortedByDescending { it.startTimeMillis }
    }

    /**
     * Files removed via a Bash `rm <path>` command — Claude Code has no dedicated delete tool, so
     * this is the only way a file actually gets deleted, and it's otherwise invisible to
     * [touchedFilesIn] (Edit/Write/NotebookEdit only). Heuristic: resolves the single-target case
     * (no wildcards, no multiple paths) only — matches the common real-world pattern, not a full
     * shell parser.
     */
    fun bashDeletedFilesIn(transcriptPath: Path, projectBasePath: String): Set<String> {
        val deleted = mutableSetOf<String>()
        File(transcriptPath.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: JsonSyntaxException) {
                return@forEachLine
            } catch (e: IllegalStateException) {
                return@forEachLine
            }
            val message = obj.get("message").jsonObject() ?: return@forEachLine
            val content = message.get("content").jsonArray() ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                if (blockObj.get("type").jsonString() != "tool_use") continue
                if (blockObj.get("name").jsonString() != "Bash") continue
                val command = blockObj.get("input").jsonObject()?.get("command").jsonString() ?: continue
                val match = RM_COMMAND_RE.find(command) ?: continue
                val rawPath = match.groupValues[2]
                if (rawPath.contains('*') || rawPath.contains('?')) continue
                val absolutePath = if (rawPath.startsWith("/")) rawPath else Path(projectBasePath, rawPath).toString()
                deleted.add(absolutePath)
            }
        }
        return deleted
    }

    /**
     * Every absolute path touched by an Edit/Write/NotebookEdit tool_use in this transcript.
     * Shared by [parseTranscript] (needs only the count) and [DiffPresenter] (needs the actual paths) —
     * a single parsing pass so the two callers can't silently drift if the transcript shape ever changes.
     */
    fun touchedFilesIn(transcriptPath: Path): Set<String> {
        val touched = mutableSetOf<String>()
        File(transcriptPath.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: JsonSyntaxException) {
                return@forEachLine
            } catch (e: IllegalStateException) {
                return@forEachLine
            }
            val message = obj.get("message").jsonObject() ?: return@forEachLine
            // content is a plain string for simple text messages, and only an array of
            // blocks (tool_use/tool_result/etc.) for the rest — real transcripts mix both.
            val content = message.get("content").jsonArray() ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                if (blockObj.get("type").jsonString() != "tool_use") continue
                val name = blockObj.get("name").jsonString() ?: continue
                if (name !in TOOL_NAMES_WITH_FILE) continue
                val input = blockObj.get("input").jsonObject() ?: continue
                val filePath = input.get("file_path").jsonString() ?: input.get("notebook_path").jsonString()
                if (filePath != null) touched.add(filePath)
            }
        }
        return touched
    }

    private fun parseTranscript(path: Path, projectBasePath: String): SessionInfo? {
        var sessionId: String? = null
        var minTimestampMillis: Long? = null
        var aiTitle: String? = null
        var firstUserPrompt: String? = null

        File(path.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: JsonSyntaxException) {
                return@forEachLine
            } catch (e: IllegalStateException) {
                return@forEachLine
            }

            if (sessionId == null) {
                sessionId = obj.get("sessionId").jsonString()
            }

            val timestamp = obj.get("timestamp").jsonString()
            if (timestamp != null) {
                val millis = try {
                    Instant.parse(timestamp).toEpochMilli()
                } catch (e: Exception) {
                    null
                }
                if (millis != null && (minTimestampMillis == null || millis < minTimestampMillis!!)) {
                    minTimestampMillis = millis
                }
            }

            when (obj.get("type").jsonString()) {
                "ai-title" -> if (aiTitle == null) aiTitle = obj.get("aiTitle").jsonString()
                // isSidechain messages are subagent prompts, not what the user actually typed.
                "user" -> if (firstUserPrompt == null && obj.get("isSidechain").jsonBoolean() != true) {
                    firstUserPrompt = obj.get("message").jsonObject()?.get("content").jsonString()
                }
            }
        }

        val id = sessionId ?: path.fileName.toString().removeSuffix(".jsonl")
        val startTime = minTimestampMillis ?: return null
        val touchedFiles = touchedFilesIn(path) + bashDeletedFilesIn(path, projectBasePath)
        if (touchedFiles.isEmpty()) return null

        return SessionInfo(
            sessionId = id,
            transcriptPath = path,
            startTimeMillis = startTime,
            touchedFileCount = touchedFiles.size,
            title = (aiTitle ?: firstUserPrompt)?.let { firstLineTruncated(it) } ?: "Untitled session",
        )
    }

    private fun firstLineTruncated(text: String, maxLen: Int = 70): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: text
        return if (firstLine.length > maxLen) firstLine.take(maxLen - 1).trimEnd() + "…" else firstLine
    }
}
