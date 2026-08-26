package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `submoduleworktree.*` 네임스페이스 — 서브모듈 상태 표시와 worktree 관리 패널의 문구.
 *
 * 두 패널을 한 네임스페이스로 두는 것은 소유 티켓이 하나이기 때문이다 — 나뉘어야 할 만큼 커지면
 * 그때 UND-45 가 자기 파일을 쪼갠다.
 *
 * **아직 비어 있다.** UND-63 이 [builtInTranslations] 등록까지만 해 두고, 키 정의 object·접근자
 * value class·로케일별 번역은 UND-45(Submodule · Worktree 패널)가 **이 파일 안에서만** 채운다.
 *
 * 채우는 모양은 [CommonStrings] 가 정본이다: [SUBMODULE_WORKTREE_NAMESPACE] 로 키를 만들고,
 * 번역 맵을 로케일별로 채우고, `Strings.submoduleWorktree` 확장 프로퍼티로 노출한다.
 */
internal const val SUBMODULE_WORKTREE_NAMESPACE: String = "submoduleworktree"

/** 서브모듈·worktree 화면이 추가할 `submoduleworktree.*` 키의 자리. */
object SubmoduleWorktreeKeys {
    val submodulesTitle = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.submodules.title")
    val submodulesEmpty = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.submodules.empty")
    val worktreesTitle = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.worktrees.title")
    val worktreesEmpty = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.worktrees.empty")
    val initialize = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.action.initialize")
    val updateFromParent = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.action.updateFromParent")
    val open = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.action.open")
    val commitToParent = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.action.commitToParent")
    val remove = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.action.remove")
    val prune = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.action.prune")
    val add = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.action.add")
    val addPathLabel = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.field.addPath")
    val addBranchLabel = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.field.addBranch")
    val initialized = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.status.initialized")
    val locallyModified = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.status.locallyModified")
    val diverged = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.status.diverged")
    val modifiedAndDiverged = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.status.modifiedAndDiverged")
    val current = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.status.current")
    val orphaned = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.status.orphaned")
    val dirtyRemovalWarning = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.warning.dirtyRemoval")
    val operationFailed = StringKey("$SUBMODULE_WORKTREE_NAMESPACE.error.operationFailed")
}

/** 서브모듈·worktree 문구 접근자. UND-45 가 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class SubmoduleWorktreeStrings internal constructor(private val strings: Strings) {
    val submodulesTitle: String get() = strings.text(SubmoduleWorktreeKeys.submodulesTitle)
    val submodulesEmpty: String get() = strings.text(SubmoduleWorktreeKeys.submodulesEmpty)
    val worktreesTitle: String get() = strings.text(SubmoduleWorktreeKeys.worktreesTitle)
    val worktreesEmpty: String get() = strings.text(SubmoduleWorktreeKeys.worktreesEmpty)
    val initialize: String get() = strings.text(SubmoduleWorktreeKeys.initialize)
    val updateFromParent: String get() = strings.text(SubmoduleWorktreeKeys.updateFromParent)
    val open: String get() = strings.text(SubmoduleWorktreeKeys.open)
    val commitToParent: String get() = strings.text(SubmoduleWorktreeKeys.commitToParent)
    val remove: String get() = strings.text(SubmoduleWorktreeKeys.remove)
    val prune: String get() = strings.text(SubmoduleWorktreeKeys.prune)
    val add: String get() = strings.text(SubmoduleWorktreeKeys.add)
    val addPathLabel: String get() = strings.text(SubmoduleWorktreeKeys.addPathLabel)
    val addBranchLabel: String get() = strings.text(SubmoduleWorktreeKeys.addBranchLabel)
    val initialized: String get() = strings.text(SubmoduleWorktreeKeys.initialized)
    val locallyModified: String get() = strings.text(SubmoduleWorktreeKeys.locallyModified)
    val diverged: String get() = strings.text(SubmoduleWorktreeKeys.diverged)
    val modifiedAndDiverged: String get() = strings.text(SubmoduleWorktreeKeys.modifiedAndDiverged)
    val current: String get() = strings.text(SubmoduleWorktreeKeys.current)
    val orphaned: String get() = strings.text(SubmoduleWorktreeKeys.orphaned)
    fun dirtyRemovalWarning(pathCount: Int): String = strings.text(SubmoduleWorktreeKeys.dirtyRemovalWarning, pathCount)
    fun operationFailed(detail: String): String = strings.text(SubmoduleWorktreeKeys.operationFailed, detail)
}

/** 서브모듈·worktree 문구 네임스페이스 진입점. */
val Strings.submoduleWorktree: SubmoduleWorktreeStrings get() = SubmoduleWorktreeStrings(this)

internal val submoduleWorktreeTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        SubmoduleWorktreeKeys.submodulesTitle to "서브모듈",
        SubmoduleWorktreeKeys.submodulesEmpty to "서브모듈이 없습니다",
        SubmoduleWorktreeKeys.worktreesTitle to "Worktree",
        SubmoduleWorktreeKeys.worktreesEmpty to "표시할 worktree가 없습니다",
        SubmoduleWorktreeKeys.initialize to "초기화",
        SubmoduleWorktreeKeys.updateFromParent to "부모 기준으로 업데이트",
        SubmoduleWorktreeKeys.open to "열기",
        SubmoduleWorktreeKeys.commitToParent to "현재 상태를 부모에 커밋",
        SubmoduleWorktreeKeys.remove to "제거",
        SubmoduleWorktreeKeys.prune to "Prune",
        SubmoduleWorktreeKeys.add to "worktree 추가",
        SubmoduleWorktreeKeys.addPathLabel to "새 worktree 디렉터리 경로",
        SubmoduleWorktreeKeys.addBranchLabel to "체크아웃할 브랜치",
        SubmoduleWorktreeKeys.initialized to "최신",
        SubmoduleWorktreeKeys.locallyModified to "수정됨",
        SubmoduleWorktreeKeys.diverged to "부모 기록과 어긋남",
        SubmoduleWorktreeKeys.modifiedAndDiverged to "수정됨 · 부모 기록과 어긋남",
        SubmoduleWorktreeKeys.current to "현재 worktree",
        SubmoduleWorktreeKeys.orphaned to "고아 worktree",
        SubmoduleWorktreeKeys.dirtyRemovalWarning to "변경된 파일 {0}개가 있어 제거하지 않았습니다.",
        SubmoduleWorktreeKeys.operationFailed to "요청을 처리하지 못했습니다: {0}",
    ),
    Locale.ENGLISH to mapOf(
        SubmoduleWorktreeKeys.submodulesTitle to "Submodules",
        SubmoduleWorktreeKeys.submodulesEmpty to "No submodules to show",
        SubmoduleWorktreeKeys.worktreesTitle to "Worktrees",
        SubmoduleWorktreeKeys.worktreesEmpty to "No worktrees to show",
        SubmoduleWorktreeKeys.initialize to "Initialize",
        SubmoduleWorktreeKeys.updateFromParent to "Update from parent",
        SubmoduleWorktreeKeys.open to "Open",
        SubmoduleWorktreeKeys.commitToParent to "Commit current state to parent",
        SubmoduleWorktreeKeys.remove to "Remove",
        SubmoduleWorktreeKeys.prune to "Prune",
        SubmoduleWorktreeKeys.add to "Add worktree",
        SubmoduleWorktreeKeys.addPathLabel to "New worktree directory path",
        SubmoduleWorktreeKeys.addBranchLabel to "Branch to check out",
        SubmoduleWorktreeKeys.initialized to "Up to date",
        SubmoduleWorktreeKeys.locallyModified to "Modified",
        SubmoduleWorktreeKeys.diverged to "Diverged from parent",
        SubmoduleWorktreeKeys.modifiedAndDiverged to "Modified and diverged from parent",
        SubmoduleWorktreeKeys.current to "Current worktree",
        SubmoduleWorktreeKeys.orphaned to "Orphaned worktree",
        SubmoduleWorktreeKeys.dirtyRemovalWarning to "Did not remove the worktree because {0} files have changes.",
        SubmoduleWorktreeKeys.operationFailed to "Could not complete the request: {0}",
    ),
)
