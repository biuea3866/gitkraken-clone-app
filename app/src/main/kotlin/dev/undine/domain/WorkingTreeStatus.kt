package dev.undine.domain

/**
 * 워킹트리 상태. [isClean] 은 필드가 아니라 네 목록에서 계산되는 파생 프로퍼티다 —
 * 상태를 두 벌로 갖지 않는다.
 */
data class WorkingTreeStatus(
    val staged: List<FileChange>,
    val unstaged: List<FileChange>,
    val untracked: List<String>,
    val conflicted: List<String>,
) {
    val isClean: Boolean
        get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicted.isEmpty()
}
