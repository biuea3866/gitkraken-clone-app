package dev.undine.presentation.diff

/** 화면 테스트가 뷰어의 각 부분을 집는 태그. 구조를 바꾸면 이 값도 함께 바뀐다. */
object DiffTags {
    const val ROOT = "diff.root"
    const val LINES = "diff.lines"
    const val LINE = "diff.line"
    const val NOTICE = "diff.notice"
    const val OLD_LINE_NUMBER = "diff.lineNumber.old"
    const val NEW_LINE_NUMBER = "diff.lineNumber.new"

    /** [key] 는 [DiffRow.key] — 목록 안에서 안정적인 행 인덱스다. */
    fun row(key: Int): String = "diff.row.$key"

    fun hunkHeader(hunkIndex: Int): String = "diff.hunk.$hunkIndex"

    fun stageHunk(hunkIndex: Int): String = "diff.hunk.$hunkIndex.stage"
}
