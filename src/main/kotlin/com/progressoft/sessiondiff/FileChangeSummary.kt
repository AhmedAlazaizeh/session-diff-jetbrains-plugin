package com.progressoft.sessiondiff

enum class ChangeCategory { NEW, DELETED, MODIFIED }

data class FileChangeSummary(
    val relpath: String,
    val category: ChangeCategory,
    val linesAdded: Int,
    val linesDeleted: Int,
    val isBinary: Boolean,
)
