package dev.undine.application.diagnostics

import dev.undine.domain.diagnostics.DiagnosticsGateway
import dev.undine.domain.diagnostics.LogDirectoryLocation
import dev.undine.domain.diagnostics.OpenLogDirectoryResult

/**
 * 로그 디렉터리 경로를 조회한다. 없으면 실패가 아니라 '아직 없음' 이 올라온다 — 화면은 그 값으로
 * 열기를 비활성으로 둔다.
 */
class LocateLogDirectoryUseCase(private val diagnosticsGateway: DiagnosticsGateway) {

    suspend fun execute(): LogDirectoryLocation = diagnosticsGateway.locateLogDirectory()
}

/**
 * 로그 디렉터리를 파일 관리자에서 연다.
 *
 * 결과를 가공하지 않는다 — '아직 없음' 과 열기 실패 사유가 그대로 화면까지 올라가야 사용자가
 * 무엇이 잘못됐는지 안다. 취소도 삼키지 않고 그대로 전파된다.
 */
class OpenLogDirectoryUseCase(private val diagnosticsGateway: DiagnosticsGateway) {

    suspend fun execute(): OpenLogDirectoryResult = diagnosticsGateway.openLogDirectory()
}

/**
 * 진단 UseCase 묶음. 고급 탭이 쓰는 두 동작을 **한 의존성으로** 전달한다.
 *
 * 묶는 이유는 [dev.undine.application.identity.IdentityUseCases] 와 같다 — 탭이 쓰는 동작이 늘어도
 * `PreferencesScreen` 의 호출부가 바뀌지 않아야 한다.
 */
data class DiagnosticsUseCases(
    val locateLogDirectory: LocateLogDirectoryUseCase,
    val openLogDirectory: OpenLogDirectoryUseCase,
)
