package com.progressoft.sessiondiff

import java.io.File

/** Sessions the user cleared from the list — hidden from then on, per project. Transcripts on disk untouched. */
object ClearedSessions {
    private val cache = mutableMapOf<String, MutableSet<String>>()

    private fun storeFile(projectBasePath: String): File {
        val encoded = projectBasePath.replace("/", "-")
        return File(System.getProperty("user.home"), ".claude/session-diff-history/_cleared-sessions/$encoded.txt")
    }

    private fun setFor(projectBasePath: String): MutableSet<String> = cache.getOrPut(projectBasePath) {
        val file = storeFile(projectBasePath)
        if (file.exists()) file.readLines().filterTo(mutableSetOf()) { it.isNotBlank() } else mutableSetOf()
    }

    fun isCleared(projectBasePath: String, sessionId: String): Boolean = sessionId in setFor(projectBasePath)

    fun clear(projectBasePath: String, sessionId: String) {
        if (!setFor(projectBasePath).add(sessionId)) return
        val file = storeFile(projectBasePath)
        file.parentFile.mkdirs()
        file.appendText("$sessionId\n")
    }
}
