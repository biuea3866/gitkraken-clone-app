package dev.undine.domain.undo

import dev.undine.domain.RefName
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val BRANCH = RefName("feature")
private val TAG = RefName("v1.0.0")

/** 이동 전 위치 — 되돌리기가 돌아갈 지점. */
private val PREVIOUS = commitId(1)

/** 이동이 만든 위치 — 되돌리기가 조건부 갱신에 쓰는 기대 위치. */
private val EXPECTED = commitId(2)

private val OTHER = commitId(3)

/**
 * 이동 되돌리기 전략 3종이 **이전 target 과 기대 target 을 둘 다** 갖는지 본다 (결정 G2·G5).
 *
 * 이전 값만 있으면 되돌리기가 기록 이후의 이동을 덮어쓴다 — 기대 값이 있어야 "그 사이 누가 옮겼다면
 * 되돌리지 않는다" 가 성립한다. 기대 값이 동치성에 참여하는지로 그것이 **선택 값이 아님**을 확인한다.
 */
class UndoMoveStrategySpec : FunSpec({

    test("MoveBranchTo 는 브랜치·이전 target·기대 target 을 함께 보유하는 되돌릴 수 있는 전략이다") {
        val strategy = UndoStrategy.MoveBranchTo(BRANCH, previous = PREVIOUS, expected = EXPECTED)

        strategy.branch shouldBe BRANCH
        strategy.previous shouldBe PREVIOUS
        strategy.expected shouldBe EXPECTED
        strategy.shouldBeInstanceOf<UndoStrategy.Reversible>()
    }

    test("MoveTagTo 는 태그·이전 target·기대 target 을 함께 보유하는 되돌릴 수 있는 전략이다") {
        val strategy = UndoStrategy.MoveTagTo(TAG, previous = PREVIOUS, expected = EXPECTED)

        strategy.tag shouldBe TAG
        strategy.previous shouldBe PREVIOUS
        strategy.expected shouldBe EXPECTED
        strategy.shouldBeInstanceOf<UndoStrategy.Reversible>()
    }

    test("HardResetTo 는 대상 브랜치·이전 target·기대 target 을 함께 보유하는 되돌릴 수 있는 전략이다") {
        val strategy = UndoStrategy.HardResetTo(BRANCH, previous = PREVIOUS, expected = EXPECTED)

        strategy.branch shouldBe BRANCH
        strategy.previous shouldBe PREVIOUS
        strategy.expected shouldBe EXPECTED
        strategy.shouldBeInstanceOf<UndoStrategy.Reversible>()
    }

    test("기대 target 만 다른 기록은 서로 다른 기록이다 — 기대 target 은 생략 가능한 값이 아니다") {
        UndoStrategy.HardResetTo(BRANCH, previous = PREVIOUS, expected = EXPECTED) shouldNotBe
            UndoStrategy.HardResetTo(BRANCH, previous = PREVIOUS, expected = OTHER)

        UndoStrategy.MoveBranchTo(BRANCH, previous = PREVIOUS, expected = EXPECTED) shouldNotBe
            UndoStrategy.MoveBranchTo(BRANCH, previous = PREVIOUS, expected = OTHER)

        UndoStrategy.MoveTagTo(TAG, previous = PREVIOUS, expected = EXPECTED) shouldNotBe
            UndoStrategy.MoveTagTo(TAG, previous = PREVIOUS, expected = OTHER)
    }
})
