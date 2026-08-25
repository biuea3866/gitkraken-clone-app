package dev.undine.domain.submodule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private const val COMBINATION_COUNT = 8

/**
 * 서브모듈 상태는 **독립 플래그 세 축**이다 — 단일 enum 으로 접으면 정보가 사라진다.
 *
 * 저장소가 필요 없는 순수 값 타입이라 단위로 검증한다
 * ([`testing`](../../../../../../../.agent/rules/testing.md) 규칙 3).
 */
class SubmoduleStateSpec : FunSpec({

    test("수정됨과 어긋남이 동시에 성립하면 두 정보가 모두 남는다") {
        val state = SubmoduleState(
            initialized = true,
            locallyModified = true,
            divergedFromRecorded = true,
        )

        state.initialized shouldBe true
        state.locallyModified shouldBe true
        state.divergedFromRecorded shouldBe true
    }

    test("세 축의 여덟 조합이 서로 다른 값이다") {
        val flags = listOf(false, true)
        val combinations = flags.flatMap { initialized ->
            flags.flatMap { modified ->
                flags.map { diverged -> SubmoduleState(initialized, modified, diverged) }
            }
        }

        combinations shouldHaveSize COMBINATION_COUNT
        combinations.toSet() shouldHaveSize COMBINATION_COUNT
    }

    test("한 축만 달라도 다른 상태다 — 우선순위로 접히지 않는다") {
        val modifiedOnly = SubmoduleState(initialized = true, locallyModified = true, divergedFromRecorded = false)
        val both = SubmoduleState(initialized = true, locallyModified = true, divergedFromRecorded = true)

        (modifiedOnly == both) shouldBe false
    }
})
