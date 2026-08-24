package dev.undine.presentation.search

import dev.undine.application.search.SearchCommitsUseCase
import dev.undine.application.search.SearchProgress
import dev.undine.domain.Commit
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.search.CommitSearchCriteria
import dev.undine.domain.search.SEARCH_ZONE
import dev.undine.domain.search.commitOf
import dev.undine.domain.search.hashOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.LocalDate

private val REFS = listOf(RefName("refs/heads/main"))

private val FIRST = commitOf(id = hashOf("aa1"), message = "fix login timeout")
private val SECOND = commitOf(id = hashOf("bb2"), message = "fix login redirect")

/** 검색 코루틴이 테스트 스레드에서 그대로 진행되도록 즉시 실행 스코프를 쓴다. */
private fun immediateScope() = CoroutineScope(Dispatchers.Unconfined)

private fun stateWith(
    useCase: SearchCommitsUseCase,
    scope: CoroutineScope = immediateScope(),
): SearchState = SearchState(searchCommits = useCase, scope = scope, refs = REFS, zone = SEARCH_ZONE)

/** 채널이 열려 있는 동안 검색이 끝나지 않는 흐름 — 진행 중 상태를 관찰하기 위해서다. */
private fun openFlowOf(channel: Channel<SearchProgress>): Flow<SearchProgress> = channel.receiveAsFlow()

/** 순회가 커밋 하나를 찾았다. */
private fun Channel<SearchProgress>.sendMatch(commit: Commit) =
    trySend(SearchProgress.Match(commit))

/** Unconfined 스코프에서 방출이 수집기까지 도달하도록 한 틱 양보한다. */
private fun settle() = runBlocking { yield() }

/** 검색 화면 상태 홀더 — 조건 결합·점진 추가·취소·실패 표시. */
class SearchStateSpec : FunSpec({

    test("조건이 하나도 없으면 검색을 시작하지 않는다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val state = stateWith(useCase)

        state.phase shouldBe SearchPhase.Idle

        state.updateQuery(SearchField.MESSAGE, "   ")

        state.phase shouldBe SearchPhase.Idle
        state.results.shouldBeEmpty()
        verify(exactly = 0) { useCase.execute(any(), any()) }
    }

    test("결과는 전체 순회가 끝나기 전에 하나씩 추가된다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val channel = Channel<SearchProgress>(Channel.UNLIMITED)
        every { useCase.execute(REFS, CommitSearchCriteria(message = "fix", zone = SEARCH_ZONE)) } returns
            openFlowOf(channel)
        val state = stateWith(useCase)

        state.updateQuery(SearchField.MESSAGE, "fix")
        state.phase shouldBe SearchPhase.Running

        channel.sendMatch(FIRST)
        settle()

        state.results shouldContainExactly listOf(FIRST)
        state.phase shouldBe SearchPhase.Running

        channel.sendMatch(SECOND)
        settle()

        state.results shouldContainExactly listOf(FIRST, SECOND)

        channel.close()
        settle()

        state.phase shouldBe SearchPhase.Completed
    }

    test("결과 0건과 검색 진행 중이 구분된다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val channel = Channel<SearchProgress>(Channel.UNLIMITED)
        every { useCase.execute(any(), any()) } returns openFlowOf(channel)
        val state = stateWith(useCase)

        state.updateQuery(SearchField.MESSAGE, "없는 커밋")

        state.phase shouldBe SearchPhase.Running
        state.results.shouldBeEmpty()

        channel.close()
        settle()

        state.phase shouldBe SearchPhase.Completed
        state.results.shouldBeEmpty()
    }

    test("순회 진행률은 페이지를 넘길수록 커지고 검색을 다시 시작하면 0 으로 돌아간다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val channel = Channel<SearchProgress>(Channel.UNLIMITED)
        every { useCase.execute(REFS, CommitSearchCriteria(message = "fix", zone = SEARCH_ZONE)) } returns
            openFlowOf(channel)
        every { useCase.execute(REFS, CommitSearchCriteria(message = "fixed", zone = SEARCH_ZONE)) } returns
            openFlowOf(Channel(Channel.UNLIMITED))
        val state = stateWith(useCase)

        state.updateQuery(SearchField.MESSAGE, "fix")

        // 첫 페이지를 훑기 전에는 훑은 양이 없다.
        state.scanProgress shouldBe 0f

        channel.trySend(SearchProgress.Scanned(scannedCommits = 200, estimatedTotalCommits = 400))
        settle()
        val afterFirstPage = state.scanProgress
        afterFirstPage shouldBe 0.5f

        channel.trySend(SearchProgress.Scanned(scannedCommits = 400, estimatedTotalCommits = 600))
        settle()
        state.scanProgress shouldBeGreaterThan afterFirstPage

        state.updateQuery(SearchField.MESSAGE, "fixed")

        state.scanProgress shouldBe 0f
    }

    test("취소된 검색의 늦은 진행 사건은 새 검색의 진행률을 건드리지 않는다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val staleChannel = Channel<SearchProgress>(Channel.UNLIMITED)
        val freshChannel = Channel<SearchProgress>(Channel.UNLIMITED)
        every { useCase.execute(REFS, CommitSearchCriteria(message = "fix", zone = SEARCH_ZONE)) } returns
            openFlowOf(staleChannel)
        every { useCase.execute(REFS, CommitSearchCriteria(message = "fix login", zone = SEARCH_ZONE)) } returns
            openFlowOf(freshChannel)
        val state = stateWith(useCase)

        state.updateQuery(SearchField.MESSAGE, "fix")
        state.updateQuery(SearchField.MESSAGE, "fix login")

        staleChannel.trySend(SearchProgress.Scanned(scannedCommits = 900, estimatedTotalCommits = 1000))
        settle()

        state.scanProgress shouldBe 0f
    }

    test("검색어를 바꾸면 이전 검색이 취소되고 늦게 도착한 결과가 화면에 남지 않는다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val staleChannel = Channel<SearchProgress>(Channel.UNLIMITED)
        val freshChannel = Channel<SearchProgress>(Channel.UNLIMITED)
        every { useCase.execute(REFS, CommitSearchCriteria(message = "fix", zone = SEARCH_ZONE)) } returns
            openFlowOf(staleChannel)
        every { useCase.execute(REFS, CommitSearchCriteria(message = "fix login", zone = SEARCH_ZONE)) } returns
            openFlowOf(freshChannel)
        val state = stateWith(useCase)

        state.updateQuery(SearchField.MESSAGE, "fix")
        state.updateQuery(SearchField.MESSAGE, "fix login")

        // 취소된 검색의 늦은 결과다 — 새 검색 결과를 덮어쓰거나 뒤에 붙으면 안 된다.
        staleChannel.sendMatch(FIRST)
        settle()

        state.results.shouldBeEmpty()

        freshChannel.sendMatch(SECOND)
        settle()

        state.results shouldContainExactly listOf(SECOND)
    }

    test("검색 실패는 0건이 아니라 실패 상태로 표시된다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val failure = UndineException.NotFound(UndineException.NotFound.Kind.REF, "refs/heads/main")
        every { useCase.execute(any(), any()) } returns flow { throw failure }
        val state = stateWith(useCase)

        state.updateQuery(SearchField.MESSAGE, "fix")

        state.phase.shouldBeInstanceOf<SearchPhase.Failed>().cause shouldBe failure
        state.results.shouldBeEmpty()
    }

    test("조건을 모두 지우면 결과를 비우고 검색을 멈춘다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val channel = Channel<SearchProgress>(Channel.UNLIMITED)
        every { useCase.execute(any(), any()) } returns openFlowOf(channel)
        val state = stateWith(useCase)

        state.updateQuery(SearchField.MESSAGE, "fix")
        channel.sendMatch(FIRST)
        settle()
        state.results shouldContainExactly listOf(FIRST)

        state.clearFilters()

        state.queryOf(SearchField.MESSAGE) shouldBe ""
        state.phase shouldBe SearchPhase.Idle
        state.results.shouldBeEmpty()
    }

    test("기간 조건은 입력 문자열을 날짜로 읽어 조건에 넣는다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val expected = CommitSearchCriteria(
            since = LocalDate.of(2026, 3, 10),
            until = LocalDate.of(2026, 3, 12),
            zone = SEARCH_ZONE,
        )
        every { useCase.execute(REFS, any()) } returns openFlowOf(Channel(Channel.UNLIMITED))
        val state = stateWith(useCase)

        state.updateQuery(SearchField.SINCE, "2026-03-10")
        state.updateQuery(SearchField.UNTIL, "2026-03-12")

        state.isInvalid(SearchField.SINCE) shouldBe false
        verify { useCase.execute(REFS, expected) }
    }

    test("날짜 형식이 잘못되면 그 축을 조건에서 빼고 잘못된 입력임을 알린다") {
        val useCase = mockk<SearchCommitsUseCase>()
        every { useCase.execute(REFS, any()) } returns openFlowOf(Channel(Channel.UNLIMITED))
        val state = stateWith(useCase)

        state.updateQuery(SearchField.MESSAGE, "fix")
        state.updateQuery(SearchField.SINCE, "2026-13-99")

        state.isInvalid(SearchField.SINCE) shouldBe true
        verify { useCase.execute(REFS, CommitSearchCriteria(message = "fix", zone = SEARCH_ZONE)) }
    }

    test("하이라이트는 결과 범위를 벗어나지 않는다") {
        val useCase = mockk<SearchCommitsUseCase>()
        val channel = Channel<SearchProgress>(Channel.UNLIMITED)
        every { useCase.execute(any(), any()) } returns openFlowOf(channel)
        val state = stateWith(useCase)

        state.highlightedCommit shouldBe null

        state.updateQuery(SearchField.MESSAGE, "fix")
        channel.sendMatch(FIRST)
        channel.sendMatch(SECOND)
        settle()

        // 첫 결과가 도착하면 바로 키보드로 열 수 있어야 한다.
        state.highlightedCommit shouldBe FIRST

        state.moveHighlightBy(1)
        state.highlightedCommit shouldBe SECOND

        state.moveHighlightBy(5)
        state.highlightedCommit shouldBe SECOND

        state.moveHighlightBy(-5)
        state.highlightedCommit shouldBe FIRST
    }
})
