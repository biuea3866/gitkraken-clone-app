package dev.undine.domain.typography

/**
 * [families] 중 고정폭인 것만 **중복 없이 이름 오름차순으로** 돌려준다.
 *
 * 폭 측정([widthsOf])을 인자로 받는 순수 함수라, 판정 규칙을 실제 설치 서체 없이 검증할 수 있다.
 * 중복 제거를 측정보다 **먼저** 해서 같은 family 를 두 번 재지 않는다 — 플랫폼 열거는 같은
 * 이름을 여러 번 돌려줄 수 있고 측정은 서체마다 비싸다.
 */
fun monospaceFamiliesOf(families: List<String>, widthsOf: (String) -> GlyphWidths): List<String> =
    families.distinct()
        .filter { family -> widthsOf(family).isMonospace }
        .sorted()
