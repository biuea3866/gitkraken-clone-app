package dev.undine.domain.typography

/**
 * 고정폭 서체 열거 결과.
 *
 * 성공과 실패를 **닫힌 두 갈래**로 가르는 이유: 실패를 빈 목록으로 돌려주면 화면이 "이 기기에
 * 고정폭 서체가 하나도 없다" 로 읽어 사용자를 막다른 곳에 세운다. 열거가 안 되는 것은 사고가
 * 아니라 예상되는 상태(헤드리스·서체 subsystem 손상)라 `UndineException` 하위 타입을 새로
 * 만들지 않는다 (wave 7 결정 D2 와 같은 판단).
 */
sealed interface MonospaceFontListing {

    /**
     * 열거에 성공했다. [families] 는 중복 없이 이름 오름차순이며, **빈 목록도 성공**이다 —
     * 고정폭 서체를 하나도 못 찾은 것과 물어보지 못한 것은 다른 사실이다.
     */
    data class Available(val families: List<String>) : MonospaceFontListing

    /**
     * 플랫폼에 서체 목록을 물어볼 수 없었다. [cause] 를 실어 화면이 로그 안내를 할 수 있게 한다.
     *
     * **저장된 서체 이름을 지우거나 거부하는 근거가 아니다** — 목록을 못 얻은 것뿐이므로
     * 소비자는 기존 직접 입력 경로를 그대로 유지한다.
     */
    data class Unavailable(val cause: Throwable) : MonospaceFontListing
}
