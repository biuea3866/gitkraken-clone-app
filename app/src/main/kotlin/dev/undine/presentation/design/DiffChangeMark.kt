package dev.undine.presentation.design

import androidx.compose.ui.graphics.Color

/**
 * diff 추가·삭제 표시. **배경색과 기호를 함께** 들고 다닌다 —
 * 색각 이상 사용자에게 배경색만으로는 추가와 삭제가 구분되지 않는다.
 *
 * 이 티켓은 표시 규약만 정의하고, diff 뷰어 렌더링은 UND-16 이 소비한다.
 *
 * @property symbol 줄머리에 찍는 기호. 삭제는 하이픈이 아니라 U+2212 MINUS SIGN 이다 —
 *   고정폭 서체에서 `+` 와 같은 폭으로 정렬된다.
 */
enum class DiffChangeMark(val symbol: String) {
    ADDITION("+"),
    DELETION("−"),
    ;

    /** 그 줄 전체에 깔리는 배경색. */
    fun backgroundOf(colors: ColorTokens): Color = when (this) {
        ADDITION -> colors.additionSurface
        DELETION -> colors.deletionSurface
    }

    /** [symbol] 과 변경 부분 강조에 쓰는 전경색. [backgroundOf] 위에서 4.5:1 이상이다. */
    fun foregroundOf(colors: ColorTokens): Color = when (this) {
        ADDITION -> colors.addition
        DELETION -> colors.deletion
    }
}
