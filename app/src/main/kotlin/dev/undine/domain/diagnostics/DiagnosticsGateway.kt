package dev.undine.domain.diagnostics

/**
 * 로그가 쌓이는 앱 디렉터리를 **알려 주고 열어 주는** 계약. 구현은 `DiagnosticsGatewayImpl` 이다.
 *
 * **여는 것까지만** 한다 — 로그 내용을 앱 안에서 보여 주거나 어딘가로 보내지 않는다. 로그에는
 * 사용자의 저장소 경로가 들어 있어 전송은 별도 판단이 필요하다.
 *
 * 여는 대상은 앱 디렉터리 자체다. 거기엔 `settings.json` 도 함께 있다 — 로그만 있는 폴더가
 * 아니라는 것을 알고 택한 결정이다 (wave 8 결정 G34 UND-78 3).
 */
interface DiagnosticsGateway {

    /**
     * 로그 디렉터리가 지금 있는지 보고 있으면 그 경로를 돌려준다.
     *
     * **경로를 만들지 않는다** — 조회가 부수 효과로 디렉터리를 만들면 "아직 없음" 을 두 번 다시
     * 관측할 수 없다.
     */
    suspend fun locateLogDirectory(): LogDirectoryLocation

    /**
     * 로그 디렉터리를 플랫폼 파일 관리자로 연다. 디렉터리가 없으면 [LogDirectoryMissing] 이고
     * 띄우지 못하면 사유가 담긴 실패다 — 어느 쪽도 조용한 성공이 되지 않는다.
     */
    suspend fun openLogDirectory(): OpenLogDirectoryResult
}
