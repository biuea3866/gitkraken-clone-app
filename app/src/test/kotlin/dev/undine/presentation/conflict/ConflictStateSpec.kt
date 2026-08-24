package dev.undine.presentation.conflict

import dev.undine.application.conflict.AbortConflictedOperationUseCase
import dev.undine.application.conflict.ContinueAfterResolveUseCase
import dev.undine.application.conflict.LoadConflictContentUseCase
import dev.undine.application.conflict.LoadConflictedFilesUseCase
import dev.undine.application.conflict.ResolveConflictUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase
import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.FileChange
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.conflict.ConflictChoice
import dev.undine.domain.conflict.ConflictGateway
import dev.undine.domain.conflict.ConflictSide
import dev.undine.domain.conflict.ConflictedFile
import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeGateway
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.merge.SkipConfirmation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private const val TEXT_FILE = "shared.txt"
private const val BINARY = "logo.bin"

private val TWO_REGION_CONTENT = """
    앞
    <<<<<<< HEAD
    첫 구간 우리
    =======
    첫 구간 저쪽
    >>>>>>> feature
    사이
    <<<<<<< HEAD
    둘째 구간 우리
    =======
    둘째 구간 저쪽
    >>>>>>> feature
""".trimIndent()

private val CONFLICTED_CONTENT = """
    앞
    <<<<<<< HEAD
    우리 줄
    =======
    저쪽 줄
    >>>>>>> feature
""".trimIndent()

/** 충돌 에디터 상태 — 진행률·표식 잔존 차단·이진 선택·중단 2단계 확인. */
class ConflictStateSpec : FunSpec({

    test("충돌 파일 목록을 읽고 없으면 빈 상태다") {
        val state = stateWith(FakeConflictGateway())
        state.refresh()
        state.isClean shouldBe true

        val withFiles = stateWith(FakeConflictGateway(files = listOf(ConflictedFile(TEXT_FILE, isBinary = false))))
        withFiles.refresh()

        withFiles.isClean shouldBe false
        withFiles.files.map { it.path } shouldContainExactly listOf(TEXT_FILE)
    }

    test("파일을 고르면 표식이 든 내용을 문서로 읽고 구간 수를 센다") {
        val state = stateWith(textConflictGateway())
        state.refresh()

        state.select(TEXT_FILE)

        state.regionCount shouldBe 1
        state.resolvedRegionCount shouldBe 0
        state.canSave shouldBe false
    }

    test("구간을 해결하면 진행률이 올라가고 저장이 열린다") {
        val state = stateWith(textConflictGateway())
        state.refresh()
        state.select(TEXT_FILE)

        state.choose(0, ConflictChoice.Take(ConflictSide.OURS))

        state.resolvedRegionCount shouldBe 1
        state.regionCount shouldBe 1
        state.canSave shouldBe true
    }

    test("직접 편집한 내용이 결과에 들어간다") {
        val conflict = textConflictGateway()
        val state = stateWith(conflict)
        state.refresh()
        state.select(TEXT_FILE)

        state.editRegion(0, "합친 줄")
        state.save()

        conflict.resolved shouldContainExactly listOf(TEXT_FILE to "앞\n합친 줄")
    }

    test("표식이 남은 채로 저장하면 차단되고 남은 줄 번호가 표시된다") {
        val conflict = textConflictGateway()
        val state = stateWith(conflict)
        state.refresh()
        state.select(TEXT_FILE)

        state.save()

        // 표식이 든 채 스테이징되면 그대로 커밋되어 소스에 박힌다.
        state.blockedMarkerLines shouldContainExactly listOf(2, 4, 6)
        conflict.resolved.shouldBeEmpty()
    }

    test("사용자가 편집으로 표식을 다시 써 넣어도 차단한다") {
        val conflict = textConflictGateway()
        val state = stateWith(conflict)
        state.refresh()
        state.select(TEXT_FILE)

        state.editRegion(0, "<<<<<<< 실수")
        state.save()

        state.blockedMarkerLines shouldContainExactly listOf(2)
        conflict.resolved.shouldBeEmpty()
    }

    test("저장하면 해결된 파일로 표시되고 목록에서 빠진다") {
        val conflict = textConflictGateway()
        val state = stateWith(conflict)
        state.refresh()
        state.select(TEXT_FILE)
        state.choose(0, ConflictChoice.Take(ConflictSide.OURS))

        state.save()

        state.resolvedPaths shouldContainExactly setOf(TEXT_FILE)
        // 해결된 파일은 목록에서 빠지므로 열어 둔 선택도 비운다.
        state.files.shouldBeEmpty()
        state.selectedPath.shouldBeNull()
    }

    test("이진 파일은 문서를 만들지 않고 한쪽 선택만 반영한다") {
        val conflict = FakeConflictGateway(files = listOf(ConflictedFile(BINARY, isBinary = true)))
        val state = stateWith(conflict)
        state.refresh()

        state.select(BINARY)
        state.document.shouldBeNull()

        state.resolveBinary(ConflictSide.THEIRS)

        conflict.resolvedBinary shouldContainExactly listOf(BINARY to ConflictSide.THEIRS)
    }

    test("파일이 여러 개면 각각 따로 해결한다") {
        val conflict = FakeConflictGateway(
            files = listOf(ConflictedFile("a.txt", false), ConflictedFile("b.txt", false)),
            contents = mapOf("a.txt" to CONFLICTED_CONTENT, "b.txt" to CONFLICTED_CONTENT),
            keepFilesAfterResolve = true,
        )
        val state = stateWith(conflict)
        state.refresh()

        state.select("a.txt")
        state.choose(0, ConflictChoice.Take(ConflictSide.OURS))
        state.save()

        state.resolvedPaths shouldContainExactly setOf("a.txt")
        state.files.map { it.path } shouldContainExactly listOf("a.txt", "b.txt")
    }

    test("중단은 요청만으로 되돌리지 않고 사라질 경로를 확인에 담는다") {
        val merge = RecordingMergeGateway()
        val state = stateWith(textConflictGateway(), merge = merge)
        state.refresh()

        state.requestAbort()

        state.abortConfirmation.shouldNotBeNull().discardedPaths shouldContainExactly listOf("edited.txt", TEXT_FILE)
        merge.aborted shouldBe false
    }

    test("확인하면 중단이 실행된다") {
        val merge = RecordingMergeGateway()
        val state = stateWith(textConflictGateway(), merge = merge)
        state.refresh()
        state.requestAbort()

        state.confirmAbort()

        merge.aborted shouldBe true
        state.abortConfirmation.shouldBeNull()
    }

    test("확인 뒤 편집이 늘면 실패가 아니라 갱신된 목록으로 다시 확인받는다") {
        val merge = RecordingMergeGateway(rejectAbort = true)
        val state = stateWith(textConflictGateway(), merge = merge)
        state.refresh()
        state.requestAbort()

        state.confirmAbort()

        // 사용자가 보지 않은 편집이 사라지는 것을 막는 거부다 — 화면도 같은 이유로 다시 묻는다.
        state.abortStale shouldBe true
        state.abortConfirmation.shouldNotBeNull()
        state.failure.shouldBeNull()
    }

    test("계속은 진행 중인 연산에 맞는 경로를 탄다") {
        val merge = RecordingMergeGateway(state = RepositoryState.REBASING)
        val rebasing = stateWith(textConflictGateway(), merge = merge, state = RepositoryState.REBASING)
        rebasing.refresh()

        rebasing.continueOperation()

        merge.calls shouldContainExactly listOf("continueRebase")
    }

    test("보는 구간을 옮기면 그 구간의 세 원본이 화면 재료가 된다") {
        val state = stateWith(
            FakeConflictGateway(
                files = listOf(ConflictedFile(TEXT_FILE, isBinary = false)),
                contents = mapOf(TEXT_FILE to TWO_REGION_CONTENT),
            ),
        )
        state.refresh()
        state.select(TEXT_FILE)

        state.regionCount shouldBe 2
        state.focusedRegion shouldBe 0
        state.focusedConflict.shouldNotBeNull().ours shouldContainExactly listOf("첫 구간 우리")

        state.focusRegion(1)

        state.focusedConflict.shouldNotBeNull().theirs shouldContainExactly listOf("둘째 구간 저쪽")

        // 범위를 벗어난 값은 무시한다 — 화면이 없는 구간을 그리려 하면 빈 패널이 된다.
        state.focusRegion(2)
        state.focusedRegion shouldBe 1
    }

    test("파일을 바꾸면 보던 구간 번호가 처음으로 돌아간다") {
        val other = "other.txt"
        val state = stateWith(
            FakeConflictGateway(
                files = listOf(
                    ConflictedFile(TEXT_FILE, isBinary = false),
                    ConflictedFile(other, isBinary = false),
                ),
                contents = mapOf(TEXT_FILE to TWO_REGION_CONTENT, other to CONFLICTED_CONTENT),
            ),
        )
        state.refresh()
        state.select(TEXT_FILE)
        state.focusRegion(1)

        state.select(other)

        state.focusedRegion shouldBe 0
    }

    test("진행 중이 아니면 계속이 상태 위반으로 남는다") {
        val merge = RecordingMergeGateway(state = RepositoryState.NORMAL)
        val normal = stateWith(textConflictGateway(), merge = merge, state = RepositoryState.NORMAL)
        normal.refresh()

        normal.continueOperation()

        normal.failure.shouldNotBeNull()
        merge.calls.shouldBeEmpty()
    }
})

private fun textConflictGateway() = FakeConflictGateway(
    files = listOf(ConflictedFile(TEXT_FILE, isBinary = false)),
    contents = mapOf(TEXT_FILE to CONFLICTED_CONTENT),
)

private fun stateWith(
    conflict: FakeConflictGateway,
    merge: RecordingMergeGateway = RecordingMergeGateway(),
    state: RepositoryState = RepositoryState.MERGING,
): ConflictState {
    val mergeService = MergeService(StatusOnlyRepositoryGateway(), merge)
    return ConflictState(
        actions = ConflictActions(
            loadFiles = LoadConflictedFilesUseCase(conflict),
            loadContent = LoadConflictContentUseCase(conflict),
            resolve = ResolveConflictUseCase(conflict),
            continueAfterResolve = ContinueAfterResolveUseCase(mergeService),
            abort = AbortConflictedOperationUseCase(mergeService),
            loadStatus = LoadWorkingTreeStatusUseCase(StatusOnlyRepositoryGateway()),
        ),
        repositoryState = { state },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )
}

/** 충돌 목록·내용·해결을 기록하는 대역. */
private class FakeConflictGateway(
    private val files: List<ConflictedFile> = emptyList(),
    private val contents: Map<String, String> = emptyMap(),
    private val keepFilesAfterResolve: Boolean = false,
) : ConflictGateway {

    val resolved = mutableListOf<Pair<String, String>>()
    val resolvedBinary = mutableListOf<Pair<String, ConflictSide>>()

    override suspend fun listConflicted(): List<ConflictedFile> =
        if (keepFilesAfterResolve) files else files.filterNot { it.path in resolvedPaths() }

    override suspend fun readConflicted(path: String): String =
        contents[path] ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, path)

    override suspend fun resolve(path: String, content: String) {
        resolved += path to content
    }

    override suspend fun resolveBinary(path: String, side: ConflictSide) {
        resolvedBinary += path to side
    }

    private fun resolvedPaths(): Set<String> =
        (resolved.map { it.first } + resolvedBinary.map { it.first }).toSet()
}

/** abort 확인 대조에 쓰이는 워킹트리 상태. 충돌 1건 + 추적 중 편집 1건. */
private class StatusOnlyRepositoryGateway : RepositoryGateway {

    override suspend fun open(path: RepositoryPath): OpenedRepository = error("사용하지 않는다")

    override suspend fun status(): WorkingTreeStatus = WorkingTreeStatus(
        staged = emptyList(),
        unstaged = listOf(
            FileChange("edited.txt", null, ChangeType.MODIFIED, 1, 0, isBinary = false),
        ),
        // 추적되지 않는 파일은 reset --hard 가 건드리지 않아 확인 대상이 아니다.
        untracked = listOf("scratch.txt"),
        conflicted = listOf(TEXT_FILE),
    )

    override suspend fun close() = error("사용하지 않는다")
}

/** 병합 Gateway 대역. [rejectAbort] 면 낡은 확인처럼 거부한다. */
private class RecordingMergeGateway(
    private val rejectAbort: Boolean = false,
    private val state: RepositoryState = RepositoryState.MERGING,
) : MergeGateway {

    val calls = mutableListOf<String>()
    var aborted = false
        private set

    override suspend fun repositoryState(): RepositoryState = state

    override suspend fun merge(target: RefName, allowFastForward: Boolean): MergeResult =
        error("사용하지 않는다")

    override suspend fun continueMerge(): MergeResult {
        calls += "continueMerge"
        return MergeResult.Succeeded(CommitId.of("a".repeat(40)), fastForward = false)
    }

    override suspend fun abortMerge(confirmation: AbortConfirmation) {
        if (rejectAbort) throw UndineException.StateViolation("확인한 뒤에 생긴 편집이 있습니다")
        aborted = true
    }

    override suspend fun rebase(target: RefName): RebaseResult = error("사용하지 않는다")

    override suspend fun continueRebase(): RebaseResult {
        calls += "continueRebase"
        return RebaseResult.Succeeded(CommitId.of("b".repeat(40)))
    }

    override suspend fun rebasingCommit(): CommitId? = null

    override suspend fun skipRebaseCommit(confirmation: SkipConfirmation): RebaseResult =
        error("사용하지 않는다")

    override suspend fun abortRebase(confirmation: AbortConfirmation) {
        if (rejectAbort) throw UndineException.StateViolation("확인한 뒤에 생긴 편집이 있습니다")
        aborted = true
    }
}
