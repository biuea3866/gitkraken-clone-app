package dev.undine.domain.undo

/**
 * 스택에 쌓이는 기록 1건 — **연산·되돌리는 방법·기록 시점의 기준 상태** 셋이 함께 있어야
 * 나중에 안전하게 되돌릴 수 있다. 셋 중 하나라도 빠지면 되돌리기가 추측이 된다.
 */
data class OperationEntry(
    val operation: GitOperationKind,
    val strategy: UndoStrategy,
    val baseline: RepositoryBaseline,
) {

    /** 복구 불가로 기록됐다면 그 사유, 되돌릴 수 있으면 null. */
    val irreversibleReason: String?
        get() = (strategy as? UndoStrategy.Irreversible)?.reason
}

/**
 * 되돌리기 직전 판단 결과. 실행 대상과 거부 사유를 한 타입으로 닫아 두면
 * 호출부가 거부 경로를 조용히 빠뜨릴 수 없다.
 */
sealed interface UndoPlan {

    /** 실행해도 된다. */
    data class Execute(val strategy: UndoStrategy.Reversible) : UndoPlan

    /** 실행하지 않는다. Git 을 전혀 건드리지 않은 상태다. */
    data class Refuse(val outcome: UndoOutcome.Refused) : UndoPlan
}

/**
 * 이 기록을 지금 되돌려도 되는지 판단한다. **Git 을 건드리기 전에** 전부 여기서 거른다.
 *
 * 순서에 이유가 있다.
 * 1. **복구 불가가 먼저다.** 뒤로 밀면 detached·외부 변경 사유가 대신 나가면서
 *    "애초에 되돌릴 수 없는 연산" 이라는 진짜 이유가 사라진다.
 * 2. **브랜치 위에서만 되돌린다.** detached HEAD 에서의 reset·checkout 은 되돌리기라기보다
 *    또 다른 위험한 이동이다.
 * 3. **기준 상태가 같아야 한다.** 그 사이 앱 밖에서 작업했다면 되돌리기가 엉뚱한 결과를 만든다.
 * 4. **워킹트리를 덮어쓰는 되돌리기는 깨끗할 때만.** undo 가 사용자의 미커밋 작업을 삼키면 안 된다.
 *
 * @param dirtyPaths 커밋되지 않은 변경 경로. 비어 있으면 깨끗한 워킹트리다.
 */
fun OperationEntry.planUndo(current: RepositoryBaseline, dirtyPaths: List<String>): UndoPlan =
    when (strategy) {
        is UndoStrategy.Irreversible ->
            UndoPlan.Refuse(UndoOutcome.Irreversible(operation, strategy.reason))

        is UndoStrategy.Reversible -> planReversible(strategy, current, dirtyPaths)
    }

/** 되돌릴 수 있다고 기록된 항목에 남은 상태 조건(2~4)을 순서대로 적용한다. */
private fun OperationEntry.planReversible(
    reversible: UndoStrategy.Reversible,
    current: RepositoryBaseline,
    dirtyPaths: List<String>,
): UndoPlan = when {
    !current.isOnBranch -> UndoPlan.Refuse(UndoOutcome.NoCurrentBranch(operation))

    current != baseline -> UndoPlan.Refuse(UndoOutcome.ExternalChange(baseline, current))

    reversible is UndoStrategy.HardResetTo && dirtyPaths.isNotEmpty() ->
        UndoPlan.Refuse(UndoOutcome.UncommittedChanges(dirtyPaths))

    else -> UndoPlan.Execute(reversible)
}
