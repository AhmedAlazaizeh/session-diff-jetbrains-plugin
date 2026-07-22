package com.progressoft.sessiondiff

import java.io.File

/**
 * Which session gets inline Keep/Reject review in the editor — defaults to the latest session for
 * the project, but the user can pin a different one via a session card's "..." menu. Two sessions
 * touching the same file would have conflicting baselines, so exactly one is ever "active" at a time.
 */
object ActiveSessionStore {
    private fun storeFile(projectBasePath: String): File {
        val encoded = projectBasePath.replace("/", "-")
        return File(System.getProperty("user.home"), ".claude/session-diff-history/_active-session/$encoded.txt")
    }

    fun get(projectBasePath: String): String? {
        val file = storeFile(projectBasePath)
        return if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    fun set(projectBasePath: String, sessionId: String) {
        val file = storeFile(projectBasePath)
        file.parentFile.mkdirs()
        file.writeText(sessionId)
    }
}
