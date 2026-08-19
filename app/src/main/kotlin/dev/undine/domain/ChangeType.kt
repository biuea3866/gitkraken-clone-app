package dev.undine.domain

/** 파일 변경 종류. */
enum class ChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
}
