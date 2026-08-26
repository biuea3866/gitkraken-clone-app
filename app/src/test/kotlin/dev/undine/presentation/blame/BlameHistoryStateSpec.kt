package dev.undine.presentation.blame

import dev.undine.application.blame.CompareFileHistoryUseCase
import dev.undine.application.blame.LoadBlameUseCase
import dev.undine.application.blame.LoadFileHistoryUseCase
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.DiffResult
import dev.undine.domain.FileChange
import dev.undine.domain.FileComparison
import dev.undine.domain.Person
import dev.undine.domain.UndineException
import dev.undine.domain.blame.BlameGateway
import dev.undine.domain.blame.BlameLine
import dev.undine.domain.blame.BlameResult
import dev.undine.domain.blame.FileHistoryEntry
import dev.undine.domain.blame.LineRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val PATH = "src/main.kt"
private val FIRST = commitOf("a")
private val SECOND = commitOf("b", parents = listOf(FIRST.id))

/**
 * Blame·파일 이력 상태 홀더.
 *
 * 진행 중 재호출을 검증하는 테스트는 [Dispatchers.Unconfined] 를 쓴다 — `launch` 가 호출 지점에서
 * 그대로 실행돼 게이트에서 멈추므로, 두 번째 호출이 "진행 중" 을 확실히 만난다.
 */
class BlameHistoryStateSpec : FunSpec({

    test("초기 가시 범위를 읽고 스크롤 확장은 읽지 않은 구간만 이어 요청한다") {
        val gateway = FakeBlameGateway()
        val state = stateFor(gateway)

        state.loadBlame(PATH, LineRange.of(1, 2))
        state.loadNextLineRange()

        gateway.blameRequests.map { it.range } shouldContainExactly
            listOf(LineRange.of(1, 2), LineRange.of(3, 202))
        state.blame.shouldBeInstanceOf<BlameUiState.Loaded>().lines.map { it.line } shouldContainExactly
            listOf(1, 2, 3, 4)
    }

    test("이어 읽는 동안에도 이미 보여 준 줄을 지우지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeBlameGateway()
        val state = stateFor(gateway)
        state.loadBlame(PATH, LineRange.of(1, 2))

        val scope = CoroutineScope(Dispatchers.Unconfined)
        gateway.gate = gate
        scope.launch { state.loadNextLineRange() }

        // 확장 요청이 게이트에 걸린 동안에도 목록이 남아 있어야 스크롤 위치를 잃지 않는다.
        state.blame.shouldBeInstanceOf<BlameUiState.Loaded>().lines.map { it.line } shouldContainExactly listOf(1, 2)
        gate.complete(Unit)
        state.blame.shouldBeInstanceOf<BlameUiState.Loaded>().lines shouldHaveSize 4
    }

    test("요청한 만큼 못 받으면 파일 끝으로 보고 추가 요청을 멈춘다") {
        val gateway = FakeBlameGateway()
        val state = stateFor(gateway)

        state.loadBlame(PATH, LineRange.of(1, 2))
        state.loadNextLineRange()
        // 두 번째 확장은 파일 끝이 확인된 뒤라 gateway 를 부르지 않는다.
        state.loadNextLineRange()

        gateway.blameRequests shouldHaveSize 2
    }

    test("파일 이력 없이도 blame 행이 커밋 메타데이터와 재귀 기준을 갖는다") {
        // 이력은 limit 에 걸리거나 실패할 수 있다 — 그때도 상대 시각·상세 이동·재귀가 살아 있어야 한다.
        val gateway = FakeBlameGateway(history = emptyList())
        val state = stateFor(gateway)

        state.loadBlame(PATH, LineRange.of(1, 4))

        val lines = state.blame.shouldBeInstanceOf<BlameUiState.Loaded>().lines
        val second = lines.first { it.commit.id == SECOND.id }
        second.commit.committedAt shouldBe SECOND.committedAt
        second.commit.parents shouldContainExactly listOf(FIRST.id)

        state.recurseBefore(second.commit)
        gateway.blameRequests.last().at shouldBe FIRST.id
    }

    test("연속한 같은 커밋은 gutter를 첫 행에만 표시한다") {
        val rows = blameRowsOf(
            listOf(line(1, FIRST), line(2, FIRST), line(3, SECOND), line(4, SECOND), line(5, FIRST)),
        )

        rows.map { it.showCommitGutter } shouldContainExactly listOf(true, false, true, false, true)
    }

    test("공백 무시 토글은 같은 요청을 즉시 새 옵션으로 다시 읽는다") {
        val gateway = FakeBlameGateway()
        val state = stateFor(gateway)
        state.loadBlame(PATH, LineRange.of(1, 2))

        state.setIgnoreWhitespace(true)

        gateway.blameRequests.map { it.ignoreWhitespace } shouldContainExactly listOf(false, true)
        state.ignoreWhitespace shouldBe true
    }

    test("선택 커밋 이전으로 재귀하면 첫 부모를 기준 커밋으로 다시 blame 한다") {
        val gateway = FakeBlameGateway()
        val state = stateFor(gateway)
        state.loadBlame(PATH, LineRange.of(1, 2))

        state.recurseBefore(SECOND)

        gateway.blameRequests.last().at shouldBe FIRST.id
    }

    test("rename 메타데이터와 두 시점 diff 결과를 상태로 보존한다") {
        val gateway = FakeBlameGateway(
            history = listOf(
                FileHistoryEntry(SECOND, "renamed.kt", "original.kt"),
                FileHistoryEntry(FIRST, "original.kt"),
            ),
        )
        val diffGateway = FakeDiffGateway()
        val state = stateFor(gateway, diffGateway)

        state.loadHistory(PATH)
        state.compareHistoryEntries(
            before = FileHistoryEntry(FIRST, "original.kt"),
            after = FileHistoryEntry(SECOND, "renamed.kt", "original.kt"),
        )

        state.history.shouldBeInstanceOf<FileHistoryUiState.Loaded>().entries.first().isRename shouldBe true
        diffGateway.comparisons.single() shouldBe FileComparison(FIRST.id, "original.kt", SECOND.id, "renamed.kt")
        state.comparison shouldBe DiffUiState.Loaded(DiffResult.Computed(emptyList()))
    }

    test("이력의 두 항목을 차례로 선택하면 비교하고 선택 상태를 비운다") {
        val diffGateway = FakeDiffGateway()
        val state = stateFor(FakeBlameGateway(), diffGateway)
        val before = FileHistoryEntry(FIRST, "original.kt")
        val after = FileHistoryEntry(SECOND, "renamed.kt", "original.kt")

        state.selectHistoryEntry(before)
        state.pendingComparisonEntry shouldBe before
        state.selectHistoryEntry(after)

        state.pendingComparisonEntry shouldBe null
        diffGateway.comparisons.single() shouldBe FileComparison(FIRST.id, "original.kt", SECOND.id, "renamed.kt")
    }

    test("미지원 blame와 gateway 실패를 빈 결과로 바꾸지 않는다") {
        val unsupported = stateFor(FakeBlameGateway(result = BlameResult.Unsupported))
        unsupported.loadBlame(PATH, LineRange.of(1, 1))
        unsupported.blame shouldBe BlameUiState.Unsupported

        val failed = stateFor(
            FakeBlameGateway(
                failure = UndineException.NotFound(UndineException.NotFound.Kind.PATH, PATH),
            ),
        )
        failed.loadBlame(PATH, LineRange.of(1, 1))
        failed.blame.shouldBeInstanceOf<BlameUiState.Failed>().failure.shouldBeInstanceOf<UndineException.NotFound>()
    }

    test("줄이 하나도 없는 blame 결과는 실패가 아니라 빈 Loaded 다") {
        val state = stateFor(FakeBlameGateway(result = BlameResult.Lines(emptyList())))

        state.loadBlame(PATH, LineRange.of(1, 10))

        state.blame.shouldBeInstanceOf<BlameUiState.Loaded>().lines.shouldBeEmpty()
    }

    test("파일 이력 조회 실패는 빈 이력이 아니라 오류 상태로 남는다") {
        val state = stateFor(
            FakeBlameGateway(failure = UndineException.NotFound(UndineException.NotFound.Kind.PATH, PATH)),
        )

        state.loadHistory(PATH)

        state.history.shouldBeInstanceOf<FileHistoryUiState.Failed>()
            .failure.shouldBeInstanceOf<UndineException.NotFound>()
    }

    test("두 시점 diff 실패는 빈 diff 가 아니라 오류 상태로 남는다") {
        val state = stateFor(
            FakeBlameGateway(),
            FakeDiffGateway(failure = UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, "b".repeat(40))),
        )

        state.compareHistoryEntries(FileHistoryEntry(FIRST, PATH), FileHistoryEntry(SECOND, PATH))

        state.comparison.shouldBeInstanceOf<DiffUiState.Failed>()
            .failure.shouldBeInstanceOf<UndineException.NotFound>()
    }

    test("취소는 전파하고 취소된 조회 결과로 상태를 갱신하지 않는다") {
        val state = stateFor(FakeBlameGateway(cancel = true))

        shouldThrow<CancellationException> { state.loadBlame(PATH, LineRange.of(1, 1)) }

        state.blame shouldBe BlameUiState.Loading
    }

    test("파일 이력·두 시점 diff 의 취소도 삼키지 않고 전파한다") {
        val historyState = stateFor(FakeBlameGateway(cancel = true))
        shouldThrow<CancellationException> { historyState.loadHistory(PATH) }
        historyState.history shouldBe FileHistoryUiState.Loading

        val diffState = stateFor(FakeBlameGateway(), FakeDiffGateway(cancel = true))
        shouldThrow<CancellationException> {
            diffState.compareHistoryEntries(FileHistoryEntry(FIRST, PATH), FileHistoryEntry(SECOND, PATH))
        }
        diffState.comparison shouldBe DiffUiState.Loading
    }

    test("진행 중인 blame 조회는 같은 로더의 재호출을 무시한다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeBlameGateway().also { it.gate = gate }
        val state = stateFor(gateway)
        val scope = CoroutineScope(Dispatchers.Unconfined)

        scope.launch { state.loadBlame(PATH, LineRange.of(1, 2)) }
        state.loadBlame(PATH, LineRange.of(1, 4))

        gateway.blameRequests shouldHaveSize 1
        gate.complete(Unit)
        state.blame.shouldBeInstanceOf<BlameUiState.Loaded>()
    }

    test("진행 중인 파일 이력 조회는 재호출을 무시한다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeBlameGateway().also { it.gate = gate }
        val state = stateFor(gateway)
        val scope = CoroutineScope(Dispatchers.Unconfined)

        scope.launch { state.loadHistory(PATH) }
        state.loadHistory(PATH)

        gateway.historyCalls shouldBe 1
        gate.complete(Unit)
        state.history.shouldBeInstanceOf<FileHistoryUiState.Loaded>()
    }

    test("진행 중인 두 시점 diff 는 재호출을 무시한다") {
        val gate = CompletableDeferred<Unit>()
        val diffGateway = FakeDiffGateway().also { it.gate = gate }
        val state = stateFor(FakeBlameGateway(), diffGateway)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val before = FileHistoryEntry(FIRST, "original.kt")
        val after = FileHistoryEntry(SECOND, "renamed.kt", "original.kt")

        scope.launch { state.compareHistoryEntries(before, after) }
        state.compareHistoryEntries(after, before)

        diffGateway.comparisons shouldHaveSize 1
        gate.complete(Unit)
        state.comparison.shouldBeInstanceOf<DiffUiState.Loaded>()
    }
})

private fun stateFor(
    blameGateway: FakeBlameGateway,
    diffGateway: FakeDiffGateway = FakeDiffGateway(),
): BlameHistoryState = BlameHistoryState(
    loadBlame = LoadBlameUseCase(blameGateway),
    loadHistory = LoadFileHistoryUseCase(blameGateway),
    compareHistory = CompareFileHistoryUseCase(diffGateway),
)

private data class RecordedBlameRequest(
    val range: LineRange,
    val ignoreWhitespace: Boolean,
    val at: CommitId?,
)

private class FakeBlameGateway(
    private val result: BlameResult = BlameResult.Lines(
        listOf(line(1, FIRST), line(2, FIRST), line(3, SECOND), line(4, SECOND)),
    ),
    private val history: List<FileHistoryEntry> = emptyList(),
    private val failure: UndineException? = null,
    private val cancel: Boolean = false,
) : BlameGateway {
    val blameRequests = mutableListOf<RecordedBlameRequest>()
    var historyCalls = 0
        private set

    /** 응답을 붙잡아 두는 게이트 — 진행 중 재호출이 억제되는지 보는 테스트가 채운다. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun blame(
        path: String,
        range: LineRange,
        ignoreWhitespace: Boolean,
        at: CommitId?,
    ): BlameResult {
        blameRequests += RecordedBlameRequest(range, ignoreWhitespace, at)
        gate?.await()
        if (cancel) throw CancellationException("test cancellation")
        failure?.let { throw it }
        return result.let { response ->
            if (response is BlameResult.Lines) {
                BlameResult.Lines(response.lines.filter { it.line in range.start..range.end })
            } else {
                response
            }
        }
    }

    override suspend fun fileHistory(path: String, at: CommitId?, limit: Int): List<FileHistoryEntry> {
        historyCalls += 1
        gate?.await()
        if (cancel) throw CancellationException("test cancellation")
        failure?.let { throw it }
        return history
    }
}

private class FakeDiffGateway(
    private val failure: UndineException? = null,
    private val cancel: Boolean = false,
) : DiffGateway {
    val comparisons = mutableListOf<FileComparison>()

    /** [FakeBlameGateway.gate] 와 같은 목적이다. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun changedFiles(commit: CommitId, parentIndex: Int): List<FileChange> = emptyList()
    override suspend fun changedFilesStaged(): List<FileChange> = emptyList()
    override suspend fun changedFilesUnstaged(): List<FileChange> = emptyList()
    override suspend fun hunksOf(
        commit: CommitId,
        path: String,
        parentIndex: Int,
    ): DiffResult = DiffResult.Computed(emptyList())

    override suspend fun hunksBetween(comparison: FileComparison): DiffResult {
        comparisons += comparison
        gate?.await()
        if (cancel) throw CancellationException("test cancellation")
        failure?.let { throw it }
        return DiffResult.Computed(emptyList())
    }
}

private fun line(number: Int, commit: Commit): BlameLine =
    BlameLine(number, number, commit, commit.author, "line $number")

private fun commitOf(id: String, parents: List<CommitId> = emptyList()): Commit = Commit(
    id = CommitId.of(id.repeat(40)),
    parents = parents,
    message = "commit $id",
    author = Person("Author $id", "$id@example.invalid"),
    committer = Person("Author $id", "$id@example.invalid"),
    authoredAt = Instant.EPOCH,
    committedAt = Instant.EPOCH,
)
