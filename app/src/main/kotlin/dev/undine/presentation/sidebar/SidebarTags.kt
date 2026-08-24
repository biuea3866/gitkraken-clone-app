package dev.undine.presentation.sidebar

import dev.undine.domain.Branch
import dev.undine.domain.RefName

/**
 * 화면 테스트가 사이드바의 각 요소를 집는 태그. 요소를 없애거나 옮기면 이 값도 함께 바뀐다
 * (셸의 `ShellTags` 와 같은 방식).
 *
 * 참조별 태그는 **ref 이름**으로 만든다 — 목록 순서가 필터·접힘으로 바뀌어도 같은 참조를 집을 수 있다.
 *
 * 브랜치 태그에는 **ref 종류(local/remote)까지** 담는다. 짧은 이름은 로컬과 원격 사이에서 겹칠 수 있어
 * (로컬 `origin/main` 과 원격 추적 `origin/main`), 이름만으로는 테스트가 다른 행을 집을 수 있다.
 */
object SidebarTags {
    const val LIST = "sidebar.list"
    const val FILTER = "sidebar.filter"
    const val EMPTY = "sidebar.empty"
    const val ERROR = "sidebar.error"
    const val ACTION_ERROR = "sidebar.actionError"
    const val DETACHED = "sidebar.detached"

    const val MENU_CHECKOUT = "sidebar.menu.checkout"
    const val MENU_RENAME = "sidebar.menu.rename"
    const val MENU_DELETE = "sidebar.menu.delete"
    const val MENU_MERGE = "sidebar.menu.merge"

    const val CONFIRM_DIALOG = "sidebar.confirm"
    const val CONFIRM_ACCEPT = "sidebar.confirm.accept"
    const val CONFIRM_CANCEL = "sidebar.confirm.cancel"

    const val RENAME_DIALOG = "sidebar.rename"
    const val RENAME_FIELD = "sidebar.rename.field"
    const val RENAME_ACCEPT = "sidebar.rename.accept"
    const val RENAME_CANCEL = "sidebar.rename.cancel"

    fun group(group: SidebarGroup): String = "sidebar.group.${group.name}"

    fun branchRow(branch: Branch): String = "sidebar.branch.${branch.refKey()}"

    fun currentMarker(name: RefName): String = "sidebar.current.${name.value}"

    fun badge(name: RefName): String = "sidebar.badge.${name.value}"

    fun menuButton(branch: Branch): String = "sidebar.menuButton.${branch.refKey()}"
}

/**
 * 로컬·원격을 구분하는 브랜치 식별자. 짧은 이름은 두 종류 사이에서 겹칠 수 있으므로
 * 메뉴 상태·테스트 태그는 이 값을 쓴다.
 */
internal fun Branch.refKey(): String = "${if (isRemote) "remote" else "local"}:${name.value}"
