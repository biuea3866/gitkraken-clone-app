package dev.undine.application.commitdetail

import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.FileChange
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

private val MERGE_COMMIT = CommitId.of("a".repeat(40))

private val CHANGED_FILE = FileChange(
    path = "src/main/kotlin/dev/undine/domain/Commit.kt",
    previousPath = null,
    changeType = ChangeType.MODIFIED,
    addedLines = 3,
    deletedLines = 1,
    isBinary = false,
)

/**
 * 상세 패널이 파일 목록을 얻는 유일한 경로. UseCase 는 얇게 위임만 하고,
 * **hunk 는 절대 요청하지 않는다** — 커밋 클릭마다 전체 diff 를 계산하면 대형 커밋에서 화면이 멈춘다.
 */
class LoadChangedFilesUseCaseSpec : BehaviorSpec({

    given("선택한 커밋과 기준 부모 인덱스") {

        `when`("변경 파일을 요청하면") {
            val diffGateway = mockk<DiffGateway>()
            coEvery { diffGateway.changedFiles(MERGE_COMMIT, 1) } returns listOf(CHANGED_FILE)
            val useCase = LoadChangedFilesUseCase(diffGateway)

            val loaded = useCase.execute(MERGE_COMMIT, 1)

            then("같은 인덱스로 DiffGateway.changedFiles 에 위임한다") {
                loaded shouldBe listOf(CHANGED_FILE)
                coVerify(exactly = 1) { diffGateway.changedFiles(MERGE_COMMIT, 1) }
            }

            then("hunksOf 는 한 번도 요청하지 않는다") {
                coVerify(exactly = 0) { diffGateway.hunksOf(any(), any(), any()) }
            }
        }

        `when`("변경 파일이 0건인 빈 커밋이면") {
            val diffGateway = mockk<DiffGateway>()
            coEvery { diffGateway.changedFiles(MERGE_COMMIT, 0) } returns emptyList()
            val useCase = LoadChangedFilesUseCase(diffGateway)

            then("빈 목록을 그대로 돌려준다") {
                useCase.execute(MERGE_COMMIT, 0) shouldBe emptyList()
            }
        }

        `when`("Gateway 가 실패하면") {
            val diffGateway = mockk<DiffGateway>()
            coEvery { diffGateway.changedFiles(MERGE_COMMIT, 0) } throws
                UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, MERGE_COMMIT.value)
            val useCase = LoadChangedFilesUseCase(diffGateway)

            then("실패를 빈 성공 결과로 숨기지 않고 그대로 올린다") {
                val failure = shouldThrow<UndineException.NotFound> { useCase.execute(MERGE_COMMIT, 0) }
                failure.kind shouldBe UndineException.NotFound.Kind.COMMIT
            }
        }
    }
})
