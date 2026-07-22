package com.ahmedalazaizeh.sessiondiff

import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import kotlin.io.path.Path

/**
 * Reconstructs a file's content as it stood right after one specific session's own edits, by
 * replaying every session's recorded Edit/Write/Bash-rm operations on that path in chronological
 * order — not diffing the live file, which also carries whatever any later session (or a manual
 * edit) did to it since.
 *
 * Only reliable when every operation on the path across the whole replay window is an Edit, Write,
 * or Bash `rm` — a NotebookEdit anywhere in that window (cell-based, not a text replace) makes the
 * reconstruction untrustworthy, so [reconstructEndOfSession] returns null rather than guess.
 */
object TranscriptReplay {

    private val RM_COMMAND_RE = Regex("""(?:^|[;&|]\s*)rm\s+((?:-\S+\s+)*)(\S+)""")

    private sealed class Op {
        abstract val timestampMillis: Long
        abstract val sessionId: String

        data class Edit(
            override val timestampMillis: Long,
            override val sessionId: String,
            val oldString: String,
            val newString: String,
            val replaceAll: Boolean,
        ) : Op()

        data class Write(override val timestampMillis: Long, override val sessionId: String, val content: String) : Op()
        data class Delete(override val timestampMillis: Long, override val sessionId: String) : Op()
        data class Unsupported(override val timestampMillis: Long, override val sessionId: String) : Op()
    }

    /** Null if replay isn't possible or reliable for this path — caller should fall back to diffing the live file. */
    fun reconstructEndOfSession(
        allSessions: List<SessionInfo>,
        targetSession: SessionInfo,
        absolutePath: String,
        projectBasePath: String,
    ): ByteArray? {
        val touchingSessions = allSessions.filter { session ->
            absolutePath in SessionDiscoveryService.touchedFilesIn(session.transcriptPath, projectBasePath) ||
                absolutePath in SessionDiscoveryService.bashDeletedFilesIn(session.transcriptPath, projectBasePath)
        }
        if (touchingSessions.none { it.sessionId == targetSession.sessionId }) return null
        val earliestSession = touchingSessions.minByOrNull { it.startTimeMillis } ?: return null

        val baseline = BaselineResolver.resolve(earliestSession, absolutePath, projectBasePath) as? Baseline.Found ?: return null
        if (isBinary(baseline.bytes)) return null // string-based replay doesn't apply to binary content

        val ops = touchingSessions
            .flatMap { session -> operationsFor(session, absolutePath, projectBasePath) }
            .sortedBy { it.timestampMillis }
        if (ops.isEmpty()) return null

        val lastIndexForTarget = ops.indexOfLast { it.sessionId == targetSession.sessionId }
        if (lastIndexForTarget == -1) return null

        var text: String? = String(baseline.bytes, Charsets.UTF_8)
        for (i in 0..lastIndexForTarget) {
            text = when (val op = ops[i]) {
                is Op.Write -> op.content
                is Op.Delete -> null
                is Op.Unsupported -> return null // e.g. a NotebookEdit touched this path — bail, don't guess
                is Op.Edit -> {
                    val current = text ?: return null // editing a path replay thinks doesn't exist — diverged
                    if (op.oldString !in current) return null // old_string missing — diverged, or the edit actually failed
                    if (op.replaceAll) current.replace(op.oldString, op.newString) else replaceFirstOccurrence(current, op.oldString, op.newString)
                }
            }
        }
        return text?.toByteArray(Charsets.UTF_8)
    }

    private fun replaceFirstOccurrence(text: String, oldValue: String, newValue: String): String {
        val index = text.indexOf(oldValue)
        if (index < 0) return text
        return text.substring(0, index) + newValue + text.substring(index + oldValue.length)
    }

    private fun isBinary(bytes: ByteArray): Boolean = bytes.take(8000).any { it == 0.toByte() }

    private fun operationsFor(session: SessionInfo, absolutePath: String, projectBasePath: String): List<Op> {
        val pending = mutableListOf<Pair<String, Op>>() // tool_use_id, provisional op
        val erroredToolUseIds = mutableSetOf<String>()

        File(session.transcriptPath.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: Exception) {
                return@forEachLine
            }

            val timestampMillis = obj.get("timestamp").jsonString()?.let {
                try {
                    Instant.parse(it).toEpochMilli()
                } catch (e: Exception) {
                    null
                }
            }

            val content = obj.get("message").jsonObject()?.get("content").jsonArray() ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                when (blockObj.get("type").jsonString()) {
                    "tool_result" -> {
                        val id = blockObj.get("tool_use_id").jsonString()
                        if (id != null && blockObj.get("is_error").jsonBoolean() == true) erroredToolUseIds.add(id)
                    }
                    "tool_use" -> {
                        val id = blockObj.get("id").jsonString() ?: continue
                        val name = blockObj.get("name").jsonString() ?: continue
                        val input = blockObj.get("input").jsonObject()
                        val ts = timestampMillis ?: continue
                        val op = operationFor(name, input, ts, session.sessionId, absolutePath, projectBasePath)
                        if (op != null) pending.add(id to op)
                    }
                }
            }
        }

        return pending.filter { (id, _) -> id !in erroredToolUseIds }.map { it.second }
    }

    private fun operationFor(
        name: String,
        input: com.google.gson.JsonObject?,
        timestampMillis: Long,
        sessionId: String,
        absolutePath: String,
        projectBasePath: String,
    ): Op? = when (name) {
        "Edit" -> {
            if (input?.get("file_path").jsonString() != absolutePath) null else {
                val oldString = input?.get("old_string").jsonString()
                val newString = input?.get("new_string").jsonString()
                if (oldString != null && newString != null) {
                    Op.Edit(timestampMillis, sessionId, oldString, newString, input?.get("replace_all").jsonBoolean() ?: false)
                } else {
                    null
                }
            }
        }
        "Write" -> {
            if (input?.get("file_path").jsonString() != absolutePath) null else {
                input?.get("content").jsonString()?.let { Op.Write(timestampMillis, sessionId, it) }
            }
        }
        "NotebookEdit" -> {
            if (input?.get("notebook_path").jsonString() != absolutePath) null else Op.Unsupported(timestampMillis, sessionId)
        }
        "Bash" -> {
            val command = input?.get("command").jsonString()
            val match = command?.let { RM_COMMAND_RE.find(it) }
            val rawPath = match?.groupValues?.get(2)
            when {
                rawPath == null || rawPath.contains('*') || rawPath.contains('?') -> null
                else -> {
                    val rmAbsolutePath = if (rawPath.startsWith("/")) rawPath else Path(projectBasePath, rawPath).toString()
                    if (rmAbsolutePath == absolutePath) Op.Delete(timestampMillis, sessionId) else null
                }
            }
        }
        else -> null
    }
}
