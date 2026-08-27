package dev.undine.application.externaltool

import dev.undine.domain.externaltool.DiffToolInput
import dev.undine.domain.externaltool.DiffToolResult
import dev.undine.domain.externaltool.ExternalToolGateway
import dev.undine.domain.externaltool.MergeToolInput
import dev.undine.domain.externaltool.MergeToolResult

/**
 * 외부 diff 도구를 띄우고 종료를 기다린다.
 *
 * **시간 제한을 두지 않는다** — 대화형 도구라 몇십 분이 정상이다. 종료 경로는 호출자의 코루틴
 * 취소뿐이며, 취소는 삼키지 않고 Gateway 까지 그대로 전파된다.
 */
class OpenDiffToolUseCase(private val externalToolGateway: ExternalToolGateway) {

    suspend fun execute(input: DiffToolInput): DiffToolResult = externalToolGateway.openDiff(input)
}

/** 외부 merge 도구를 띄우고 종료를 기다린 뒤 편집 결과를 돌려준다. */
class OpenMergeToolUseCase(private val externalToolGateway: ExternalToolGateway) {

    suspend fun execute(input: MergeToolInput): MergeToolResult = externalToolGateway.openMerge(input)
}

/**
 * 외부 도구 UseCase 묶음. 환경설정 도구 탭이 설정한 도구를 **실제로 띄워 확인**할 때 쓴다.
 *
 * 묶는 이유는 [dev.undine.application.identity.IdentityUseCases] 와 같다 — 탭이 쓰는 동작이 늘어도
 * `PreferencesScreen` 의 호출부가 바뀌지 않아야 탭 티켓이 그 파일을 건드리지 않고 일할 수 있다.
 */
data class ExternalToolUseCases(
    val openDiff: OpenDiffToolUseCase,
    val openMerge: OpenMergeToolUseCase,
)
