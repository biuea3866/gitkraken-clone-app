package dev.undine.domain

/** 파일 단위 변경 요약. [previousPath] 는 rename·copy 일 때만 채워진다. */
data class FileChange(
    val path: String,
    val previousPath: String?,
    val changeType: ChangeType,
    val addedLines: Int,
    val deletedLines: Int,
    val isBinary: Boolean,
)
