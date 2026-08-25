package dev.undine.domain.worktree

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath

/**
 * 하나의 저장소를 동시에 체크아웃한 디렉터리 하나.
 *
 * @param name `.git/worktrees/<name>` 의 등록 이름. 메인 worktree 는 등록이 없어 디렉터리 이름을 쓴다.
 * @param branch 그 worktree 의 HEAD 가 가리키는 브랜치. detached HEAD 면 null 이다 —
 *   "브랜치가 없다" 를 빈 문자열로 뭉개지 않는다.
 */
data class Worktree(
    val name: String,
    val path: RepositoryPath,
    val branch: RefName?,
    val state: WorktreeState,
)

/**
 * worktree 가 놓인 상태. 화면이 취할 행동이 셋 다 다르다 —
 * 메인은 제거 버튼을 감추고, 고아는 등록만 남았음을 알리며, 연결은 정상 동작을 모두 연다.
 */
enum class WorktreeState {

    /** 저장소 본체가 있는 worktree. 제거할 수 없다. */
    MAIN,

    /** `.git/worktrees` 에 등록됐고 디렉터리도 있는 정상 worktree. */
    LINKED,

    /** 등록은 남았지만 디렉터리가 사라졌다. 사용자가 디렉터리를 직접 지운 경우가 대표적이다. */
    ORPHANED,
}

/**
 * 읽을 수 없는 worktree 등록.
 *
 * `.git/worktrees/<name>` 의 형식이 이 앱이 아는 현행 git 표준과 다를 때 담는다.
 * **추측하지 않고 미지원으로 보고**하는 자리다 — 조용히 목록에서 빼면 화면이
 * "그런 worktree 는 없다" 로 오해하고, 사용자가 그 등록을 영영 못 본다.
 */
data class UnsupportedWorktreeMetadata(val name: String, val detail: String)

/**
 * worktree 조회 결과.
 *
 * 읽은 것과 읽지 못한 것을 **함께** 준다. 하나가 깨졌다고 나머지를 못 쓰게 만들지 않고,
 * 깨진 것을 성공으로 뭉개지도 않는다 (`exception-handling` 규칙 6·7).
 */
data class WorktreeListing(
    val worktrees: List<Worktree>,
    val unsupported: List<UnsupportedWorktreeMetadata>,
) {

    /** 메인 worktree. 저장소가 열려 있으면 항상 하나 있다. */
    val main: Worktree?
        get() = worktrees.firstOrNull { it.state == WorktreeState.MAIN }
}
