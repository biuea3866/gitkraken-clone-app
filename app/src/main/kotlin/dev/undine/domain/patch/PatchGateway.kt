package dev.undine.domain.patch

/**
 * 패치 생성과 적용. 원격 없이 변경을 주고받는 경로이자 실험적 변경을 보관하는 수단이다.
 *
 * **패치는 신뢰할 수 없는 입력이다.** 경로 이탈·`.git` 변형·절대 경로를 담은 패치는 적용하지 않고
 * `UndineException.StateViolation` 으로 거부한다.
 *
 * **원자성이 이 계약의 핵심이다.** 적용 전 상태를 Git 트리 객체로 기록하고, 격리된 트리 위에서
 * 먼저 적용해 본 뒤 통과한 결과만 워킹트리로 승격한다. 승격이나 그 뒤 검증이 실패하면 기록한
 * 트리로 되돌린다 — 파일 경로 목록을 손으로 스냅샷·복원하지 않는다. 트리는 심볼릭 링크·mode·
 * 디렉터리 구조를 그 자체로 담으므로 열거할 항목이 없다.
 *
 * 되돌릴 수 없는 상태(적용 대상 경로의 추적되지 않은 파일)는 트리에 담기지 않으므로 **적용 전에 거부**한다.
 *
 * 외부 `git` 바이너리를 호출하지 않는다 — 구현은 JGit 만 쓴다.
 */
interface PatchGateway {

    /** 커밋 범위를 패치 바이트로 내보낸다. 파일을 쓰지 않는다. */
    suspend fun export(range: CommitRange): PatchExport

    /** 워킹트리·인덱스 변경을 패치 바이트로 내보낸다. */
    suspend fun export(scope: WorkingTreeScope): PatchExport

    /**
     * 승격만 생략한 격리 적용. **실제 적용과 같은 경로**를 쓰고 같은 [ApplyOutcome] 을 돌려준다 —
     * 검사 전용 구현을 따로 두면 두 경로가 갈라져 "검사는 통과했는데 적용은 실패" 가 생긴다.
     *
     * 워킹트리·인덱스를 바꾸지 않는다.
     *
     * @throws dev.undine.domain.UndineException.StateViolation 경로가 안전하지 않거나 복원을 보장할 수 없을 때
     */
    suspend fun dryRun(patch: ByteArray, mode: ApplyMode): ApplyOutcome

    /**
     * 패치를 적용한다. [dryRun] 과 같은 격리 적용을 거친 뒤 통과한 결과만 승격한다.
     *
     * @throws dev.undine.domain.UndineException.StateViolation 경로가 안전하지 않거나 복원을 보장할 수 없을 때
     */
    suspend fun apply(patch: ByteArray, mode: ApplyMode): ApplyOutcome

    /**
     * 패치를 거꾸로 적용한다. **순수 hunk 패치만** 지원하며 워킹트리에만 반영한다.
     * copy·rename·mode 변경·binary 가 **하나라도** 섞이면 되돌리지 않고
     * [ApplyOutcome.Unsupported] 를 돌려준다.
     *
     * @throws dev.undine.domain.UndineException.StateViolation 경로가 안전하지 않거나 복원을 보장할 수 없을 때
     */
    suspend fun applyReversed(patch: ByteArray): ApplyOutcome
}
