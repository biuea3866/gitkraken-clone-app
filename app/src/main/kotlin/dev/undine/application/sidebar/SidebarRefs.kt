package dev.undine.application.sidebar

import dev.undine.domain.Branch
import dev.undine.domain.StashEntry
import dev.undine.domain.Tag

/**
 * 사이드바 한 화면이 필요로 하는 참조 전체.
 *
 * 브랜치의 `ahead`·`behind` 는 [Branch] 에 이미 실려 온다 — 화면이 행마다 다시 조회하지 않도록
 * 목록 조회 한 번으로 끝내는 것이 이 타입의 존재 이유다.
 */
data class SidebarRefs(
    val branches: List<Branch>,
    val tags: List<Tag>,
    val stashes: List<StashEntry>,
)
