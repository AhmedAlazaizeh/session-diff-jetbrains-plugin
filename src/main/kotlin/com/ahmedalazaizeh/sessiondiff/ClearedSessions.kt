package com.ahmedalazaizeh.sessiondiff

import java.io.File

/**
 * Sessions the user cleared from the list — hidden per project, until that same session's transcript
 * grows past the size it was at clear time (the user resumed it and Claude touched more files, so
 * there's new material to review). Transcripts on disk are otherwise untouched.
 */
object ClearedSessions {
    private val cache = mutableMapOf<String, MutableMap<String, Long>>()

    private fun storeFile(projectBasePath: String): File {
        val encoded = projectBasePath.replace("/", "-")
        return File(System.getProperty("user.home"), ".claude/session-diff-history/_cleared-sessions/$encoded.txt")
    }

    private fun sizesFor(projectBasePath: String): MutableMap<String, Long> = cache.getOrPut(projectBasePath) {
        val file = storeFile(projectBasePath)
        val map = mutableMapOf<String, Long>()
        if (file.exists()) {
            file.readLines().forEach { line ->
                val parts = line.split("|", limit = 2)
                val sessionId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@forEach
                // Pre-0.2.5 lines are a bare session ID with no recorded size — never known to have
                // grown since, so treat as cleared forever rather than dropping them on the floor.
                val size = parts.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
                map[sessionId] = size
            }
        }
        map
    }

    fun isCleared(projectBasePath: String, sessionId: String, currentTranscriptSize: Long): Boolean {
        val sizeAtClear = sizesFor(projectBasePath)[sessionId] ?: return false
        return currentTranscriptSize <= sizeAtClear
    }

    fun clear(projectBasePath: String, sessionId: String, transcriptSize: Long) {
        sizesFor(projectBasePath)[sessionId] = transcriptSize
        val file = storeFile(projectBasePath)
        file.parentFile.mkdirs()
        file.appendText("$sessionId|$transcriptSize\n")
    }
}
