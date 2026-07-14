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

    fun projectsDir(projectBasePath: String): Path {
        val encoded = projectBasePath.replace("/", "-")
        return Path(System.getProperty("user.home"), ".claude", "projects", encoded)
    }

    fun listSessions(projectBasePath: String): List<SessionInfo> {
        val dir = projectsDir(projectBasePath)
        if (!dir.exists()) return emptyList()

        return dir.listDirectoryEntries("*.jsonl")
            .mapNotNull { parseTranscript(it) }
            .sortedByDescending { it.startTimeMillis }
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
            val message = obj.getAsJsonObject("message") ?: return@forEachLine
            val content = message.getAsJsonArray("content") ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                if (blockObj.get("type")?.asString != "tool_use") continue
                val name = blockObj.get("name")?.asString ?: continue
                if (name !in TOOL_NAMES_WITH_FILE) continue
                val input = blockObj.getAsJsonObject("input") ?: continue
                val filePath = input.get("file_path")?.asString ?: input.get("notebook_path")?.asString
                if (filePath != null) touched.add(filePath)
            }
        }
        return touched
    }

    private fun parseTranscript(path: Path): SessionInfo? {
        var sessionId: String? = null
        var minTimestampMillis: Long? = null

        File(path.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: JsonSyntaxException) {
                return@forEachLine
            } catch (e: IllegalStateException) {
                return@forEachLine
            }

            if (sessionId == null && obj.has("sessionId")) {
                sessionId = obj.get("sessionId").asString
            }

            if (obj.has("timestamp")) {
                val millis = try {
                    Instant.parse(obj.get("timestamp").asString).toEpochMilli()
                } catch (e: Exception) {
                    null
                }
                if (millis != null && (minTimestampMillis == null || millis < minTimestampMillis!!)) {
                    minTimestampMillis = millis
                }
            }
        }

        val id = sessionId ?: path.fileName.toString().removeSuffix(".jsonl")
        val startTime = minTimestampMillis ?: return null
        val touchedFiles = touchedFilesIn(path)
        if (touchedFiles.isEmpty()) return null

        return SessionInfo(
            sessionId = id,
            transcriptPath = path,
            startTimeMillis = startTime,
            touchedFileCount = touchedFiles.size,
        )
    }
}
