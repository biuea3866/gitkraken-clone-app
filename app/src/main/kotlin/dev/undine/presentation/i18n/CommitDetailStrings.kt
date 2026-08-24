package dev.undine.presentation.i18n

import dev.undine.domain.ChangeType
import java.util.Locale

private const val NAMESPACE = "commitdetail"

/** `commitdetail.*` 키 정의. 커밋 상세 패널이 표시하는 문구만 둔다. */
object CommitDetailKeys {
    val hash = StringKey("$NAMESPACE.hash")
    val copyHash = StringKey("$NAMESPACE.copyHash")
    val person = StringKey("$NAMESPACE.person")
    val timestamp = StringKey("$NAMESPACE.timestamp")
    val author = StringKey("$NAMESPACE.author")
    val authoredAt = StringKey("$NAMESPACE.authoredAt")
    val committer = StringKey("$NAMESPACE.committer")
    val committedAt = StringKey("$NAMESPACE.committedAt")
    val parents = StringKey("$NAMESPACE.parents")
    val noParents = StringKey("$NAMESPACE.noParents")
    val baseParent = StringKey("$NAMESPACE.baseParent")
    val parentOption = StringKey("$NAMESPACE.parentOption")
    val expandMessage = StringKey("$NAMESPACE.expandMessage")
    val collapseMessage = StringKey("$NAMESPACE.collapseMessage")
    val changedFiles = StringKey("$NAMESPACE.changedFiles")
    val loading = StringKey("$NAMESPACE.loading")
    val noChanges = StringKey("$NAMESPACE.noChanges")
    val noChangesDescription = StringKey("$NAMESPACE.noChangesDescription")
    val loadFailed = StringKey("$NAMESPACE.loadFailed")
    val binary = StringKey("$NAMESPACE.binary")
    val lineStats = StringKey("$NAMESPACE.lineStats")
    val renamedFrom = StringKey("$NAMESPACE.renamedFrom")
    val changeAdded = StringKey("$NAMESPACE.change.added")
    val changeModified = StringKey("$NAMESPACE.change.modified")
    val changeDeleted = StringKey("$NAMESPACE.change.deleted")
    val changeRenamed = StringKey("$NAMESPACE.change.renamed")
    val changeCopied = StringKey("$NAMESPACE.change.copied")

    /** 네임스페이스 정합 검증용 전체 목록. 키를 추가하면 여기에도 넣는다. */
    val all: List<StringKey> = listOf(
        hash, copyHash, person, timestamp, author, authoredAt, committer, committedAt,
        parents, noParents, baseParent, parentOption,
        expandMessage, collapseMessage, changedFiles, loading,
        noChanges, noChangesDescription, loadFailed, binary, lineStats, renamedFrom,
        changeAdded, changeModified, changeDeleted, changeRenamed, changeCopied,
    )
}

/**
 * 커밋 상세 문구 접근자. `strings.commitDetail.author` 로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 여러 티켓이 한 줄씩 고치면 충돌하는
 * 공용 파일이라 등록을 UND-26 이 일괄로 한다 (wave 3 결정 A3). 그때까지 화면 테스트는
 * [commitDetailTranslations] 로 카탈로그를 직접 만들어 검증한다.
 */
@JvmInline
value class CommitDetailStrings internal constructor(private val strings: Strings) {
    val hash: String get() = strings.text(CommitDetailKeys.hash)
    val copyHash: String get() = strings.text(CommitDetailKeys.copyHash)
    val author: String get() = strings.text(CommitDetailKeys.author)
    val authoredAt: String get() = strings.text(CommitDetailKeys.authoredAt)
    val committer: String get() = strings.text(CommitDetailKeys.committer)
    val committedAt: String get() = strings.text(CommitDetailKeys.committedAt)
    val parents: String get() = strings.text(CommitDetailKeys.parents)
    val noParents: String get() = strings.text(CommitDetailKeys.noParents)
    val baseParent: String get() = strings.text(CommitDetailKeys.baseParent)
    val expandMessage: String get() = strings.text(CommitDetailKeys.expandMessage)
    val collapseMessage: String get() = strings.text(CommitDetailKeys.collapseMessage)
    val changedFiles: String get() = strings.text(CommitDetailKeys.changedFiles)
    val loading: String get() = strings.text(CommitDetailKeys.loading)
    val noChanges: String get() = strings.text(CommitDetailKeys.noChanges)
    val noChangesDescription: String get() = strings.text(CommitDetailKeys.noChangesDescription)
    val loadFailed: String get() = strings.text(CommitDetailKeys.loadFailed)
    val binary: String get() = strings.text(CommitDetailKeys.binary)

    /** git identity 한 명. 이름과 메일 주소를 잇는 서식은 로케일 리소스가 정한다. */
    fun person(name: String, email: String): String =
        strings.text(CommitDetailKeys.person, name, email)

    /**
     * 절대 시각 한 건. 날짜와 시각을 잇는 순서를 로케일 리소스가 정한다 —
     * 문자열 이어붙이기를 쓰지 않는다.
     */
    fun timestamp(date: String, timeOfDay: String): String =
        strings.text(CommitDetailKeys.timestamp, date, timeOfDay)

    /** 기준 부모 선택 버튼의 이름. [ordinal] 은 사람이 세는 1부터의 번호다. */
    fun parentOption(ordinal: Int): String = strings.text(CommitDetailKeys.parentOption, ordinal)

    /** 파일 한 건의 증감 줄 수. 자릿수 구분은 로케일 숫자 서식이 처리한다. */
    fun lineStats(added: Int, deleted: Int): String =
        strings.text(CommitDetailKeys.lineStats, added, deleted)

    /** rename·copy 된 파일의 이전 경로. */
    fun renamedFrom(previousPath: String): String =
        strings.text(CommitDetailKeys.renamedFrom, previousPath)

    /** 변경 종류 이름. 색만으로 구분하지 않기 위해 글자로도 표시한다. */
    fun changeType(changeType: ChangeType): String = strings.text(
        when (changeType) {
            ChangeType.ADDED -> CommitDetailKeys.changeAdded
            ChangeType.MODIFIED -> CommitDetailKeys.changeModified
            ChangeType.DELETED -> CommitDetailKeys.changeDeleted
            ChangeType.RENAMED -> CommitDetailKeys.changeRenamed
            ChangeType.COPIED -> CommitDetailKeys.changeCopied
        },
    )
}

/** 커밋 상세 문구 네임스페이스 진입점. */
val Strings.commitDetail: CommitDetailStrings get() = CommitDetailStrings(this)

internal val commitDetailTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        CommitDetailKeys.hash to "커밋 해시",
        CommitDetailKeys.copyHash to "커밋 해시 복사",
        CommitDetailKeys.person to "{0} <{1}>",
        CommitDetailKeys.timestamp to "{0} {1}",
        CommitDetailKeys.author to "작성자",
        CommitDetailKeys.authoredAt to "작성 시각",
        CommitDetailKeys.committer to "커미터",
        CommitDetailKeys.committedAt to "커밋 시각",
        CommitDetailKeys.parents to "부모 커밋",
        CommitDetailKeys.noParents to "부모 없음 (최초 커밋)",
        CommitDetailKeys.baseParent to "기준 부모",
        CommitDetailKeys.parentOption to "부모 {0}",
        CommitDetailKeys.expandMessage to "본문 펼치기",
        CommitDetailKeys.collapseMessage to "본문 접기",
        CommitDetailKeys.changedFiles to "변경 파일",
        CommitDetailKeys.loading to "변경 파일을 읽는 중",
        CommitDetailKeys.noChanges to "변경된 파일이 없습니다",
        CommitDetailKeys.noChangesDescription to "이 커밋은 파일을 바꾸지 않았습니다",
        CommitDetailKeys.loadFailed to "변경 파일을 읽지 못했습니다",
        CommitDetailKeys.binary to "바이너리",
        CommitDetailKeys.lineStats to "+{0} −{1}",
        CommitDetailKeys.renamedFrom to "이전 경로 {0}",
        CommitDetailKeys.changeAdded to "추가",
        CommitDetailKeys.changeModified to "수정",
        CommitDetailKeys.changeDeleted to "삭제",
        CommitDetailKeys.changeRenamed to "이름 변경",
        CommitDetailKeys.changeCopied to "복사",
    ),
    Locale.ENGLISH to mapOf(
        CommitDetailKeys.hash to "Commit hash",
        CommitDetailKeys.copyHash to "Copy commit hash",
        CommitDetailKeys.person to "{0} <{1}>",
        CommitDetailKeys.timestamp to "{0} {1}",
        CommitDetailKeys.author to "Author",
        CommitDetailKeys.authoredAt to "Authored",
        CommitDetailKeys.committer to "Committer",
        CommitDetailKeys.committedAt to "Committed",
        CommitDetailKeys.parents to "Parents",
        CommitDetailKeys.noParents to "No parent (initial commit)",
        CommitDetailKeys.baseParent to "Base parent",
        CommitDetailKeys.parentOption to "Parent {0}",
        CommitDetailKeys.expandMessage to "Show full message",
        CommitDetailKeys.collapseMessage to "Hide full message",
        CommitDetailKeys.changedFiles to "Changed files",
        CommitDetailKeys.loading to "Loading changed files",
        CommitDetailKeys.noChanges to "No files changed",
        CommitDetailKeys.noChangesDescription to "This commit does not change any file",
        CommitDetailKeys.loadFailed to "Could not load changed files",
        CommitDetailKeys.binary to "Binary",
        CommitDetailKeys.lineStats to "+{0} −{1}",
        CommitDetailKeys.renamedFrom to "from {0}",
        CommitDetailKeys.changeAdded to "Added",
        CommitDetailKeys.changeModified to "Modified",
        CommitDetailKeys.changeDeleted to "Deleted",
        CommitDetailKeys.changeRenamed to "Renamed",
        CommitDetailKeys.changeCopied to "Copied",
    ),
)
