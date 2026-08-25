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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import kotlinx.coroutines.CancellationException

private const val PATH = "src/main.kt"
private val FIRST = commitOf("a")
private val SECOND = commitOf("b", parents = listOf(FIRST.id))

class BlameHistoryStateSpec : FunSpec({

    test("초기 가시 범위를 읽고 스크롤 확장은 새 범위만 요청한다") {
        val gateway = FakeBlameGateway()
        val state = stateFor(gateway)

        state.loadBlame(PATH, LineRange.of(1, 2))
        state.expandVisibleRange(LineRange.of(3, 4))

        gateway.blameRequests.map { it.range } shouldContainExactly
            listOf(LineRange.of(1, 2), LineRange.of(3, 4))
        state.blame.shouldBeInstanceOf<BlameUiState.Loaded>().lines.map { it.line } shouldContainExactly
            listOf(1, 2, 3, 4)
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

    test("취소는 전파하고 취소된 조회 결과로 상태를 갱신하지 않는다") {
        val state = stateFor(FakeBlameGateway(cancel = true))

        shouldThrow<CancellationException> { state.loadBlame(PATH, LineRange.of(1, 1)) }

        state.blame shouldBe BlameUiState.Loading
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

    override suspend fun blame(
        path: String,
        range: LineRange,
        ignoreWhitespace: Boolean,
        at: CommitId?,
    ): BlameResult {
        blameRequests += RecordedBlameRequest(range, ignoreWhitespace, at)
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

    override suspend fun fileHistory(path: String, at: CommitId?, limit: Int): List<FileHistoryEntry> = history
}

private class FakeDiffGateway : DiffGateway {
    val comparisons = mutableListOf<FileComparison>()
    override suspend fun changedFiles(commit: CommitId, parentIndex: Int): List<FileChange> = emptyList()
    override suspend fun changedFilesStaged(): List<FileChange> = emptyList()
    override suspend fun changedFilesUnstaged(): List<FileChange> = emptyList()
    override suspend fun hunksOf(
        commit: CommitId,
        path: String,
        parentIndex: Int,
    ): DiffResult = DiffResult.Computed(emptyList())
    override suspend fun hunksBetween(comparison: FileComparison): DiffResult =
        DiffResult.Computed(emptyList()).also { comparisons += comparison }
}

private fun line(number: Int, commit: Commit): BlameLine =
    BlameLine(number, number, commit.id, commit.author, "line $number")

private fun commitOf(id: String, parents: List<CommitId> = emptyList()): Commit = Commit(
    id = CommitId.of(id.repeat(40)),
    parents = parents,
    message = "commit $id",
    author = Person("Author $id", "$id@example.invalid"),
    committer = Person("Author $id", "$id@example.invalid"),
    authoredAt = Instant.EPOCH,
    committedAt = Instant.EPOCH,
)
