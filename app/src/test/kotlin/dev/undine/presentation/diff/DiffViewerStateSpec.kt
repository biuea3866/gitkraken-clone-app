package dev.undine.presentation.diff

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** 뷰 모드 상태 홀더 — 화면이 아니라 상태가 모드를 소유한다 (compose-ui 규칙 1). */
class DiffViewerStateSpec : FunSpec({

    test("기본 모드는 통합 뷰다") {
        DiffViewerState().viewMode shouldBe DiffViewMode.UNIFIED
    }

    test("모드를 지정해 만들 수 있다") {
        DiffViewerState(DiffViewMode.SPLIT).viewMode shouldBe DiffViewMode.SPLIT
    }

    test("모드 전환은 통합과 분할을 오간다") {
        val state = DiffViewerState()

        state.showViewMode(DiffViewMode.SPLIT)
        state.viewMode shouldBe DiffViewMode.SPLIT

        state.toggleViewMode()
        state.viewMode shouldBe DiffViewMode.UNIFIED

        state.toggleViewMode()
        state.viewMode shouldBe DiffViewMode.SPLIT
    }

    test("같은 모드를 다시 지정해도 그대로 남는다") {
        val state = DiffViewerState(DiffViewMode.SPLIT)

        state.showViewMode(DiffViewMode.SPLIT)

        state.viewMode shouldBe DiffViewMode.SPLIT
    }
})
