package dev.undine.presentation.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 타이포그래피 토큰. 색은 담지 않는다 — 색은 [ColorTokens] 가 소유하고 사용처에서 조합한다.
 *
 * @property mono diff 본문·커밋 해시처럼 **자릿수 정렬이 의미를 갖는** 텍스트 전용 고정폭 서체.
 *   이 티켓은 토큰만 제공하고, 실제 적용은 소비 티켓(UND-14·UND-16)이 한다.
 */
data class TypographyTokens(
    val title: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
) {
    companion object {

        val Default = TypographyTokens(
            title = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            body = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            caption = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            mono = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        )
    }
}
