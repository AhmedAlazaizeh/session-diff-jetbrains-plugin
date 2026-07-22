package com.ahmedalazaizeh.sessiondiff

import java.io.File

/**
 * Files dismissed from a session's file list — hidden until that file's content actually changes
 * again (Claude touched it again after the dismiss, so there's new material to review). Mirrors
 * [ClearedSessions]' same snapshot-and-expire approach, just keyed by (session, file) and
 * fingerprinted by content hash instead of transcript size.
 */
object DismissedFiles {
    private val cache = mutableMapOf<String, MutableMap<String, Long>>()

    private fun storeFile(sessionId: String): File =
        File(System.getProperty("user.home"), ".claude/session-diff-history/$sessionId/dismissed-files.txt")

    private fun fingerprintsFor(sessionId: String): MutableMap<String, Long> = cache.getOrPut(sessionId) {
        val file = storeFile(sessionId)
        val map = mutableMapOf<String, Long>()
        if (file.exists()) {
            file.readLines().forEach { line ->
                val parts = line.split("|", limit = 2)
                val relpath = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@forEach
                // Pre-0.2.6 lines are a bare relpath with no recorded fingerprint. Unlike
                // ClearedSessions, there's no safe "dismissed forever" fallback here — an unmatchable
                // fingerprint just means the file falls through to fresh live-state on next look,
                // which self-heals immediately (summaryFor re-dismisses it if still resolved).
                val fingerprint = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
                map[relpath] = fingerprint
            }
        }
        map
    }

    fun isDismissed(sessionId: String, relpath: String, currentFingerprint: Long): Boolean =
        fingerprintsFor(sessionId)[relpath] == currentFingerprint

    fun dismiss(sessionId: String, relpath: String, fingerprint: Long) {
        fingerprintsFor(sessionId)[relpath] = fingerprint
        val file = storeFile(sessionId)
        file.parentFile.mkdirs()
        file.appendText("$relpath|$fingerprint\n")
    }
}
