package dev.undine.domain

/**
 * 원자 조작을 수행할 브랜치.
 *
 * "현재 브랜치" 를 호출부가 미리 읽어 이름으로 넘기면, 읽은 뒤 실행되기 전에 체크아웃이 바뀌었을 때
 * 사용자가 지목하지 않은 브랜치에서 실행된다. 그래서 **대상 판정을 실행 시점으로 미룬다.**
 */
sealed interface BranchTarget {

    /** 실행 시점에 체크아웃돼 있는 브랜치. detached HEAD 면 대상이 없어 거부된다. */
    data object Current : BranchTarget

    /** 이름으로 지목한 브랜치. */
    data class Named(val branch: RefName) : BranchTarget
}

/**
 * 대상 브랜치 위에서 실행할 조작.
 *
 * 병합·리베이스·cherry-pick 의 상세 결과가 필요한 단발 호출은 그대로 각 Gateway
 * (`MergeGateway` · `CherryPickGateway`)를 쓴다. 여기 있는 것은 **"다른 브랜치를 대상으로"**
 * 실행할 때 필요한 부분집합이다.
 */
sealed interface BranchOperation {

    /** [source] 를 대상 브랜치로 병합한다. */
    data class Merge(val source: RefName, val allowFastForward: Boolean) : BranchOperation

    /** 대상 브랜치를 [upstream] 위로 재배치한다. */
    data class Rebase(val upstream: RefName) : BranchOperation

    /** [commit] 을 대상 브랜치에 적용한다. */
    data class CherryPick(val commit: CommitId, val recordOrigin: Boolean) : BranchOperation
}

/**
 * 원자 조작의 결과. [performedOn] 은 **실제로 조작을 수행한 브랜치**다 —
 * [BranchTarget.Current] 로 요청했을 때 화면이 무엇에 가해졌는지 말할 수 있어야 한다.
 *
 * 세 조작의 결과를 하나로 좁힌다. 빨리 감기 여부처럼 조작마다 다른 세부는 담지 않는다 —
 * 그 세부가 필요한 호출부는 각 조작 전용 Gateway 를 직접 쓴다.
 */
sealed interface BranchOperationResult {

    val performedOn: RefName

    /**
     * [performedOn] 이 **조작 직전에** 가리키던 커밋.
     *
     * 호출자가 스스로 읽으면 그 읽기가 임계 구역 밖이라, 읽은 뒤 조작이 시작되기까지 사이에 앱
     * 내부의 다른 조작이 끼어들 수 있다 — 그 값으로 되돌리면 엉뚱한 커밋으로 간다. 그래서
     * [WorktreeOpsGateway.runOnBranch] 가 자기 임계 구역 안에서 읽어 결과에 담는다.
     *
     * **세 변이가 모두 갖는다** — 호출자가 결과 종류로 분기하지 않고 되돌리기를 구성할 수 있어야 한다.
     */
    val previousTarget: CommitId

    /** 조작이 끝나 대상 브랜치가 [head] 다. */
    data class Succeeded(
        override val performedOn: RefName,
        override val previousTarget: CommitId,
        val head: CommitId,
    ) : BranchOperationResult

    /** 충돌로 멈췄다. **실패가 아니다** — 저장소는 진행 중 상태로 남고 사용자가 이어서 해결한다. */
    data class Conflicted(
        override val performedOn: RefName,
        override val previousTarget: CommitId,
        val paths: List<String>,
    ) : BranchOperationResult

    /** 적용할 변경이 없어 아무것도 바뀌지 않았다. */
    data class NoChange(
        override val performedOn: RefName,
        override val previousTarget: CommitId,
    ) : BranchOperationResult
}

/**
 * 워킹트리를 되돌리는 연산(stash·revert·reset)과, **대상 브랜치에서 원자적으로 실행하는 조작**.
 * [hardReset] 은 워킹트리를 파괴하므로 [ResetMode] 플래그가 아니라 별도 메서드다.
 */
interface WorktreeOpsGateway {

    suspend fun stashPush(includeUntracked: Boolean): StashEntry

    suspend fun stashList(): List<StashEntry>

    suspend fun stashPop()

    suspend fun stashApply(entry: StashEntry)

    suspend fun stashDrop(entry: StashEntry)

    suspend fun revert(commit: CommitId): RevertResult

    suspend fun reset(commit: CommitId, mode: ResetMode)

    suspend fun hardReset(commit: CommitId)

    /**
     * 대상 브랜치 확인 → 체크아웃 → [operation] 실행을 **하나의 임계 구역**에서 끝낸다.
     *
     * 호출자가 체크아웃과 조작을 순서대로 부르는 방식으로는 안전해지지 않는다 — 앱 내부의 다른
     * 체크아웃이 그 사이에 끼어들면 의도하지 않은 브랜치에서 조작이 실행된다. 그래서 시퀀스를
     * 하나의 연산으로 노출하고 잠금을 이 계약의 구현이 소유한다 (결정 A-N1·A-L3·G4).
     * 호출자·화면·UseCase 는 자기 잠금을 갖지 않는다.
     *
     * 실패하면 **호출 전 HEAD 와 대상 브랜치 위치로 되돌리고** 워킹트리에 조작 흔적을 남기지 않는다.
     * 성공하면 HEAD 는 조작을 수행한 브랜치에 남으며, 어느 브랜치였는지는 결과가 말한다.
     *
     * 결과의 [BranchOperationResult.previousTarget] 은 이 임계 구역 안에서, 체크아웃과 조작을
     * 시작하기 전에 읽은 대상 브랜치 위치다. 되돌리기를 구성하는 호출자가 그 값을 스스로 읽지
     * 않게 하려는 것이다. 실패는 결과가 아니라 예외이므로 이 약속의 대상이 아니다.
     *
     * 검사와 실행 사이의 **외부 프로세스** 변경은 방어 대상이 아니다 (결정 A-M1).
     *
     * **호출자는 이 호출과 결과 소비(Undo 기록 등)를 한 `NonCancellable` 단위로 묶는다.** 시작한
     * 조작은 중간에 끊기지 않지만, 조작이 끝난 뒤에 취소가 떨어지면 결과 대신 `CancellationException`
     * 이 도착한다 — 그때 기록을 건너뛰면 저장소는 바뀌었는데 되돌릴 방법이 없다 (결정 A-L2·G4).
     * 화면 수명 코루틴이 부르는 경로일수록 이 규칙이 필요하다.
     *
     * @throws UndineException.StateViolation detached HEAD 에서 [BranchTarget.Current] 를 요청했을 때 ·
     *   이미 병합·리베이스·cherry-pick·revert 가 진행 중일 때
     * @throws UndineException.NotFound 대상 브랜치나 조작 대상 참조·커밋이 없을 때
     * @throws UndineException.DirtyWorkingTree 커밋되지 않은 변경이 있을 때 — 대상이 이미 체크아웃돼
     *   있어도 마찬가지다. 실패 복구가 워킹트리를 되돌리므로 그 편집을 지우지 않으려면 시작하지 않는다.
     */
    suspend fun runOnBranch(on: BranchTarget, operation: BranchOperation): BranchOperationResult

    /**
     * [branch] 가 **[expected] 를 가리킬 때만** [to] 로 옮긴다 (조건부 갱신).
     *
     * 대상이 현재 브랜치인지는 화면 스냅샷이 아니라 **실행 시점의 실제 HEAD** 로 판정한다.
     * 실제 HEAD 가 [branch] 면 워킹트리·인덱스까지 [to] 로 동기화하고(`reset --hard`),
     * 아니면 워킹트리를 건드리지 않고 ref 만 옮긴다. 두 경우 모두 같은 조건부 규칙을 따른다.
     *
     * **커밋하지 않은 편집이 유실되고 되돌릴 수 없다 — 호출 전 사용자 확인이 필요하다.**
     *
     * [runOnBranch] 와 같은 커밋 구간 규칙을 따른다 — 호출자가 이 호출과 기록 소비를 한
     * `NonCancellable` 단위로 묶는다.
     *
     * @throws UndineException.StateViolation 실제 target 이 [expected] 와 다를 때
     * @throws UndineException.NotFound 브랜치나 [to] 커밋이 없을 때
     */
    suspend fun hardResetBranch(branch: RefName, to: CommitId, expected: CommitId)
}
