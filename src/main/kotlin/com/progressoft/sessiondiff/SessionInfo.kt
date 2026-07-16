package com.progressoft.sessiondiff

import java.nio.file.Path

data class SessionInfo(
    val sessionId: String,
    val transcriptPath: Path,
    val startTimeMillis: Long,
    val touchedFileCount: Int,
    val title: String,
)
