package dev.undine.domain.typography

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val MONOSPACE = GlyphWidths(narrow = 7, wide = 7, space = 7)
private val PROPORTIONAL = GlyphWidths(narrow = 3, wide = 11, space = 4)

/**
 * 고정폭 판정은 폭 측정을 주입받는 순수 함수라 실제 설치 서체 없이 전수 검증한다 —
 * 개발 기기마다 다른 서체 목록에 테스트가 흔들리지 않게 하려는 경계다.
 */
class MonospaceFontFilterSpec : FunSpec({

    test("i·W·공백 폭이 모두 같으면 고정폭으로 판정한다") {
        MONOSPACE.isMonospace shouldBe true
    }

    test("i·W·공백 중 하나라도 폭이 다르면 고정폭이 아니다") {
        GlyphWidths(narrow = 3, wide = 7, space = 7).isMonospace shouldBe false
        GlyphWidths(narrow = 7, wide = 11, space = 7).isMonospace shouldBe false
        GlyphWidths(narrow = 7, wide = 7, space = 4).isMonospace shouldBe false
    }

    test("고정폭이 아닌 family 는 결과에서 빠진다") {
        val families = listOf("Fira Code", "Georgia", "JetBrains Mono")

        val monospace = monospaceFamiliesOf(families) { family ->
            if (family == "Georgia") PROPORTIONAL else MONOSPACE
        }

        monospace shouldContainExactly listOf("Fira Code", "JetBrains Mono")
    }

    test("중복을 제거하고 이름 오름차순으로 정렬한다") {
        val families = listOf("Menlo", "Fira Code", "Menlo", "Andale Mono")

        val monospace = monospaceFamiliesOf(families) { MONOSPACE }

        monospace shouldContainExactly listOf("Andale Mono", "Fira Code", "Menlo")
    }

    test("중복 family 는 폭을 한 번만 측정한다") {
        val measured = mutableListOf<String>()

        monospaceFamiliesOf(listOf("Menlo", "Menlo", "Menlo")) { family ->
            measured += family
            MONOSPACE
        }

        measured shouldContainExactly listOf("Menlo")
    }

    test("빈 목록은 빈 결과다") {
        monospaceFamiliesOf(emptyList()) { MONOSPACE }.shouldBeEmpty()
    }
})
