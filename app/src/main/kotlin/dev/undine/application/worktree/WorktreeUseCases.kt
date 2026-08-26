package dev.undine.application.worktree

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.domain.worktree.WorktreeListing
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * worktree 변경을 되돌릴 수 없다고 기록하는 사유.
 *
 * 앱이 만든 디렉터리를 앱이 되지우는 되돌리기는 사용자가 그 사이 넣은 파일까지 지운다(A-L1).
 * 그래서 되돌리지 않고, 되돌릴 수 없다는 사실을 이름과 함께 남긴다.
 */
private const val ADD_IRREVERSIBLE_REASON: String =
    "새로 만든 worktree 디렉터리는 앱이 자동으로 지우지 않습니다 — 목록에서 제거하세요."

private const val REMOVE_IRREVERSIBLE_REASON: String =
    "제거한 worktree 등록은 앱이 되살리지 않습니다 — 같은 브랜치로 다시 추가하세요."

/** worktree 목록과 지원하지 않는 등록을 함께 읽는다. */
class LoadWorktreesUseCase(private val gateway: WorktreeGateway) {
    suspend fun execute(): WorktreeListing = gateway.list()
}

/**
 * 브랜치를 새 디렉터리에 checkout한 worktree를 만든다. 기록은 성공한 뒤에만 남긴다.
 *
 * 변경과 기록을 [NonCancellable] 한 단위로 묶는다 — 디렉터리는 만들어졌는데 화면 수명 코루틴이
 * 그 사이 취소돼 기록이 사라지면, 되돌리기가 이 연산을 건너뛰고 그 앞의 연산을 되돌린다.
 *
 * 묶기 **전에** 호출자의 취소를 확인한다. 아직 아무것도 만들지 않은 시점의 취소는 존중한다.
 */
class AddWorktreeUseCase(
    private val gateway: WorktreeGateway,
    private val recorder: OperationRecorder,
) {
    suspend fun execute(path: RepositoryPath, branch: RefName): Worktree {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val added = gateway.add(path, branch)
            recorder.recordIrreversible(GitOperationKind.WORKTREE_ADD, ADD_IRREVERSIBLE_REASON)
            added
        }
    }
}

/**
 * 강제 옵션 없이 worktree 하나를 제거한다. 더티 상태는 Gateway의 실패를 그대로 전달한다.
 *
 * 고아(prune) 정리도 이 경로를 쓴다 — 새 Gateway 계약을 만들지 않는다.
 */
class RemoveWorktreeUseCase(
    private val gateway: WorktreeGateway,
    private val recorder: OperationRecorder,
) {
    suspend fun execute(name: String) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            gateway.remove(name)
            recorder.recordIrreversible(GitOperationKind.WORKTREE_REMOVE, REMOVE_IRREVERSIBLE_REASON)
        }
    }
}
