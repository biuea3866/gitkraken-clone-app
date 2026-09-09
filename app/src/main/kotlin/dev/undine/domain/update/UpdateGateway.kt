package dev.undine.domain.update

import java.nio.file.Path

/**
 * 릴리즈 확인 · 다운로드 · 무결성 검증 · OS 기본 열기의 계약. 구현은 `UpdateGatewayImpl` 이다.
 *
 * **앱을 우리가 교체하지 않는다** (결정 D12). 검증을 통과한 설치 파일을 OS 설치 관리자에게 넘기는
 * 데까지가 이 계약의 끝이고, 그 뒤는 OS 와 사용자의 일이다. 그래서 "실패해도 기존 설치본이 남는다"
 * 가 절차가 아니라 **구조로** 성립한다 — 우리는 애초에 설치본을 건드리지 않는다.
 *
 * 세 단계를 한 메서드로 합치지 않는 이유는 **동의 지점이 그 사이에 있기** 때문이다. 확인은 자동이고
 * 다운로드·열기는 사용자가 누른 뒤에만 일어난다.
 */
interface UpdateGateway {

    /**
     * 최신 릴리즈를 조회한다.
     *
     * 실패를 "업데이트 없음" 으로 접지 않는다 — [UpdateCheckResult] 의 네 상태가 서로 다른 사실이다.
     */
    suspend fun checkForUpdate(): UpdateCheckResult

    /**
     * 이 OS 용 자산을 앱 데이터 디렉터리 아래 update 하위 디렉터리에 받고 체크섬을 대조한다.
     *
     * 대조에 실패하면 **방금 받은 파일 하나만** 지운다 (결정 D5) — 삭제가 기존 파일로 번지지 않게
     * 대상을 그 디렉터리 안의 그 이름 하나로 좁힌다.
     */
    suspend fun downloadAndVerify(release: AvailableRelease): UpdateDownloadResult

    /**
     * 검증을 통과한 [installer] 를 OS 기본 동작으로 연다.
     *
     * 열지 못해도 파일을 지우지 않는다 (결정 D18) — 파일은 정상이고 실패한 것은 여는 동작이다.
     */
    suspend fun openInstaller(installer: Path): InstallerLaunchResult
}
