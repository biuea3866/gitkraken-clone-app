package dev.undine.application.search

import dev.undine.domain.ChangeType
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.FileChange
import dev.undine.domain.HistoryGateway
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.search.CommitSearchCriteria
import dev.undine.domain.search.commitOf
import dev.undine.domain.search.hashOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

private const val PAGE_SIZE = 2
private val REFS = listOf(RefName("refs/heads/main"))

/** 찾은 커밋만 남긴다 — 진행량 사건은 별도 시나리오에서 따로 본다. */
private suspend fun Flow<SearchProgress>.matchedCommits(): List<Commit> =
    filterIsInstance<SearchProgress.Match>().map { match -> match.commit }.toList()

private fun fileChange(path: String) = FileChange(
    path = path,
    previousPath = null,
    changeType = ChangeType.MODIFIED,
    addedLines = 1,
    deletedLines = 0,
    isBinary = false,
)

/**
 * 검색 UseCase — 페이지 순회·점진 방출·경로 필터·실패 전파.
 *
 * 호출 횟수를 세므로 Gateway 대역은 **Given 마다 새로** 만든다 — 한 대역을 공유하면
 * 앞 시나리오의 호출이 뒤 시나리오의 검증에 섞인다.
 */
class SearchCommitsUseCaseSpec : BehaviorSpec({

    Given("여러 페이지에 걸친 이력") {
        val historyGateway = mockk<HistoryGateway>()
        val diffGateway = mockk<DiffGateway>()
        val useCase = SearchCommitsUseCase(historyGateway, diffGateway, pageSize = PAGE_SIZE)

        val first = commitOf(id = hashOf("aa1"), message = "fix login timeout")
        val second = commitOf(id = hashOf("bb2"), message = "docs 정리")
        val third = commitOf(id = hashOf("cc3"), message = "fix LOGIN redirect")

        coEvery { historyGateway.load(REFS, 0, PAGE_SIZE) } returns listOf(first, second)
        coEvery { historyGateway.load(REFS, PAGE_SIZE, PAGE_SIZE) } returns listOf(third)

        When("메시지 조건으로 끝까지 검색하면") {
            val found = useCase.execute(REFS, CommitSearchCriteria(message = "login")).matchedCommits()

            Then("페이지를 offset 순서로 소비해 매칭 커밋만 모은다") {
                found shouldContainExactly listOf(first, third)
                coVerify(exactly = 1) { historyGateway.load(REFS, 0, PAGE_SIZE) }
                coVerify(exactly = 1) { historyGateway.load(REFS, PAGE_SIZE, PAGE_SIZE) }
            }
        }
    }

    Given("첫 페이지에 이미 결과가 있는 이력") {
        val historyGateway = mockk<HistoryGateway>()
        val diffGateway = mockk<DiffGateway>()
        val useCase = SearchCommitsUseCase(historyGateway, diffGateway, pageSize = PAGE_SIZE)

        val first = commitOf(id = hashOf("aa1"), message = "fix login timeout")
        val second = commitOf(id = hashOf("bb2"), message = "docs 정리")
        val third = commitOf(id = hashOf("cc3"), message = "fix LOGIN redirect")

        coEvery { historyGateway.load(REFS, 0, PAGE_SIZE) } returns listOf(first, second)
        coEvery { historyGateway.load(REFS, PAGE_SIZE, PAGE_SIZE) } returns listOf(third)

        When("첫 결과만 받고 수집을 멈추면") {
            val found = useCase.execute(REFS, CommitSearchCriteria(message = "login"))
                .filterIsInstance<SearchProgress.Match>()
                .take(1)
                .map { match -> match.commit }
                .toList()

            Then("남은 페이지를 조회하지 않고 끝난다 — 전체 순회 전에 결과가 나온다") {
                found shouldContainExactly listOf(first)
                coVerify(exactly = 0) { historyGateway.load(REFS, PAGE_SIZE, PAGE_SIZE) }
            }
        }
    }

    Given("경로 필터가 있는 검색") {
        val historyGateway = mockk<HistoryGateway>()
        val diffGateway = mockk<DiffGateway>()
        val useCase = SearchCommitsUseCase(historyGateway, diffGateway, pageSize = PAGE_SIZE)

        val touched = commitOf(id = hashOf("dd4"), message = "fix parser")
        val untouched = commitOf(id = hashOf("ee5"), message = "fix parser docs")
        val unrelated = commitOf(id = hashOf("ff6"), message = "무관한 커밋")

        coEvery { historyGateway.load(REFS, 0, PAGE_SIZE) } returns listOf(touched, untouched)
        coEvery { historyGateway.load(REFS, PAGE_SIZE, PAGE_SIZE) } returns listOf(unrelated)
        coEvery { diffGateway.changedFiles(touched.id, 0) } returns listOf(fileChange("src/main/Parser.kt"))
        coEvery { diffGateway.changedFiles(untouched.id, 0) } returns listOf(fileChange("docs/parser.md"))

        When("경로를 건드린 커밋만 남기면") {
            val found = useCase
                .execute(REFS, CommitSearchCriteria(message = "parser", filePath = "src/main"))
                .matchedCommits()

            Then("첫 부모(parentIndex 0) 기준 변경 파일로 판정한다") {
                found shouldContainExactly listOf(touched)
                coVerify(exactly = 1) { diffGateway.changedFiles(touched.id, 0) }
            }

            Then("메시지 조건에서 이미 탈락한 커밋은 diff 를 계산하지 않는다") {
                coVerify(exactly = 0) { diffGateway.changedFiles(unrelated.id, any()) }
            }
        }
    }

    Given("세 페이지에 걸친 이력") {
        val historyGateway = mockk<HistoryGateway>()
        val diffGateway = mockk<DiffGateway>()
        val useCase = SearchCommitsUseCase(historyGateway, diffGateway, pageSize = PAGE_SIZE)

        coEvery { historyGateway.load(REFS, 0, PAGE_SIZE) } returns
            listOf(commitOf(id = hashOf("aa1")), commitOf(id = hashOf("bb2")))
        coEvery { historyGateway.load(REFS, PAGE_SIZE, PAGE_SIZE) } returns
            listOf(commitOf(id = hashOf("cc3")), commitOf(id = hashOf("dd4")))
        coEvery { historyGateway.load(REFS, PAGE_SIZE * 2, PAGE_SIZE) } returns
            listOf(commitOf(id = hashOf("ee5")))

        When("끝까지 순회하면") {
            val scanned = useCase.execute(REFS, CommitSearchCriteria(message = "없는 커밋"))
                .filterIsInstance<SearchProgress.Scanned>()
                .toList()

            Then("페이지를 넘길 때마다 훑은 양이 늘어난 진행 사건이 나온다") {
                scanned shouldContainExactly listOf(
                    SearchProgress.Scanned(scannedCommits = 2, estimatedTotalCommits = 4),
                    SearchProgress.Scanned(scannedCommits = 4, estimatedTotalCommits = 6),
                )
            }

            Then("마지막 페이지는 진행 사건을 내지 않는다 — 끝은 흐름의 종료가 알린다") {
                scanned.map { progress -> progress.scannedCommits } shouldContainExactly listOf(2, 4)
            }
        }
    }

    Given("빈 이력") {
        val historyGateway = mockk<HistoryGateway>()
        val diffGateway = mockk<DiffGateway>()
        val useCase = SearchCommitsUseCase(historyGateway, diffGateway, pageSize = PAGE_SIZE)

        coEvery { historyGateway.load(REFS, 0, PAGE_SIZE) } returns emptyList<Commit>()

        When("검색하면") {
            val found = useCase.execute(REFS, CommitSearchCriteria(message = "login")).matchedCommits()

            Then("예외 없이 0건으로 끝난다") {
                found.shouldBeEmpty()
            }
        }
    }

    Given("이력 조회가 실패하는 저장소") {
        val historyGateway = mockk<HistoryGateway>()
        val diffGateway = mockk<DiffGateway>()
        val useCase = SearchCommitsUseCase(historyGateway, diffGateway, pageSize = PAGE_SIZE)

        coEvery { historyGateway.load(REFS, 0, PAGE_SIZE) } throws
            UndineException.NotFound(UndineException.NotFound.Kind.REF, "refs/heads/main")

        When("검색하면") {
            Then("빈 결과로 바뀌지 않고 실패가 그대로 전파된다") {
                shouldThrow<UndineException.NotFound> {
                    useCase.execute(REFS, CommitSearchCriteria(message = "login")).matchedCommits()
                }
            }
        }
    }

    Given("diff 조회가 실패하는 저장소") {
        val historyGateway = mockk<HistoryGateway>()
        val diffGateway = mockk<DiffGateway>()
        val useCase = SearchCommitsUseCase(historyGateway, diffGateway, pageSize = PAGE_SIZE)

        val commit = commitOf(id = hashOf("ab7"), message = "fix parser")
        coEvery { historyGateway.load(REFS, 0, PAGE_SIZE) } returns listOf(commit)
        coEvery { diffGateway.changedFiles(any<CommitId>(), any()) } throws
            UndineException.GitOperationFailed("changedFiles")

        When("경로 필터로 검색하면") {
            Then("실패가 그대로 전파된다") {
                shouldThrow<UndineException.GitOperationFailed> {
                    useCase
                        .execute(REFS, CommitSearchCriteria(message = "parser", filePath = "src"))
                        .matchedCommits()
                }
            }
        }
    }
})
