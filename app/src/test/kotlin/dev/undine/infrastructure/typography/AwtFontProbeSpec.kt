package dev.undine.infrastructure.typography

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.awt.Font

/**
 * 실제 AWT 경로가 살아 있는지만 확인한다. 검증 대상은 **JDK 논리 서체**뿐이다 —
 * `Monospaced`·`Serif` 는 어떤 JDK 에나 있어 설치 서체 목록이 다른 기기에서도 결정적이다.
 * 필터·캐시 계약은 가짜 probe 를 쓰는 [MonospaceFontGatewayImplSpec] 이 본다.
 */
class AwtFontProbeSpec : FunSpec({

    test("설치된 서체 목록에 JDK 논리 서체가 들어 있다") {
        val families = AwtFontProbe().availableFamilies()

        families.size shouldBeGreaterThan 0
        families shouldContain Font.MONOSPACED
    }

    test("논리 고정폭 서체는 i·W·공백 폭이 같다") {
        AwtFontProbe().glyphWidths(Font.MONOSPACED).isMonospace shouldBe true
    }
})
