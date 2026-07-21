package com.progressoft.sessiondiff

import java.io.File

/** Files whose entire change was rejected from the file list — removed from that session's list from then on. */
object DismissedFiles {
    private val cache = mutableMapOf<String, MutableSet<String>>()

    private fun storeFile(sessionId: String): File =
        File(System.getProperty("user.home"), ".claude/session-diff-history/$sessionId/dismissed-files.txt")

    private fun setFor(sessionId: String): MutableSet<String> = cache.getOrPut(sessionId) {
        val file = storeFile(sessionId)
        if (file.exists()) file.readLines().filterTo(mutableSetOf()) { it.isNotBlank() } else mutableSetOf()
    }

    fun isDismissed(sessionId: String, relpath: String): Boolean = relpath in setFor(sessionId)

    fun dismiss(sessionId: String, relpath: String) {
        if (!setFor(sessionId).add(relpath)) return
        val file = storeFile(sessionId)
        file.parentFile.mkdirs()
        file.appendText("$relpath\n")
    }
}
