package dev.undine.presentation.design

import androidx.compose.ui.graphics.Color

/**
 * 색 토큰. **색 리터럴이 존재하는 유일한 파일**이다 — 라이트/다크 전환이 여기 한 곳에서 끝나야 하고,
 * `DesignSourceScanSpec` 이 나머지 design 소스에 리터럴이 없는지 스캔으로 강제한다.
 *
 * 값은 WCAG 2.1 상대 휘도 대비비를 기준으로 골랐다 (결정 문서 UND-10).
 * 본문 텍스트([foregroundPrimary]·[foregroundSecondary]·[foregroundTertiary])는 [background]·[surface]
 * 양쪽에서 4.5:1 이상, 경계·강조·상태색은 3:1 이상이다. `ColorContrastSpec` 이 회귀를 막는다.
 *
 * @property divider 영역을 나누는 선. **본문 대비 요구를 받지 않는다** — 읽는 대상이 아니라 구획
 *   표시이며, [border] 만큼 밝으면 화면이 선으로 갈라져 보인다. 그래서 3:1 대비 검증 목록
 *   (`nonTextColorsOf`)에 넣지 않는다. 상호작용하는 경계(칩·입력)는 여전히 [border] 를 쓴다.
 * @property lanePalette 그래프 레인 색. 슬롯은 순환 재사용되므로 **마지막↔첫 번째를 포함한 인접 쌍**이
 *   서로 3:1 이상이다. 이를 위해 어두운 색과 중간 밝기 색이 번갈아 배치돼 있으며, 그 결과 중간 밝기
 *   레인은 배경 대비가 약 2:1 로 낮다 — 인접 구분(요구사항)을 배경 대비보다 우선한 결과다.
 */
data class ColorTokens(
    val background: Color,
    val surface: Color,
    val border: Color,
    val divider: Color,
    val foregroundPrimary: Color,
    val foregroundSecondary: Color,
    val foregroundTertiary: Color,
    val accent: Color,
    val addition: Color,
    val deletion: Color,
    val conflict: Color,
    val warning: Color,
    val additionSurface: Color,
    val deletionSurface: Color,
    val lanePalette: List<Color>,
) {
    companion object {

        val Light = ColorTokens(
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFF1F3F5),
            border = Color(0xFF767E8A),
            divider = Color(0xFFD8DDE3),
            foregroundPrimary = Color(0xFF12161C),
            foregroundSecondary = Color(0xFF3A424E),
            foregroundTertiary = Color(0xFF59626F),
            accent = Color(0xFF0B5CD5),
            addition = Color(0xFF0E6B33),
            deletion = Color(0xFFB3261E),
            conflict = Color(0xFF8B2FB0),
            warning = Color(0xFF8A5300),
            additionSurface = Color(0xFFE4F5E9),
            deletionSurface = Color(0xFFFCE8E6),
            lanePalette = listOf(
                Color(0xFF12429E),
                Color(0xFFE5933A),
                Color(0xFF8C1616),
                Color(0xFF4FB3C8),
                Color(0xFF14591F),
                Color(0xFFE88AB0),
                Color(0xFF5B2A9E),
                Color(0xFF8FBE3F),
                Color(0xFF6B3A12),
                Color(0xFFA79BE8),
                Color(0xFF0B4F52),
                Color(0xFFD9C24A),
            ),
        )

        val Dark = ColorTokens(
            background = Color(0xFF12161C),
            surface = Color(0xFF1C222B),
            border = Color(0xFF7C8694),
            divider = Color(0xFF2A313C),
            foregroundPrimary = Color(0xFFEEF1F5),
            foregroundSecondary = Color(0xFFBFC7D2),
            foregroundTertiary = Color(0xFF9AA4B2),
            accent = Color(0xFF7FB4FF),
            addition = Color(0xFF66D397),
            deletion = Color(0xFFFF938C),
            conflict = Color(0xFFD9A2F0),
            warning = Color(0xFFE5B34B),
            additionSurface = Color(0xFF16301F),
            deletionSurface = Color(0xFF3A1B1A),
            lanePalette = listOf(
                Color(0xFF5E9BFF),
                Color(0xFF6E3A0C),
                Color(0xFFFF8F87),
                Color(0xFF124F54),
                Color(0xFF7DD48B),
                Color(0xFF7A2853),
                Color(0xFFC4A5FF),
                Color(0xFF36510F),
                Color(0xFFF0B45C),
                Color(0xFF31316B),
                Color(0xFF5FD6D0),
                Color(0xFF663010),
            ),
        )
    }
}
