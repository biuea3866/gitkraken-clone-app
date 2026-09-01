package dev.undine.domain.typography

/**
 * 플랫폼 서체 subsystem 경계.
 *
 * 인터페이스로 두는 이유는 **설치 서체가 기기마다 다른 환경에서도 열거·필터·캐시 로직을 전수
 * 검증**하기 위해서다 (`ExternalToolRunner` 와 같은 경계). 실제 AWT 구현은 infrastructure 에 있다.
 *
 * 두 연산 모두 blocking 이다 — `Dispatchers.IO` 로 옮기는 것은 이 경계를 쓰는 Gateway 구현의 몫이다.
 */
interface FontProbe {

    /**
     * 설치된 서체 family 이름. 플랫폼이 같은 이름을 여러 번 돌려줄 수 있어 **중복 제거를 하지
     * 않는다** — 그 판단은 [monospaceFamiliesOf] 가 한다.
     *
     * 서체 subsystem 을 쓸 수 없으면 던진다. 빈 목록으로 뭉개지 않는다 — "설치된 고정폭 서체가
     * 없다" 와 "물어볼 수 없었다" 는 화면이 다르게 안내해야 한다.
     */
    fun availableFamilies(): List<String>

    /** [family] 의 대표 문자 폭. 알 수 없는 이름이면 플랫폼 기본 서체의 폭이 나온다. */
    fun glyphWidths(family: String): GlyphWidths
}
