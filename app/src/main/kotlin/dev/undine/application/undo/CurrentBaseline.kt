package dev.undine.application.undo

import dev.undine.domain.RefGateway
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.undo.RepositoryBaseline

/**
 * 지금의 기준 상태를 읽는다. 체크아웃된 로컬 브랜치가 없으면(= detached HEAD 이거나 커밋이 없는
 * 저장소) 브랜치도 HEAD 도 없는 상태로 돌려준다 — 되돌리기는 그 사실을 근거로 거부한다.
 *
 * 기록과 되돌리기가 **같은 방법으로** 읽어야 비교가 성립하므로 한 곳에 둔다.
 */
internal suspend fun RefGateway.currentBaseline(): RepositoryBaseline {
    val current = listBranches().firstOrNull { it.isCurrent && !it.isRemote }
    return RepositoryBaseline(branch = current?.name, head = current?.target)
}

/**
 * 커밋되지 않은 변경 경로. 추적되지 않는 파일도 포함한다 —
 * [WorkingTreeStatus.isClean] 이 정의하는 "깨끗함" 과 같은 기준을 쓴다.
 */
internal fun WorkingTreeStatus.dirtyPaths(): List<String> =
    (staged.map { it.path } + unstaged.map { it.path } + untracked + conflicted).distinct().sorted()
