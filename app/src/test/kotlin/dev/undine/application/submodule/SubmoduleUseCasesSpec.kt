package dev.undine.application.submodule

import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult
import dev.undine.domain.StagingGateway
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.domain.submodule.SubmoduleState
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class SubmoduleUseCasesSpec : BehaviorSpec({
    given("서브모듈 Gateway") {
        val gateway = mockk<SubmoduleGateway>(relaxUnitFun = true)
        val submodule = Submodule("modules/core", null, SubmoduleState(true, false, false))
        coEvery { gateway.list() } returns listOf(submodule)

        `when`("목록·초기화·업데이트 UseCase를 실행하면") {
            val listed = LoadSubmodulesUseCase(gateway).execute()
            InitializeSubmoduleUseCase(gateway).execute("modules/core")
            UpdateSubmoduleUseCase(gateway).execute("modules/core")

            then("각 요청을 Gateway에 그대로 위임한다") {
                listed shouldBe listOf(submodule)
                coVerify(exactly = 1) { gateway.initialize("modules/core", recursive = false) }
                coVerify(exactly = 1) { gateway.update("modules/core", recursive = false) }
            }
        }
    }

    given("스테이징 Gateway") {
        val gateway = mockk<StagingGateway>(relaxUnitFun = true)
        val result = CommitResult(CommitId.of("a".repeat(40)))
        coEvery { gateway.commit("서브모듈 포인터 갱신") } returns result

        `when`("현재 서브모듈 상태를 부모에 커밋하면") {
            val committed = CommitSubmodulePointerUseCase(gateway).execute(
                path = "modules/core",
                message = "서브모듈 포인터 갱신",
            )

            then("서브모듈 경로 하나를 stage한 뒤 같은 메시지로 commit한다") {
                committed shouldBe result
                coVerify(exactly = 1) { gateway.stage(listOf("modules/core")) }
                coVerify(exactly = 1) { gateway.commit("서브모듈 포인터 갱신") }
            }
        }
    }
})
