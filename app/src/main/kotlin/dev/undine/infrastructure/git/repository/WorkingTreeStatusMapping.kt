package dev.undine.infrastructure.git.repository

import dev.undine.domain.ChangeType
import dev.undine.domain.FileChange
import dev.undine.domain.WorkingTreeStatus
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository

/**
 * JGit `Status` 를 도메인 [WorkingTreeStatus] 로 옮긴다.
 *
 * **[FileChange.addedLines]·[FileChange.deletedLines] 는 0, [FileChange.isBinary] 는 false,
 * [FileChange.previousPath] 는 null 로 둔다.** 줄 수와 이진 판정은 파일마다 diff 를 계산해야 하고,
 * 상태 조회는 화면이 자주 호출하므로 여기서 전체 diff 를 돌면 느려진다 — **UND-05 소관**이다.
 * 같은 이유로 rename·copy 탐지를 하지 않아 [ChangeType.RENAMED]·[ChangeType.COPIED] 는 나오지 않는다.
 *
 * 목록은 경로 오름차순으로 정렬한다 — JGit 이 주는 `Set` 순서는 보장되지 않아 화면이 흔들린다.
 */
internal fun Repository.toWorkingTreeStatus(): WorkingTreeStatus {
    // Git(repository) 는 저장소를 소유하지 않으므로 use {} 가 장수명 핸들을 닫지 않는다.
    val status = Git(this).use { git -> git.status().call() }
    return WorkingTreeStatus(
        staged = changesOf(
            status.added to ChangeType.ADDED,
            status.changed to ChangeType.MODIFIED,
            status.removed to ChangeType.DELETED,
        ),
        unstaged = changesOf(
            status.modified to ChangeType.MODIFIED,
            status.missing to ChangeType.DELETED,
        ),
        untracked = status.untracked.sorted(),
        conflicted = status.conflicting.sorted(),
    )
}

private fun changesOf(vararg groups: Pair<Set<String>, ChangeType>): List<FileChange> =
    groups.flatMap { (paths, changeType) -> paths.map { path -> fileChangeOf(path, changeType) } }
        .sortedBy { it.path }

private fun fileChangeOf(path: String, changeType: ChangeType): FileChange =
    FileChange(
        path = path,
        previousPath = null,
        changeType = changeType,
        addedLines = 0,
        deletedLines = 0,
        isBinary = false,
    )
