package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "staging"

/** `staging.*` 키 정의. */
object StagingKeys {
    val stagedTitle = StringKey("$NAMESPACE.stagedTitle")
    val unstagedTitle = StringKey("$NAMESPACE.unstagedTitle")
    val stage = StringKey("$NAMESPACE.stage")
    val unstage = StringKey("$NAMESPACE.unstage")
    val stageAll = StringKey("$NAMESPACE.stageAll")
    val unstageAll = StringKey("$NAMESPACE.unstageAll")
    val messagePlaceholder = StringKey("$NAMESPACE.messagePlaceholder")
    val commit = StringKey("$NAMESPACE.commit")
    val amend = StringKey("$NAMESPACE.amend")
    val cleanTitle = StringKey("$NAMESPACE.cleanTitle")
    val cleanDescription = StringKey("$NAMESPACE.cleanDescription")

    /** 커밋 버튼이 비활성인 사유. 숨기면 사용자는 왜 눌리지 않는지 알 수 없다. */
    val blockedNothingStaged = StringKey("$NAMESPACE.blocked.nothingStaged")
    val blockedEmptyMessage = StringKey("$NAMESPACE.blocked.emptyMessage")
    val blockedAuthorMissing = StringKey("$NAMESPACE.blocked.authorMissing")

    /** amend 확인 대화. 대상 커밋을 보여 준 뒤에만 실행한다. */
    val amendConfirmTitle = StringKey("$NAMESPACE.amendConfirm.title")
    val amendConfirmTarget = StringKey("$NAMESPACE.amendConfirm.target")
    val amendConfirmAccept = StringKey("$NAMESPACE.amendConfirm.accept")

    /** 길이 가이드. 강제하지 않고 표시만 한다 — 규칙을 어길 정당한 이유가 있는 커밋이 있다. */
    val subjectLengthGuide = StringKey("$NAMESPACE.guide.subjectLength")
    val bodyWrapGuide = StringKey("$NAMESPACE.guide.bodyWrap")
}

/** 스테이징 패널 문구 접근자. `strings.staging.commit` 로 읽는다. */
@JvmInline
value class StagingStrings internal constructor(private val strings: Strings) {
    val stagedTitle: String get() = strings.text(StagingKeys.stagedTitle)
    val unstagedTitle: String get() = strings.text(StagingKeys.unstagedTitle)
    val stage: String get() = strings.text(StagingKeys.stage)
    val unstage: String get() = strings.text(StagingKeys.unstage)
    val stageAll: String get() = strings.text(StagingKeys.stageAll)
    val unstageAll: String get() = strings.text(StagingKeys.unstageAll)
    val messagePlaceholder: String get() = strings.text(StagingKeys.messagePlaceholder)
    val commit: String get() = strings.text(StagingKeys.commit)
    val amend: String get() = strings.text(StagingKeys.amend)
    val cleanTitle: String get() = strings.text(StagingKeys.cleanTitle)
    val cleanDescription: String get() = strings.text(StagingKeys.cleanDescription)
    val amendConfirmTitle: String get() = strings.text(StagingKeys.amendConfirmTitle)
    val amendConfirmAccept: String get() = strings.text(StagingKeys.amendConfirmAccept)

    fun amendConfirmTarget(shortHash: String): String =
        strings.text(StagingKeys.amendConfirmTarget, shortHash)

    fun subjectLengthGuide(length: Int, limit: Int): String =
        strings.text(StagingKeys.subjectLengthGuide, length.toString(), limit.toString())

    fun bodyWrapGuide(limit: Int): String = strings.text(StagingKeys.bodyWrapGuide, limit.toString())

    /** 커밋을 막는 사유 문장. 사유가 없으면 `null` 이고 버튼이 활성이다. */
    fun blocked(reason: CommitBlockedReason): String = strings.text(
        when (reason) {
            CommitBlockedReason.NOTHING_STAGED -> StagingKeys.blockedNothingStaged
            CommitBlockedReason.EMPTY_MESSAGE -> StagingKeys.blockedEmptyMessage
            CommitBlockedReason.AUTHOR_MISSING -> StagingKeys.blockedAuthorMissing
        },
    )
}

/** 커밋 버튼이 비활성인 사유. 화면이 문장으로 표시한다. */
enum class CommitBlockedReason {
    NOTHING_STAGED,
    EMPTY_MESSAGE,
    AUTHOR_MISSING,
}

/** 스테이징 문구 네임스페이스 진입점. */
val Strings.staging: StagingStrings get() = StagingStrings(this)

internal val stagingTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        StagingKeys.stagedTitle to "스테이징된 변경",
        StagingKeys.unstagedTitle to "스테이징되지 않은 변경",
        StagingKeys.stage to "올리기",
        StagingKeys.unstage to "내리기",
        StagingKeys.stageAll to "전부 올리기",
        StagingKeys.unstageAll to "전부 내리기",
        StagingKeys.messagePlaceholder to "커밋 메시지",
        StagingKeys.commit to "커밋",
        StagingKeys.amend to "직전 커밋 고치기",
        StagingKeys.cleanTitle to "변경이 없습니다",
        StagingKeys.cleanDescription to "워킹트리가 깨끗합니다.",
        StagingKeys.blockedNothingStaged to "스테이징된 변경이 없습니다",
        StagingKeys.blockedEmptyMessage to "커밋 메시지를 입력하세요",
        StagingKeys.blockedAuthorMissing to
            "Git 작성자 정보를 설정하세요 — git config user.name \"이름\" · git config user.email \"메일\"",
        StagingKeys.amendConfirmTitle to "직전 커밋이 이미 원격에 있습니다",
        StagingKeys.amendConfirmTarget to "고쳐 쓸 대상: {0}",
        StagingKeys.amendConfirmAccept to "고쳐 쓰기",
        StagingKeys.subjectLengthGuide to "제목 {0}/{1}자",
        StagingKeys.bodyWrapGuide to "본문은 {0}자에서 줄바꿈을 권합니다",
    ),
    Locale.ENGLISH to mapOf(
        StagingKeys.stagedTitle to "Staged changes",
        StagingKeys.unstagedTitle to "Unstaged changes",
        StagingKeys.stage to "Stage",
        StagingKeys.unstage to "Unstage",
        StagingKeys.stageAll to "Stage all",
        StagingKeys.unstageAll to "Unstage all",
        StagingKeys.messagePlaceholder to "Commit message",
        StagingKeys.commit to "Commit",
        StagingKeys.amend to "Amend last commit",
        StagingKeys.cleanTitle to "No changes",
        StagingKeys.cleanDescription to "The working tree is clean.",
        StagingKeys.blockedNothingStaged to "Nothing is staged",
        StagingKeys.blockedEmptyMessage to "Enter a commit message",
        StagingKeys.blockedAuthorMissing to
            "Configure your Git author — git config user.name \"name\" · git config user.email \"mail\"",
        StagingKeys.amendConfirmTitle to "The last commit already exists on the remote",
        StagingKeys.amendConfirmTarget to "Rewriting: {0}",
        StagingKeys.amendConfirmAccept to "Rewrite",
        StagingKeys.subjectLengthGuide to "Subject {0}/{1}",
        StagingKeys.bodyWrapGuide to "Wrap the body at {0} characters",
    ),
)
