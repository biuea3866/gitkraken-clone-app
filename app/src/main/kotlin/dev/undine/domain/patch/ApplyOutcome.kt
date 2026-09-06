package dev.undine.domain.patch

/**
 * 패치 적용의 결과. **성공·충돌·미지원·변경 없음을 한 타입**으로 표현한다.
 *
 * 성공이 아닌 것을 예외로 던지지 않는 이유: `dryRun` 과 `apply` 가 **같은 타입**을 돌려줘야
 * "검사는 통과했는데 적용은 실패" 를 타입으로 막을 수 있다. 예외는 **거부**에만 쓴다
 * (경로 안전 위반·복원 보장 불가 → `UndineException.StateViolation`).
 *
 * 어느 변이든 **워킹트리에 부분 적용 상태나 충돌 표식을 남기지 않는다**는 것이 공통 불변식이다.
 */
sealed interface ApplyOutcome {

    /** 적용됐다(또는 dry-run 에서 적용 가능하다). [paths] 는 패치가 건드린 경로다. */
    data class Applied(val paths: List<String>) : ApplyOutcome

    /**
     * 붙지 않았다. [paths] 는 붙지 않은 경로다.
     *
     * 워킹트리는 **그대로**다 — 충돌 표식을 남기지 않는다. 표식을 남겨 충돌 에디터로 넘기는 배선은
     * 이 티켓의 범위가 아니다.
     */
    data class Conflicted(val paths: List<String>) : ApplyOutcome

    /** 지원하지 않는 패치다. 실패가 아니라 **정상 결과**이므로 예외로 던지지 않는다. */
    data class Unsupported(val reason: UnsupportedReason) : ApplyOutcome

    /** 적용할 것이 없다 (빈 패치, 또는 이미 그 상태). */
    data object NoChange : ApplyOutcome
}

/**
 * [ApplyOutcome.Unsupported] 의 사유. **자유 문자열이 아니라 닫힌 목록**이다 —
 * 화면이 사유마다 다르게 안내해야 하는데, 문자열이면 분기할 수 없다.
 */
enum class UnsupportedReason {

    /**
     * 컨텍스트가 어긋나 3-way 적용이 필요한데, JGit 표준 API 에는
     * **조상 blob 기반 3-way 적용 경로가 없다**. 조상 blob 자체는 저장소에 있으므로
     * "붙지 않는 패치" 가 아니라 "우리가 시도할 수단이 없는 패치" 다 —
     * 그래서 [ApplyOutcome.Conflicted] 가 아니라 미지원으로 보고한다.
     */
    THREE_WAY_REQUIRED,

    /**
     * 역방향 적용에 copy·rename·mode 변경·binary 가 섞였다. 역적용은 **순수 hunk 패치만** 지원한다 —
     * 섞인 패치를 단순 경로로 되돌리면 copy 의 source 를 덮어쓴다.
     */
    REVERSE_NOT_PURE_HUNK,
}
