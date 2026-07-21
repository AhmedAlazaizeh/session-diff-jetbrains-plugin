package com.progressoft.sessiondiff

import java.io.File

enum class HunkDecision { KEPT, REJECTED }

/**
 * Remembers which hunks the user already clicked Keep/Reject on (and which of the two), so the
 * action bar doesn't reappear for them and the file list can show accepted/rejected status.
 * Keyed by content, not offsets — offsets shift as the document changes, hunk text doesn't.
 */
object ResolvedHunks {
    private val cache = mutableMapOf<String, MutableMap<String, HunkDecision>>()

    private fun storeFile(sessionId: String): File =
        File(System.getProperty("user.home"), ".claude/session-diff-history/$sessionId/resolved-hunks.txt")

    private fun decisionsFor(sessionId: String): MutableMap<String, HunkDecision> = cache.getOrPut(sessionId) {
        val file = storeFile(sessionId)
        val map = mutableMapOf<String, HunkDecision>()
        if (file.exists()) {
            file.readLines().forEach { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@forEach
                val decision = try {
                    HunkDecision.valueOf(parts[1])
                } catch (e: IllegalArgumentException) {
                    return@forEach
                }
                map[parts[0]] = decision
            }
        }
        map
    }

    fun decisionFor(sessionId: String, relpath: String, oldText: String, newText: String): HunkDecision? =
        decisionsFor(sessionId)[key(relpath, oldText, newText)]

    fun isResolved(sessionId: String, relpath: String, oldText: String, newText: String): Boolean =
        decisionFor(sessionId, relpath, oldText, newText) != null

    fun mark(sessionId: String, relpath: String, oldText: String, newText: String, decision: HunkDecision) {
        val k = key(relpath, oldText, newText)
        val map = decisionsFor(sessionId)
        if (map[k] == decision) return
        map[k] = decision
        val file = storeFile(sessionId)
        file.parentFile.mkdirs()
        file.appendText("$k|${decision.name}\n")
    }

    private fun key(relpath: String, oldText: String, newText: String): String =
        "$relpath::${oldText.hashCode()}-${newText.hashCode()}"
}
