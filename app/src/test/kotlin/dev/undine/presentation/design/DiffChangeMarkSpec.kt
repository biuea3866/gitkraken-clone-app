package dev.undine.presentation.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** 색각 이상 사용자에게 배경색만으로는 정보가 전달되지 않는다 — 기호가 함께 있어야 한다. */
class DiffChangeMarkSpec : FunSpec({

    test("추가·삭제 두 종류로 닫혀 있다") {
        DiffChangeMark.entries.map { it.name } shouldContainExactly listOf("ADDITION", "DELETION")
    }

    test("추가는 + 기호, 삭제는 − 기호를 갖는다") {
        DiffChangeMark.ADDITION.symbol shouldBe "+"
        DiffChangeMark.DELETION.symbol shouldBe "−"
    }

    test("두 표시는 기호와 배경색이 모두 서로 다르다 — 색 하나에 의존하지 않는다") {
        DiffChangeMark.ADDITION.symbol shouldNotBe DiffChangeMark.DELETION.symbol
        listOf(UndineTokenSet.Light.color, UndineTokenSet.Dark.color).forEach { colors ->
            DiffChangeMark.ADDITION.backgroundOf(colors) shouldNotBe DiffChangeMark.DELETION.backgroundOf(colors)
            DiffChangeMark.ADDITION.foregroundOf(colors) shouldNotBe DiffChangeMark.DELETION.foregroundOf(colors)
        }
    }

    test("배경색은 diff 전용 표면 토큰에서 오고 기호색은 상태색 토큰에서 온다") {
        val colors = UndineTokenSet.Light.color
        DiffChangeMark.ADDITION.backgroundOf(colors) shouldBe colors.additionSurface
        DiffChangeMark.DELETION.backgroundOf(colors) shouldBe colors.deletionSurface
        DiffChangeMark.ADDITION.foregroundOf(colors) shouldBe colors.addition
        DiffChangeMark.DELETION.foregroundOf(colors) shouldBe colors.deletion
    }
})
