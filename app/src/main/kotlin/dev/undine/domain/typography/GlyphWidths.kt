package dev.undine.domain.typography

/**
 * 한 서체 family 에서 잰 대표 문자 폭.
 *
 * 세 글자를 고른 이유: [narrow] 는 가장 좁은 글자(`i`), [wide] 는 가장 넓은 글자(`W`),
 * [space] 는 공백(` `)이다. 가변폭 서체는 이 셋의 폭이 반드시 갈리고, 고정폭 서체는 정의상
 * 모두 같다 — 서체 이름에 "Mono" 가 들어가는지로 판정하면 이름이 다른 고정폭 서체를 놓치고
 * 이름만 그런 가변폭 서체를 들인다.
 */
data class GlyphWidths(val narrow: Int, val wide: Int, val space: Int) {

    /** 세 폭이 모두 같을 때만 고정폭이다. */
    val isMonospace: Boolean get() = narrow == wide && wide == space
}
