package dev.undine.application.toolbar

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.Progress
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositorySessionBinding
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStrategy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 지목 받기가 아무것도 하지 않은 사유. 사용자가 다음에 할 일이 다르므로 종류로 가른다
 * (exception-handling 규칙 4).
 */
enum class FastForwardRefusal {
    /** 원격이 로컬 위치에서 이어지지 않는다 — 병합이 필요한데 이 경로는 병합하지 않는다. */
    NOT_FAST_FORWARD,

    /** 추적 원격 참조가 없어 무엇을 받을지 정할 수 없다. */
    NO_UPSTREAM,

    /** 현재 체크아웃된 브랜치 — 포인터만 옮기면 워킹트리가 HEAD 와 어긋난 채 남는다. */
    CURRENT_BRANCH,
}

/** 지목 받기 한 번의 결과. `sealed` 라 새 결과가 생기면 화면 변환이 컴파일 시점에 빠짐을 알린다. */
sealed interface FastForwardOutcome {

    val branch: RefName

    /**
     * 브랜치를 원격 위치로 옮겼다.
     *
     * [undoRecordFailure] 가 null 이 아니면 **이동은 끝났고 실행 이력 항목만 남지 않았다** —
     * 기록 실패를 조작 실패로 승격하면 사용자는 옮겨진 브랜치를 보며 "실패" 안내를 받는다.
     */
    data class FastForwarded(
        override val branch: RefName,
        val to: CommitId,
        val undoRecordFailure: UndineException?,
    ) : FastForwardOutcome

    /** 이미 원격과 같은 위치라 옮길 것이 없었다. 실패가 아니다. */
    data class AlreadyUpToDate(override val branch: RefName) : FastForwardOutcome

    /** 아무것도 옮기지 않았다 — [reason] 이 사용자에게 왜인지 말한다. */
    data class Refused(override val branch: RefName, val reason: FastForwardRefusal) : FastForwardOutcome
}

/**
 * 체크아웃하지 않은 브랜치를 **원격 위치로 빨리 감는다** — fetch 뒤 조건부 갱신 하나가 전부다.
 *
 * `RemoteGateway.pull` 은 원격 단위이고 현재 브랜치에 병합하므로 여기서 쓰지 않는다 (결정 D3).
 * 대신 이미 있는 원시연산을 엮는다: [FetchRemoteUseCase] 로 원격 참조를 갱신하고,
 * [RefGateway.isDescendantOf] 하나로 빨리 감기 가능 여부를 묻고,
 * [RefGateway.moveBranch] 의 조건부 갱신으로 옮긴다.
 *
 * **빨리 감기가 아니면 거부한다.** 체크아웃돼 있지 않은 브랜치는 충돌을 해결할 화면이 없어,
 * 병합을 시작해 놓고 사용자가 손댈 수 없는 상태로 남기는 것이 가장 나쁘다.
 *
 * **체크아웃하지 않는다.** 이 UseCase 가 있는 이유가 그것이므로 편의로라도 끼워 넣지 않는다 (결정 D1).
 *
 * 기대 위치는 화면 스냅샷이 아니라 **fetch 뒤 다시 조회한 값**이다 — 스냅샷을 그대로 쓰면 그 사이
 * 다른 경로가 옮긴 브랜치를 덮어쓴다. 갱신과 기록은 [NonCancellable] 한 단위로 묶어, 옮긴 뒤
 * 떨어진 취소가 되돌릴 방법 없는 이동을 남기지 않게 한다 (결정 A-L2).
 *
 * **네 호출 전체가 시작 시점의 저장소 세션에 고정된다** ([RepositorySessionBinding], 결정 D14).
 * fetch·조회·판정·갱신은 저마다 저장소를 만지므로, 호출 사이에 사용자가 저장소를 A 에서 B 로
 * 바꾸면 뒤쪽 호출이 B 를 조작하면서 결과는 A 의 실행 이력에 남는다 — 그 이력의 되돌리기는
 * 엉뚱한 저장소를 건드린다. UND-80 이 호출 하나에 세운 규칙을 이 시퀀스 전체로 넓혀 쓴다.
 */
class FastForwardBranchUseCase(
    private val fetchRemote: FetchRemoteUseCase,
    private val refGateway: RefGateway,
    private val operationRecorder: OperationRecorder,
    private val sessionBinding: RepositorySessionBinding,
) {

    // 막힌 조건마다 곧바로 돌려주는 편이 중첩된 분기보다 읽기 쉽다 — 각 return 이 한 사유다.
    @Suppress("ReturnCount")
    suspend fun execute(
        branch: Branch,
        remote: String,
        onProgress: (Progress) -> Unit,
    ): FastForwardOutcome {
        // 막힌 조건은 fetch 전에 본다 — 어차피 옮기지 못할 브랜치 때문에 원격을 두드리지 않는다.
        if (branch.isCurrent) return refused(branch.name, FastForwardRefusal.CURRENT_BRANCH)
        val upstream = branch.upstream ?: return refused(branch.name, FastForwardRefusal.NO_UPSTREAM)
        currentCoroutineContext().ensureActive()
        // 세션 고정이 기록 순서 구역보다 바깥이다 — 안쪽에서 잡으면 그 락을 기다리는 동안의
        // 저장소 전환을 그대로 따라가, UND-80 이 고친 "락 대기 뒤 대상 결정" 이 되살아난다.
        return sessionBinding.withStartingSession {
            operationRecorder.recordingChange {
                fetchRemote.execute(remote, onProgress)
                fastForward(branch.name, upstream)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun fastForward(branch: RefName, upstream: RefName): FastForwardOutcome {
        val branches = refGateway.listBranches()
        val localTarget = branches.firstOrNull { !it.isRemote && it.name == branch }?.target
            ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, branch.value)
        val remoteTarget = branches.firstOrNull { it.isRemote && it.name == upstream }?.target
            ?: return refused(branch, FastForwardRefusal.NO_UPSTREAM)
        if (remoteTarget == localTarget) return FastForwardOutcome.AlreadyUpToDate(branch)
        if (!refGateway.isDescendantOf(remoteTarget, localTarget)) {
            return refused(branch, FastForwardRefusal.NOT_FAST_FORWARD)
        }
        return move(branch, from = localTarget, to = remoteTarget)
    }

    private suspend fun move(branch: RefName, from: CommitId, to: CommitId): FastForwardOutcome =
        withContext(NonCancellable) {
            val baseline = refGateway.moveBranch(branch, to = to, expected = from)
            val failure = operationRecorder.recordQuietly(GitOperationKind.BRANCH_FAST_FORWARD) {
                operationRecorder.record(
                    GitOperationKind.BRANCH_FAST_FORWARD,
                    UndoStrategy.MoveBranchTo(branch, previous = from, expected = to),
                    baseline,
                    branch.value,
                )
            }
            FastForwardOutcome.FastForwarded(branch, to, failure)
        }

    private fun refused(branch: RefName, reason: FastForwardRefusal): FastForwardOutcome =
        FastForwardOutcome.Refused(branch, reason)
}
