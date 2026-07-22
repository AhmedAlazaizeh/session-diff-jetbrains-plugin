package com.ahmedalazaizeh.sessiondiff

enum class ChangeCategory { NEW, DELETED, MODIFIED }

/** Whether the file's hunks have all been reviewed (Keep/Reject) via the inline action bar, and how. */
enum class FileReviewStatus { PENDING, ACCEPTED, HAS_REJECTIONS }

data class FileChangeSummary(
    val relpath: String,
    val category: ChangeCategory,
    val linesAdded: Int,
    val linesDeleted: Int,
    val isBinary: Boolean,
    val reviewStatus: FileReviewStatus,
)
