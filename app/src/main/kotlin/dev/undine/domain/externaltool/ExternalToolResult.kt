package dev.undine.domain.externaltool

/**
 * 외부 도구를 **띄우지 못한** 사유. diff 와 merge 가 똑같이 겪는 실패라 한 곳에 두고
 * 두 결과 타입 모두의 하위 타입으로 삼는다 — 같은 사유를 두 번 정의하면 한쪽만 늘어난다.
 *
 * 이 셋은 사고가 아니라 **예상되는 상태**다. 도구를 쓰지 않는 사용자가 다수이고 설정이 낡는 것도
 * 정상이라 `UndineException` 하위 타입을 새로 만들지 않는다 (wave 7 결정 D2).
 */
sealed interface ExternalToolUnavailable : DiffToolResult, MergeToolResult {

    /**
     * Git 설정에도 앱 설정에도 도구가 없다.
     *
     * **내장 뷰어로 대체할지는 호출자가 판단한다** — Gateway 가 화면을 알면 안 되므로
     * 여기서 대체 동작을 고르지 않고 사실만 돌려준다.
     */
    data object NoToolConfigured : ExternalToolUnavailable

    /**
     * 도구는 정해졌지만 [executable] 이 설치돼 있지 않다. **프로세스를 띄우기 전에** 판정한다 —
     * 실행 실패 메시지는 사용자에게 무엇을 고쳐야 하는지 알려주지 못한다.
     */
    data class ToolNotFound(val executable: String) : ExternalToolUnavailable

    /**
     * `diff.tool`/`merge.tool` 에 이름은 있는데 그 이름의 명령 템플릿을 쓸 수 없다.
     *
     * 이 때 앱 설정으로 **내려가지 않는다** — 사용자가 도구를 지정한 이상 다른 도구를 조용히
     * 실행하는 편이 더 나쁘다. [detail] 은 무엇이 잘못됐는지를 화면이 그대로 보여 준다.
     */
    data class MisconfiguredTool(val toolName: String, val detail: String) : ExternalToolUnavailable
}

/** diff 도구 실행 결과. diff 는 읽기 전용이라 성공과 실패만 가른다. */
sealed interface DiffToolResult {

    /** 도구가 정상 종료했다. */
    data object Completed : DiffToolResult

    /** 도구가 0 이 아닌 [exitCode] 로 끝났다. 조용히 성공으로 뭉개지 않는다. */
    data class ToolFailed(val exitCode: Int) : DiffToolResult
}

/** merge 도구 실행 결과. */
sealed interface MergeToolResult {

    /** 도구가 `$MERGED` 를 고쳐 저장했다. [content] 를 충돌 해결 내용으로 반영한다. */
    data class Resolved(val content: String) : MergeToolResult

    /**
     * 도구가 저장 없이 닫혔다. **원본을 그대로 두고 아무것도 반영하지 않는다** —
     * 사용자가 병합을 그만둔 것이지 빈 결과를 고른 것이 아니다.
     */
    data object Unchanged : MergeToolResult

    /** 도구가 0 이 아닌 [exitCode] 로 끝났다. 그 상태의 `$MERGED` 는 읽지 않는다. */
    data class MergeFailed(val exitCode: Int) : MergeToolResult
}
