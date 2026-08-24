package dev.undine.presentation.rebase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val ROW_HEIGHT = 40f

/** 드래그 거리 → 놓을 자리. 재정렬의 실제 규칙이라 제스처와 떼어 검증한다. */
class RebaseDragSpec : FunSpec({

    test("행 높이의 절반을 넘기면 한 칸 움직인다") {
        dropTargetIndex(from = 1, dragOffsetPx = ROW_HEIGHT * 0.4f, rowHeightPx = ROW_HEIGHT, count = 4) shouldBe 1
        dropTargetIndex(from = 1, dragOffsetPx = ROW_HEIGHT * 0.6f, rowHeightPx = ROW_HEIGHT, count = 4) shouldBe 2
        dropTargetIndex(from = 1, dragOffsetPx = -ROW_HEIGHT * 0.6f, rowHeightPx = ROW_HEIGHT, count = 4) shouldBe 0
    }

    test("여러 칸을 지나면 그만큼 움직인다") {
        dropTargetIndex(from = 0, dragOffsetPx = ROW_HEIGHT * 2, rowHeightPx = ROW_HEIGHT, count = 4) shouldBe 2
        dropTargetIndex(from = 3, dragOffsetPx = -ROW_HEIGHT * 3, rowHeightPx = ROW_HEIGHT, count = 4) shouldBe 0
    }

    test("목록 밖으로는 나가지 않는다") {
        dropTargetIndex(from = 0, dragOffsetPx = -ROW_HEIGHT * 5, rowHeightPx = ROW_HEIGHT, count = 3) shouldBe 0
        dropTargetIndex(from = 2, dragOffsetPx = ROW_HEIGHT * 5, rowHeightPx = ROW_HEIGHT, count = 3) shouldBe 2
    }

    test("행 높이를 아직 재지 못했으면 움직이지 않는다") {
        // 0 으로 나누지 않는다 — 측정 전 드래그는 무시한다.
        dropTargetIndex(from = 1, dragOffsetPx = 999f, rowHeightPx = 0f, count = 3) shouldBe 1
    }

    test("빈 목록에서는 움직일 자리가 없다") {
        dropTargetIndex(from = 0, dragOffsetPx = ROW_HEIGHT, rowHeightPx = ROW_HEIGHT, count = 0) shouldBe 0
    }
})
