package com.progressoft.sessiondiff

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.exists

object SessionDiscoveryService {

    private val TOOL_NAMES_WITH_FILE = setOf("Edit", "Write", "NotebookEdit")
    private val RM_COMMAND_RE = Regex("""(?:^|[;&|]\s*)rm\s+((?:-\S+\s+)*)(\S+)""")

    private data class ParsedTranscript(
        val sessionId: String?,
        val startTimeMillis: Long?,
        val title: String,
        val touchedFiles: Set<String>,
        val bashDeletedFiles: Set<String>,
    )

    private data class CacheKey(val size: Long, val mtime: Long)

    // A session's transcript only ever grows (append-only NDJSON log) and this poller re-reads
    // every transcript on every tick — cache the one full-file scan per (size, mtime) instead of
    // re-parsing unchanged files every 3 seconds, and merge what used to be 3 separate full-file
    // passes (title/timestamp scan, touched-files scan, bash-rm scan) into one.
    private val cache = ConcurrentHashMap<String, Pair<CacheKey, ParsedTranscript>>()

    fun projectsDir(projectBasePath: String): Path {
        val encoded = projectBasePath.replace("/", "-")
        return Path(System.getProperty("user.home"), ".claude", "projects", encoded)
    }

    fun listSessions(projectBasePath: String): List<SessionInfo> {
        val dir = projectsDir(projectBasePath)
        if (!dir.exists()) return emptyList()

        return dir.listDirectoryEntries("*.jsonl")
            .mapNotNull { toSessionInfo(it, projectBasePath) }
            .filter { !ClearedSessions.isCleared(projectBasePath, it.sessionId, it.transcriptPath.toFile().length()) }
            .sortedByDescending { it.startTimeMillis }
    }

    /** The session inline editor review applies to — the user's pinned choice if it still exists, else the latest. */
    fun activeSessionFor(projectBasePath: String): SessionInfo? {
        val sessions = listSessions(projectBasePath)
        val pinnedId = ActiveSessionStore.get(projectBasePath)
        return sessions.firstOrNull { it.sessionId == pinnedId } ?: sessions.firstOrNull()
    }

    /**
     * Files removed via a Bash `rm <path>` command — Claude Code has no dedicated delete tool, so
     * this is the only way a file actually gets deleted, and it's otherwise invisible to
     * [touchedFilesIn] (Edit/Write/NotebookEdit only). Heuristic: resolves the single-target case
     * (no wildcards, no multiple paths) only — matches the common real-world pattern, not a full
     * shell parser.
     */
    fun bashDeletedFilesIn(transcriptPath: Path, projectBasePath: String): Set<String> =
        parsed(transcriptPath, projectBasePath).bashDeletedFiles

    /** Every absolute path touched by an Edit/Write/NotebookEdit tool_use in this transcript. */
    fun touchedFilesIn(transcriptPath: Path, projectBasePath: String): Set<String> =
        parsed(transcriptPath, projectBasePath).touchedFiles

    private fun toSessionInfo(path: Path, projectBasePath: String): SessionInfo? {
        val parsed = parsed(path, projectBasePath)
        val id = parsed.sessionId ?: path.fileName.toString().removeSuffix(".jsonl")
        val startTime = parsed.startTimeMillis ?: return null
        val touchedFiles = parsed.touchedFiles + parsed.bashDeletedFiles
        if (touchedFiles.isEmpty()) return null

        return SessionInfo(
            sessionId = id,
            transcriptPath = path,
            startTimeMillis = startTime,
            touchedFileCount = touchedFiles.size,
            title = parsed.title,
        )
    }

    private fun parsed(path: Path, projectBasePath: String): ParsedTranscript {
        val file = path.toFile()
        val key = CacheKey(file.length(), file.lastModified())
        val cacheKeyStr = path.toString()
        cache[cacheKeyStr]?.let { (cachedKey, cachedValue) -> if (cachedKey == key) return cachedValue }

        var sessionId: String? = null
        var minTimestampMillis: Long? = null
        var aiTitle: String? = null
        var firstUserPrompt: String? = null
        val touched = mutableSetOf<String>()
        val bashDeleted = mutableSetOf<String>()

        file.forEachLine { line ->
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

            // content is a plain string for simple text messages, and only an array of
            // blocks (tool_use/tool_result/etc.) for the rest — real transcripts mix both.
            val content = obj.get("message").jsonObject()?.get("content").jsonArray() ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                if (blockObj.get("type").jsonString() != "tool_use") continue
                val name = blockObj.get("name").jsonString() ?: continue
                val input = blockObj.get("input").jsonObject()
                if (name in TOOL_NAMES_WITH_FILE) {
                    val filePath = input?.get("file_path").jsonString() ?: input?.get("notebook_path").jsonString()
                    if (filePath != null) touched.add(filePath)
                } else if (name == "Bash") {
                    val command = input?.get("command").jsonString() ?: continue
                    val match = RM_COMMAND_RE.find(command) ?: continue
                    val rawPath = match.groupValues[2]
                    if (rawPath.contains('*') || rawPath.contains('?')) continue
                    val absolutePath = if (rawPath.startsWith("/")) rawPath else Path(projectBasePath, rawPath).toString()
                    bashDeleted.add(absolutePath)
                }
            }
        }

        val result = ParsedTranscript(
            sessionId = sessionId,
            startTimeMillis = minTimestampMillis,
            title = (aiTitle ?: firstUserPrompt)?.let { firstLineTruncated(it) } ?: "Untitled session",
            touchedFiles = touched,
            bashDeletedFiles = bashDeleted,
        )
        cache[cacheKeyStr] = key to result
        return result
    }

    private fun firstLineTruncated(text: String, maxLen: Int = 70): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: text
        return if (firstLine.length > maxLen) firstLine.take(maxLen - 1).trimEnd() + "…" else firstLine
    }
}
