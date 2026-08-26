package dev.undine.domain.submodule

import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val COMBINATION_COUNT = 8

private const val SUBMODULE_PATH = "lib"

/** 보존해야 할 것 — 커밋되지 않은 변경과, `Status.isClean` 이 통과시키는 무시된 파일. */
private val PRESERVED = listOf("lib/작업중.txt(커밋되지 않은 변경)", "lib/무시된.log(무시된 파일)")

/** 유효한 저장소로 열리지 않아 무엇이 들었는지 모르는 경로 — 깨끗함이 아니다. */
private val UNDECIDABLE = listOf("lib(판정 불가)")

/**
 * 계약이 **보존 대상 목록과 함께 거부하는 모양**을 표현할 수 있는지만 보는 스텁.
 * 저장소가 필요 없는 검증이라 여기서 단위로 본다 — 실제 JGit 경로는 `SubmoduleGatewayImplSpec` 이 본다.
 */
private class RefusingSubmoduleGateway(private val preserved: List<String>) : SubmoduleGateway {

    override suspend fun list(): List<Submodule> = emptyList()

    override suspend fun initialize(path: String, recursive: Boolean) = Unit

    override suspend fun update(path: String, recursive: Boolean) = Unit

    override suspend fun add(url: String, path: String, branch: String?): Submodule =
        error("이 스텁이 다루는 연산이 아닙니다.")

    /** 확인 여부와 무관하게 보존 대상이 있으면 거부한다 — 새 예외 종류를 만들지 않는다. */
    override suspend fun remove(path: String, confirmed: Boolean): Unit =
        throw UndineException.StateViolation(
            "서브모듈 '$path' 아래에 보존해야 할 항목이 있어 제거할 수 없습니다: ${preserved.joinToString()}",
        )
}

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

    test("제거 거부는 보존 대상을 하나도 빠뜨리지 않고 사유에 담는다") {
        val gateway = RefusingSubmoduleGateway(PRESERVED)

        val failure = shouldThrow<UndineException.StateViolation> {
            gateway.remove(SUBMODULE_PATH, confirmed = true)
        }

        PRESERVED.forEach { entry -> failure.detail shouldContain entry }
    }

    test("보존 대상이 있으면 확인 여부와 무관하게 거부된다") {
        val gateway = RefusingSubmoduleGateway(PRESERVED)

        listOf(true, false).forEach { confirmed ->
            shouldThrow<UndineException.StateViolation> { gateway.remove(SUBMODULE_PATH, confirmed) }
        }
    }

    test("판정 불가도 같은 StateViolation 으로 표현된다 — 깨끗함으로 접히지 않는다") {
        val gateway = RefusingSubmoduleGateway(UNDECIDABLE)

        val failure = shouldThrow<UndineException.StateViolation> {
            gateway.remove(SUBMODULE_PATH, confirmed = true)
        }

        failure.detail shouldContain "판정 불가"
    }

    test("한 축만 달라도 다른 상태다 — 우선순위로 접히지 않는다") {
        val modifiedOnly = SubmoduleState(initialized = true, locallyModified = true, divergedFromRecorded = false)
        val both = SubmoduleState(initialized = true, locallyModified = true, divergedFromRecorded = true)

        (modifiedOnly == both) shouldBe false
    }
})
