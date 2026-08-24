package dev.undine.presentation.commitdetail

private const val PREFIX = "commitDetail"

/**
 * 화면 테스트가 상세 패널의 각 자리를 집는 태그. 자리 구성을 바꾸면 이 값도 함께 바뀐다
 * (셸의 `ShellTags` 와 같은 규약).
 */
object CommitDetailTags {
    const val ROOT = "$PREFIX.root"
    const val HASH = "$PREFIX.hash"
    const val AUTHOR = "$PREFIX.author"
    const val AUTHORED_AT = "$PREFIX.authoredAt"
    const val COMMITTER = "$PREFIX.committer"
    const val COMMITTED_AT = "$PREFIX.committedAt"
    const val PARENTS = "$PREFIX.parents"
    const val MESSAGE_SUBJECT = "$PREFIX.message.subject"
    const val MESSAGE_BODY = "$PREFIX.message.body"
    const val MESSAGE_TOGGLE = "$PREFIX.message.toggle"
    const val BASE_PARENT_SELECTOR = "$PREFIX.baseParent"
    const val FILE_LIST = "$PREFIX.files"
    const val FILE_LOADING = "$PREFIX.files.loading"
    const val FILE_EMPTY = "$PREFIX.files.empty"
    const val FILE_FAILED = "$PREFIX.files.failed"

    /** 변경 파일 한 행. 경로는 커밋 안에서 유일하므로 목록 key 와 같은 값을 쓴다. */
    fun fileRow(path: String): String = "$FILE_LIST.row.$path"

    /** 부모 커밋으로 이동하는 링크. [index] 는 부모 순번(0 이 첫 부모)이다. */
    fun parentLink(index: Int): String = "$PARENTS.link.$index"

    /** 기준 부모 선택지. [index] 는 부모 순번(0 이 첫 부모)이다. */
    fun baseParentOption(index: Int): String = "$BASE_PARENT_SELECTOR.option.$index"
}
