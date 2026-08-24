package dev.undine.presentation.welcome

import dev.undine.domain.RepositoryPath

/** 화면 테스트가 Welcome 의 각 요소를 집는 태그. 요소 구조를 바꾸면 이 값도 함께 바뀐다. */
object WelcomeTags {
    const val ROOT = "welcome.root"
    const val RECENT_LIST = "welcome.recent.list"
    const val RECENT_EMPTY = "welcome.recent.empty"
    const val OPEN_LOCAL = "welcome.open.local"
    const val CLONE_URL = "welcome.clone.url"
    const val CLONE_TARGET = "welcome.clone.target"
    const val CLONE_START = "welcome.clone.start"
    const val CLONE_CANCEL = "welcome.clone.cancel"
    const val CLONE_PROGRESS = "welcome.clone.progress"
    const val NOTICE = "welcome.notice"

    /** 최근 목록의 한 행. 경로가 목록 안에서 유일하므로 안정 키로 쓸 수 있다. */
    fun recentRow(path: RepositoryPath): String = "welcome.recent.row:${path.value}"

    fun recentRemove(path: RepositoryPath): String = "welcome.recent.remove:${path.value}"
}
