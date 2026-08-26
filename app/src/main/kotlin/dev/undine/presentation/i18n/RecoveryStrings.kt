package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `recovery.*` 네임스페이스 — 잃어버린 커밋 찾기(reflog)와 버그 커밋 찾기(bisect) 화면의 문구.
 *
 * **아직 비어 있다.** UND-63 이 [builtInTranslations] 등록까지만 해 두고, 키 정의 object·접근자
 * value class·로케일별 번역은 UND-46(Reflog · Bisect 화면)이 **이 파일 안에서만** 채운다. 공통 파일
 * (`BuiltInStrings.kt`)은 이미 등록돼 있으므로 건드리지 않는다 — 같은 wave 의 화면 7건이 그 파일을
 * 함께 고치면 머지 충돌이 난다.
 *
 * 채우는 모양은 [CommonStrings] 가 정본이다: [RECOVERY_NAMESPACE] 로 키를 만들고, 번역 맵을
 * 로케일별로 채우고, `Strings.recovery` 확장 프로퍼티로 노출한다.
 *
 * 빈 맵은 병합에서 아무 키도 더하지 않으므로 등록만으로 카탈로그 동작이 달라지지 않는다.
 * 이 네임스페이스의 키를 지금 조회하면 다른 미등록 키와 똑같이 폴백한다.
 */
internal const val RECOVERY_NAMESPACE: String = "recovery"

/** Reflog·bisect 화면의 `recovery.*` 키. */
object RecoveryKeys {
    val title = StringKey("$RECOVERY_NAMESPACE.title")
    val reflog = StringKey("$RECOVERY_NAMESPACE.reflog")
    val reflogLoading = StringKey("$RECOVERY_NAMESPACE.reflogLoading")
    val reflogEmpty = StringKey("$RECOVERY_NAMESPACE.reflogEmpty")
    val reflogExpired = StringKey("$RECOVERY_NAMESPACE.reflogExpired")
    val preview = StringKey("$RECOVERY_NAMESPACE.preview")
    val changedFiles = StringKey("$RECOVERY_NAMESPACE.changedFiles")
    val loadFailed = StringKey("$RECOVERY_NAMESPACE.loadFailed")
    val undoRecordFailed = StringKey("$RECOVERY_NAMESPACE.undoRecordFailed")
    val newBranch = StringKey("$RECOVERY_NAMESPACE.newBranch")
    val moveExisting = StringKey("$RECOVERY_NAMESPACE.moveExisting")
    val moveWarning = StringKey("$RECOVERY_NAMESPACE.moveWarning")
    val moveConfirm = StringKey("$RECOVERY_NAMESPACE.moveConfirm")
    val moveCancel = StringKey("$RECOVERY_NAMESPACE.moveCancel")
    val scanUnreachable = StringKey("$RECOVERY_NAMESPACE.scanUnreachable")
    val scanWarning = StringKey("$RECOVERY_NAMESPACE.scanWarning")
    val scanning = StringKey("$RECOVERY_NAMESPACE.scanning")
    val scanUnsupported = StringKey("$RECOVERY_NAMESPACE.scanUnsupported")
    val bisect = StringKey("$RECOVERY_NAMESPACE.bisect")
    val bisectStart = StringKey("$RECOVERY_NAMESPACE.bisectStart")
    val bisectPickGood = StringKey("$RECOVERY_NAMESPACE.bisectPickGood")
    val bisectPickBad = StringKey("$RECOVERY_NAMESPACE.bisectPickBad")
    val bisectBoundaryGood = StringKey("$RECOVERY_NAMESPACE.bisectBoundaryGood")
    val bisectBoundaryBad = StringKey("$RECOVERY_NAMESPACE.bisectBoundaryBad")
    val bisectBoundaryMissing = StringKey("$RECOVERY_NAMESPACE.bisectBoundaryMissing")
    val summaryUnknownCounts = StringKey("$RECOVERY_NAMESPACE.summaryUnknownCounts")
    val currentTarget = StringKey("$RECOVERY_NAMESPACE.currentTarget")
    val remainingCandidates = StringKey("$RECOVERY_NAMESPACE.remainingCandidates")
    val remainingChecks = StringKey("$RECOVERY_NAMESPACE.remainingChecks")
    val markGood = StringKey("$RECOVERY_NAMESPACE.markGood")
    val markBad = StringKey("$RECOVERY_NAMESPACE.markBad")
    val skip = StringKey("$RECOVERY_NAMESPACE.skip")
    val reset = StringKey("$RECOVERY_NAMESPACE.reset")
    val firstBad = StringKey("$RECOVERY_NAMESPACE.firstBad")
    val inconclusive = StringKey("$RECOVERY_NAMESPACE.inconclusive")
    val inconclusiveReason = StringKey("$RECOVERY_NAMESPACE.inconclusiveReason")
    val historyGood = StringKey("$RECOVERY_NAMESPACE.historyGood")
    val historyCurrentBad = StringKey("$RECOVERY_NAMESPACE.historyCurrentBad")
    val historySkipped = StringKey("$RECOVERY_NAMESPACE.historySkipped")
    val historyNotChronological = StringKey("$RECOVERY_NAMESPACE.historyNotChronological")
}

/** Reflog·bisect 문구 접근자. UND-46 이 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class RecoveryStrings internal constructor(private val strings: Strings) {
    val title: String get() = strings.text(RecoveryKeys.title)
    val reflog: String get() = strings.text(RecoveryKeys.reflog)
    val reflogLoading: String get() = strings.text(RecoveryKeys.reflogLoading)
    val reflogEmpty: String get() = strings.text(RecoveryKeys.reflogEmpty)
    val reflogExpired: String get() = strings.text(RecoveryKeys.reflogExpired)
    val preview: String get() = strings.text(RecoveryKeys.preview)
    val changedFiles: String get() = strings.text(RecoveryKeys.changedFiles)
    val loadFailed: String get() = strings.text(RecoveryKeys.loadFailed)
    val undoRecordFailed: String get() = strings.text(RecoveryKeys.undoRecordFailed)
    val newBranch: String get() = strings.text(RecoveryKeys.newBranch)
    val moveExisting: String get() = strings.text(RecoveryKeys.moveExisting)
    val moveWarning: String get() = strings.text(RecoveryKeys.moveWarning)
    val moveConfirm: String get() = strings.text(RecoveryKeys.moveConfirm)
    val moveCancel: String get() = strings.text(RecoveryKeys.moveCancel)
    val scanUnreachable: String get() = strings.text(RecoveryKeys.scanUnreachable)
    val scanWarning: String get() = strings.text(RecoveryKeys.scanWarning)
    val scanning: String get() = strings.text(RecoveryKeys.scanning)
    val scanUnsupported: String get() = strings.text(RecoveryKeys.scanUnsupported)
    val bisect: String get() = strings.text(RecoveryKeys.bisect)
    val bisectStart: String get() = strings.text(RecoveryKeys.bisectStart)
    val bisectPickGood: String get() = strings.text(RecoveryKeys.bisectPickGood)
    val bisectPickBad: String get() = strings.text(RecoveryKeys.bisectPickBad)
    fun bisectBoundaryGood(commit: String): String = strings.text(RecoveryKeys.bisectBoundaryGood, commit)
    fun bisectBoundaryBad(commit: String): String = strings.text(RecoveryKeys.bisectBoundaryBad, commit)
    val bisectBoundaryMissing: String get() = strings.text(RecoveryKeys.bisectBoundaryMissing)
    val summaryUnknownCounts: String get() = strings.text(RecoveryKeys.summaryUnknownCounts)
    fun currentTarget(commit: String): String = strings.text(RecoveryKeys.currentTarget, commit)
    fun remainingCandidates(count: Int): String = strings.text(RecoveryKeys.remainingCandidates, count)
    fun remainingChecks(count: Int): String = strings.text(RecoveryKeys.remainingChecks, count)
    val markGood: String get() = strings.text(RecoveryKeys.markGood)
    val markBad: String get() = strings.text(RecoveryKeys.markBad)
    val skip: String get() = strings.text(RecoveryKeys.skip)
    val reset: String get() = strings.text(RecoveryKeys.reset)
    fun firstBad(commit: String): String = strings.text(RecoveryKeys.firstBad, commit)
    val inconclusive: String get() = strings.text(RecoveryKeys.inconclusive)
    val inconclusiveReason: String get() = strings.text(RecoveryKeys.inconclusiveReason)
    fun historyGood(commits: String): String = strings.text(RecoveryKeys.historyGood, commits)
    fun historyCurrentBad(commit: String): String = strings.text(RecoveryKeys.historyCurrentBad, commit)
    fun historySkipped(commits: String): String = strings.text(RecoveryKeys.historySkipped, commits)
    val historyNotChronological: String get() = strings.text(RecoveryKeys.historyNotChronological)
}

/** Reflog·bisect 문구 네임스페이스 진입점. */
val Strings.recovery: RecoveryStrings get() = RecoveryStrings(this)

internal val recoveryTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        RecoveryKeys.title to "복구",
        RecoveryKeys.reflog to "Reflog",
        RecoveryKeys.reflogLoading to "Reflog를 읽는 중…",
        RecoveryKeys.reflogEmpty to "표시할 reflog 항목이 없습니다.",
        RecoveryKeys.reflogExpired to "Reflog가 만료되었을 수 있습니다.",
        RecoveryKeys.preview to "커밋 미리보기",
        RecoveryKeys.changedFiles to "변경 파일",
        RecoveryKeys.loadFailed to "복구 정보를 읽지 못했습니다.",
        RecoveryKeys.undoRecordFailed to
            "변경은 적용됐지만 되돌리기(Undo) 기록에 실패했습니다. 이 변경은 Undo 목록에 남아 있지 않습니다.",
        RecoveryKeys.newBranch to "새 브랜치로 복구",
        RecoveryKeys.moveExisting to "기존 ref 이동",
        RecoveryKeys.moveWarning to "기존 ref를 이동하면 현재 가리키는 커밋을 잃을 수 있습니다.",
        RecoveryKeys.moveConfirm to "위험을 확인했고 이동합니다",
        RecoveryKeys.moveCancel to "이동 취소",
        RecoveryKeys.scanUnreachable to "도달 불가 커밋 탐색",
        RecoveryKeys.scanWarning to "전체 객체를 탐색하므로 오래 걸릴 수 있습니다.",
        RecoveryKeys.scanning to "도달 불가 커밋을 탐색하는 중…",
        RecoveryKeys.scanUnsupported to "이 저장소에서는 도달 불가 커밋 탐색을 지원하지 않습니다.",
        RecoveryKeys.bisect to "Bisect",
        RecoveryKeys.bisectStart to "Bisect 시작",
        RecoveryKeys.bisectPickGood to "선택 커밋을 good 경계로",
        RecoveryKeys.bisectPickBad to "선택 커밋을 bad 경계로",
        RecoveryKeys.bisectBoundaryGood to "good 경계: {0}",
        RecoveryKeys.bisectBoundaryBad to "bad 경계: {0}",
        RecoveryKeys.bisectBoundaryMissing to "reflog 항목을 골라 good·bad 경계를 지정하면 시작할 수 있습니다.",
        RecoveryKeys.summaryUnknownCounts to "복원한 세션이라 남은 후보 수는 다음 판정에서 계산됩니다.",
        RecoveryKeys.currentTarget to "현재 검사 대상: {0}",
        RecoveryKeys.remainingCandidates to "남은 후보: {0}개",
        RecoveryKeys.remainingChecks to "예상 남은 검사: {0}회",
        RecoveryKeys.markGood to "좋음",
        RecoveryKeys.markBad to "나쁨",
        RecoveryKeys.skip to "건너뛰기",
        RecoveryKeys.reset to "Bisect 초기화",
        RecoveryKeys.firstBad to "원인 커밋: {0}",
        RecoveryKeys.inconclusive to "원인 커밋을 하나로 확정할 수 없습니다.",
        RecoveryKeys.inconclusiveReason to "건너뛴 커밋 때문에 아래 후보 모두가 가능성으로 남아 있습니다.",
        RecoveryKeys.historyGood to "good: {0}",
        RecoveryKeys.historyCurrentBad to "현재 bad: {0}",
        RecoveryKeys.historySkipped to "skipped: {0}",
        RecoveryKeys.historyNotChronological to "판정 이력은 저장된 good·현재 bad·skipped 상태이며 시간순 로그가 아닙니다.",
    ),
    Locale.ENGLISH to mapOf(
        RecoveryKeys.title to "Recovery",
        RecoveryKeys.reflog to "Reflog",
        RecoveryKeys.reflogLoading to "Loading reflog…",
        RecoveryKeys.reflogEmpty to "There are no reflog entries to show.",
        RecoveryKeys.reflogExpired to "The reflog may have expired.",
        RecoveryKeys.preview to "Commit preview",
        RecoveryKeys.changedFiles to "Changed files",
        RecoveryKeys.loadFailed to "Could not load recovery information.",
        RecoveryKeys.undoRecordFailed to
            "The change was applied, but recording it for undo failed. It is not in the undo list.",
        RecoveryKeys.newBranch to "Recover to new branch",
        RecoveryKeys.moveExisting to "Move existing ref",
        RecoveryKeys.moveWarning to "Moving an existing ref can displace its current commit.",
        RecoveryKeys.moveConfirm to "I understand the risk; move it",
        RecoveryKeys.moveCancel to "Cancel move",
        RecoveryKeys.scanUnreachable to "Scan unreachable commits",
        RecoveryKeys.scanWarning to "This can take a while because it scans all objects.",
        RecoveryKeys.scanning to "Scanning unreachable commits…",
        RecoveryKeys.scanUnsupported to "This repository does not support scanning unreachable commits.",
        RecoveryKeys.bisect to "Bisect",
        RecoveryKeys.bisectStart to "Start bisect",
        RecoveryKeys.bisectPickGood to "Use selected commit as good",
        RecoveryKeys.bisectPickBad to "Use selected commit as bad",
        RecoveryKeys.bisectBoundaryGood to "good boundary: {0}",
        RecoveryKeys.bisectBoundaryBad to "bad boundary: {0}",
        RecoveryKeys.bisectBoundaryMissing to
            "Pick reflog entries as the good and bad boundaries to start.",
        RecoveryKeys.summaryUnknownCounts to
            "This session was restored, so remaining counts are computed on the next verdict.",
        RecoveryKeys.currentTarget to "Current target: {0}",
        RecoveryKeys.remainingCandidates to "Remaining candidates: {0}",
        RecoveryKeys.remainingChecks to "Estimated checks remaining: {0}",
        RecoveryKeys.markGood to "Good",
        RecoveryKeys.markBad to "Bad",
        RecoveryKeys.skip to "Skip",
        RecoveryKeys.reset to "Reset bisect",
        RecoveryKeys.firstBad to "First bad commit: {0}",
        RecoveryKeys.inconclusive to "A single culprit cannot be determined.",
        RecoveryKeys.inconclusiveReason to "Skipped commits leave every candidate below unresolved.",
        RecoveryKeys.historyGood to "good: {0}",
        RecoveryKeys.historyCurrentBad to "current bad: {0}",
        RecoveryKeys.historySkipped to "skipped: {0}",
        RecoveryKeys.historyNotChronological to
            "History shows stored good, current bad, and skipped states; it is not a chronological event log.",
    ),
)
