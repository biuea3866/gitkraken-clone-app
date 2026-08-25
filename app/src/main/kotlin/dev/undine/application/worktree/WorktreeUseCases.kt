package dev.undine.application.worktree

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.domain.worktree.WorktreeListing

/** worktree 목록과 지원하지 않는 등록을 함께 읽는다. */
class LoadWorktreesUseCase(private val gateway: WorktreeGateway) {
    suspend fun execute(): WorktreeListing = gateway.list()
}

/** 브랜치를 새 디렉터리에 checkout한 worktree를 만든다. */
class AddWorktreeUseCase(private val gateway: WorktreeGateway) {
    suspend fun execute(path: RepositoryPath, branch: RefName): Worktree = gateway.add(path, branch)
}

/** 강제 옵션 없이 worktree 하나를 제거한다. 더티 상태는 Gateway의 실패를 그대로 전달한다. */
class RemoveWorktreeUseCase(private val gateway: WorktreeGateway) {
    suspend fun execute(name: String) {
        gateway.remove(name)
    }
}
