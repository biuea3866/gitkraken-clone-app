package dev.undine.infrastructure.typography

import dev.undine.domain.typography.FontProbe
import dev.undine.domain.typography.GlyphWidths
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage

private const val PROBE_FONT_SIZE = 12
private const val NARROW_CHARACTER = 'i'
private const val WIDE_CHARACTER = 'W'
private const val SPACE_CHARACTER = ' '

/**
 * AWT 로 플랫폼 서체를 열거하고 재는 [FontProbe] 구현.
 *
 * 폭은 `FontMetrics.charWidth` 로 잰다. `FontMetrics` 를 얻으려면 `Graphics` 가 필요한데,
 * 1×1 오프스크린 이미지에서 만들면 헤드리스 환경에서도 창 없이 잴 수 있다. 그래픽스는 잰 직후
 * `dispose` 한다 — 프로세스 수명 동안 들고 있으면 네이티브 자원이 남는다. 열거 자체가
 * 캐시돼 한 번만 도는 경로라 이미지를 매번 만드는 비용은 문제되지 않는다.
 *
 * 실패를 삼키지 않는다. 서체 subsystem 을 쓸 수 없으면 그대로 던지고, 성공·실패 구분은
 * [MonospaceFontGatewayImpl] 이 결과 타입으로 옮긴다.
 */
class AwtFontProbe : FontProbe {

    override fun availableFamilies(): List<String> =
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()

    override fun glyphWidths(family: String): GlyphWidths {
        val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        return try {
            val metrics = graphics.getFontMetrics(Font(family, Font.PLAIN, PROBE_FONT_SIZE))
            GlyphWidths(
                narrow = metrics.charWidth(NARROW_CHARACTER),
                wide = metrics.charWidth(WIDE_CHARACTER),
                space = metrics.charWidth(SPACE_CHARACTER),
            )
        } finally {
            graphics.dispose()
        }
    }
}
