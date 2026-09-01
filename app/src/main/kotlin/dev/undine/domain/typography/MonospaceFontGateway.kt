package dev.undine.domain.typography

/**
 * 설치된 고정폭 서체를 열거하는 **읽기 전용** 계약. 구현은 `MonospaceFontGatewayImpl` 이다.
 *
 * 열거를 infrastructure 로 미는 이유: `java.awt.GraphicsEnvironment` 는 플랫폼 API 라
 * presentation 이 직접 부르면 화면이 플랫폼에 묶인다.
 *
 * **고정폭만 거른다.** 전체 서체는 수백 개라 고르기 어렵고, 이 설정이 쓰이는 곳은 diff·코드 표시다.
 *
 * **저장된 `Settings.monospaceFontFamily` 를 건드리지 않는다.** 이 계약에는 쓰기도 검증도 없다 —
 * 목록에 없는 이름은 아직 설치하지 않았거나 열거가 실패한 것일 수 있어, 조회 결과를 근거로
 * 사용자가 적어 둔 값을 지우면 안 된다.
 */
interface MonospaceFontGateway {

    /**
     * 설치된 고정폭 서체 이름을 중복 없이 이름 오름차순으로 돌려준다.
     *
     * 첫 조회는 서체를 하나씩 재느라 느릴 수 있다. 구현은 **첫 성공 결과만** 프로세스 수명 동안
     * 재사용하고 실패는 캐시하지 않는다 — 일시적 실패가 앱을 끌 때까지 굳으면 안 된다.
     */
    suspend fun monospaceFamilies(): MonospaceFontListing
}
