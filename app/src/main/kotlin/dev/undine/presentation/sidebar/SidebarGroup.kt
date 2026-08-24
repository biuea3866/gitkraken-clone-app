package dev.undine.presentation.sidebar

/**
 * 사이드바 트리의 접을 수 있는 그룹. 선언 순서가 화면에 그려지는 순서다 —
 * 자주 쓰는 로컬 브랜치가 위, 참고용 스태시가 아래다.
 */
enum class SidebarGroup {
    LOCAL_BRANCHES,
    REMOTE_BRANCHES,
    TAGS,
    STASHES,
}
