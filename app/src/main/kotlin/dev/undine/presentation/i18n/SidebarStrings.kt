package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "sidebar"

/** `sidebar.*` 키 정의 — 참조 트리가 노출하는 모든 문구. */
@Suppress("TooManyFunctions")
object SidebarKeys {
    val localBranches = StringKey("$NAMESPACE.localBranches")
    val remoteBranches = StringKey("$NAMESPACE.remoteBranches")
    val tags = StringKey("$NAMESPACE.tags")
    val stashes = StringKey("$NAMESPACE.stashes")
    val detachedHead = StringKey("$NAMESPACE.detachedHead")
    val filterLabel = StringKey("$NAMESPACE.filterLabel")
    val emptyBranches = StringKey("$NAMESPACE.emptyBranches")
    val emptyFiltered = StringKey("$NAMESPACE.emptyFiltered")
    val currentBranch = StringKey("$NAMESPACE.currentBranch")
    val untrackedStash = StringKey("$NAMESPACE.untrackedStash")
    val menuOpen = StringKey("$NAMESPACE.menuOpen")
    val menuCheckout = StringKey("$NAMESPACE.menuCheckout")
    val menuRename = StringKey("$NAMESPACE.menuRename")
    val menuDelete = StringKey("$NAMESPACE.menuDelete")
    val menuMerge = StringKey("$NAMESPACE.menuMerge")
    val deleteTitle = StringKey("$NAMESPACE.deleteTitle")
    val deleteMessage = StringKey("$NAMESPACE.deleteMessage")
    val unmergedTitle = StringKey("$NAMESPACE.unmergedTitle")
    val unmergedMessage = StringKey("$NAMESPACE.unmergedMessage")
    val renameTitle = StringKey("$NAMESPACE.renameTitle")
    val renameField = StringKey("$NAMESPACE.renameField")
    val loadFailed = StringKey("$NAMESPACE.loadFailed")
    val actionFailed = StringKey("$NAMESPACE.actionFailed")
    val ahead = StringKey("$NAMESPACE.ahead")
    val behind = StringKey("$NAMESPACE.behind")
}

/**
 * 사이드바 문구 접근자. `strings.sidebar.localBranches` 로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 공용 파일이라 등록 한 줄을 UND-26 이
 * 일괄로 추가한다 (wave 3 결정 A3). 그때까지 테스트는 [sidebarTranslations] 로 카탈로그를 만든다.
 */
@JvmInline
@Suppress("TooManyFunctions")
value class SidebarStrings internal constructor(private val strings: Strings) {
    val localBranches: String get() = strings.text(SidebarKeys.localBranches)
    val remoteBranches: String get() = strings.text(SidebarKeys.remoteBranches)
    val tags: String get() = strings.text(SidebarKeys.tags)
    val stashes: String get() = strings.text(SidebarKeys.stashes)
    val detachedHead: String get() = strings.text(SidebarKeys.detachedHead)
    val filterLabel: String get() = strings.text(SidebarKeys.filterLabel)
    val emptyBranches: String get() = strings.text(SidebarKeys.emptyBranches)
    val currentBranch: String get() = strings.text(SidebarKeys.currentBranch)
    val untrackedStash: String get() = strings.text(SidebarKeys.untrackedStash)
    val menuOpen: String get() = strings.text(SidebarKeys.menuOpen)
    val menuCheckout: String get() = strings.text(SidebarKeys.menuCheckout)
    val menuRename: String get() = strings.text(SidebarKeys.menuRename)
    val menuDelete: String get() = strings.text(SidebarKeys.menuDelete)
    val menuMerge: String get() = strings.text(SidebarKeys.menuMerge)
    val deleteTitle: String get() = strings.text(SidebarKeys.deleteTitle)
    val unmergedTitle: String get() = strings.text(SidebarKeys.unmergedTitle)
    val renameTitle: String get() = strings.text(SidebarKeys.renameTitle)
    val renameField: String get() = strings.text(SidebarKeys.renameField)

    fun emptyFiltered(filter: String): String = strings.text(SidebarKeys.emptyFiltered, filter)

    fun deleteMessage(branch: String): String = strings.text(SidebarKeys.deleteMessage, branch)

    /** 미병합 삭제 경고 — "정말 삭제하시겠습니까" 가 아니라 결과(도달 불가)를 문장으로 알린다. */
    fun unmergedMessage(branch: String): String = strings.text(SidebarKeys.unmergedMessage, branch)

    fun loadFailed(detail: String): String = strings.text(SidebarKeys.loadFailed, detail)

    fun actionFailed(detail: String): String = strings.text(SidebarKeys.actionFailed, detail)

    fun ahead(count: Int): String = strings.text(SidebarKeys.ahead, count)

    fun behind(count: Int): String = strings.text(SidebarKeys.behind, count)
}

/** 사이드바 문구 네임스페이스 진입점. */
val Strings.sidebar: SidebarStrings get() = SidebarStrings(this)

internal val sidebarTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        SidebarKeys.localBranches to "로컬 브랜치",
        SidebarKeys.remoteBranches to "원격 브랜치",
        SidebarKeys.tags to "태그",
        SidebarKeys.stashes to "스태시",
        SidebarKeys.detachedHead to "detached HEAD — 지금 만든 커밋은 어느 브랜치에도 남지 않습니다.",
        SidebarKeys.filterLabel to "브랜치 이름으로 걸러내기",
        SidebarKeys.emptyBranches to "표시할 브랜치가 없습니다",
        SidebarKeys.emptyFiltered to "{0} 와(과) 일치하는 브랜치가 없습니다",
        SidebarKeys.currentBranch to "현재 브랜치",
        SidebarKeys.untrackedStash to "추적되지 않는 파일 포함",
        SidebarKeys.menuOpen to "브랜치 메뉴 열기",
        SidebarKeys.menuCheckout to "체크아웃",
        SidebarKeys.menuRename to "이름 변경",
        SidebarKeys.menuDelete to "삭제",
        SidebarKeys.menuMerge to "병합 대상으로 선택",
        SidebarKeys.deleteTitle to "브랜치를 삭제할까요?",
        SidebarKeys.deleteMessage to "브랜치 {0} 을(를) 삭제합니다. 병합되지 않은 커밋이 있으면 한 번 더 확인합니다.",
        SidebarKeys.unmergedTitle to "병합되지 않은 브랜치입니다",
        SidebarKeys.unmergedMessage to
            "{0} 에만 있는 커밋은 삭제 후 도달 불가 상태가 됩니다 — 어느 브랜치에서도 찾을 수 없게 되고 reflog 로만 복구할 수 있습니다.",
        SidebarKeys.renameTitle to "브랜치 이름 변경",
        SidebarKeys.renameField to "새 브랜치 이름",
        SidebarKeys.loadFailed to "참조 목록을 불러오지 못했습니다: {0}",
        SidebarKeys.actionFailed to "요청을 처리하지 못했습니다: {0}",
        SidebarKeys.ahead to "{0}↑",
        SidebarKeys.behind to "{0}↓",
    ),
    Locale.ENGLISH to mapOf(
        SidebarKeys.localBranches to "Local branches",
        SidebarKeys.remoteBranches to "Remote branches",
        SidebarKeys.tags to "Tags",
        SidebarKeys.stashes to "Stashes",
        SidebarKeys.detachedHead to "Detached HEAD — commits made now stay on no branch.",
        SidebarKeys.filterLabel to "Filter branches by name",
        SidebarKeys.emptyBranches to "No branches to show",
        SidebarKeys.emptyFiltered to "No branches match {0}",
        SidebarKeys.currentBranch to "Current branch",
        SidebarKeys.untrackedStash to "Includes untracked files",
        SidebarKeys.menuOpen to "Open branch menu",
        SidebarKeys.menuCheckout to "Check out",
        SidebarKeys.menuRename to "Rename",
        SidebarKeys.menuDelete to "Delete",
        SidebarKeys.menuMerge to "Select as merge source",
        SidebarKeys.deleteTitle to "Delete this branch?",
        SidebarKeys.deleteMessage to "Deletes branch {0}. Unmerged commits trigger one more confirmation.",
        SidebarKeys.unmergedTitle to "This branch is not merged",
        SidebarKeys.unmergedMessage to
            "Commits that exist only on {0} become unreachable after deletion — " +
            "no branch will find them and only the reflog can recover them.",
        SidebarKeys.renameTitle to "Rename branch",
        SidebarKeys.renameField to "New branch name",
        SidebarKeys.loadFailed to "Could not load references: {0}",
        SidebarKeys.actionFailed to "Could not complete the request: {0}",
        SidebarKeys.ahead to "{0}↑",
        SidebarKeys.behind to "{0}↓",
    ),
)
