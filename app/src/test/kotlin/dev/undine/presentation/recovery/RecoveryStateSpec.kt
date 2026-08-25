package dev.undine.presentation.recovery

import dev.undine.application.reflog.RecoveryActions
import dev.undine.application.reflog.ReflogCommitPreview
import dev.undine.application.reflog.ReflogListing
import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.FileChange
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectSession
import dev.undine.domain.bisect.BisectStartPoint
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.domain.reflog.UnreachableCommitScan
import dev.undine.testsupport.FIXED_NOW
import dev.undine.testsupport.FIXTURE_AUTHOR
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class RecoveryStateSpec : FunSpec({
    test("reflog 항목을 선택하면 커밋 메시지와 변경 파일 요약을 함께 표시한다") {
        val target = commitId(2)
        val actions = FakeRecoveryActions(
            listing = ReflogListing(listOf(entry(target)), mayBeExpired = false),
            previews = mapOf(
                target to ReflogCommitPreview(commit(2, message = "잃은 작업"), listOf(fileChange("lost.kt"))),
            ),
        )
        val state = state(actions)

        state.load()
        state.selectReflog(entry(target))

        state.selectedPreview.shouldNotBeNull().commit.message shouldBe "잃은 작업"
        state.selectedPreview.shouldNotBeNull().changedFiles.map { it.path } shouldContainExactly listOf("lost.kt")
    }

    test("비어 있는 reflog는 만료 가능성을 안내하고 조회 실패와 구분한다") {
        val empty = state(FakeRecoveryActions(listing = ReflogListing(emptyList(), mayBeExpired = true)))
        empty.load()

        empty.reflog.shouldBeInstanceOf<ReflogUiState.Loaded>().mayBeExpired shouldBe true
        empty.reflog.shouldBeInstanceOf<ReflogUiState.Loaded>().entries shouldBe emptyList()

        val failed = state(FakeRecoveryActions(listingFailure = UndineException.GitOperationFailed("reflog")))
        failed.load()

        failed.reflog.shouldBeInstanceOf<ReflogUiState.Failed>().failure
            .shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "reflog"
    }

    test("도달 불가 커밋 탐색은 사용자 시작 전 호출되지 않고 시작 후 진행 상태를 보인다") {
        val gate = CompletableDeferred<Unit>()
        val actions = FakeRecoveryActions(scanGate = gate)
        val state = state(actions)

        state.load()
        actions.scanRequests shouldBe 0

        state.startUnreachableScan()
        state.unreachable.shouldBeInstanceOf<UnreachableUiState.Scanning>()
        actions.scanRequests shouldBe 1

        gate.complete(Unit)
        state.unreachable.shouldBeInstanceOf<UnreachableUiState.Completed>()
    }

    test("복구 기본값은 새 브랜치이고 기존 ref 이동은 위험 경고를 요구한다") {
        val state = state(FakeRecoveryActions())

        state.recoveryMode shouldBe RecoveryMode.NewBranch
        state.selectRecoveryMode(RecoveryMode.MoveExisting)

        state.recoveryMode shouldBe RecoveryMode.MoveExisting
        state.requiresRefMoveWarning shouldBe true
    }

    test("bisect 상태는 실제 good·현재 bad·skipped를 누적 표시하고 시간순 이력이라고 주장하지 않는다") {
        val session = BisectSession(
            startPoint = BisectStartPoint.Branch(RefName("refs/heads/main")),
            good = listOf(commitId(1), commitId(3)),
            bad = commitId(8),
            skipped = listOf(commitId(5)),
            testing = commitId(6),
        )
        val state = state(FakeRecoveryActions(session = session))

        state.load()

        state.bisectHistory.good shouldContainExactly listOf(commitId(1), commitId(3))
        state.bisectHistory.currentBad shouldBe commitId(8)
        state.bisectHistory.skipped shouldContainExactly listOf(commitId(5))
        state.bisectHistory.isChronologicalEventLog shouldBe false
        state.resetVisible shouldBe true
    }

    test("skip으로 미확정이면 후보 전체를 유지하고 키보드 판정은 마우스와 같은 전이를 쓴다") {
        val candidates = listOf(commitId(4), commitId(5))
        val actions = FakeRecoveryActions(markResult = BisectResult.Inconclusive(candidates))
        val state = state(actions)

        state.markBisect(BisectVerdict.SKIP)
        val mouseResult = state.bisectResult.shouldBeInstanceOf<BisectResult.Inconclusive>()
        mouseResult.candidates shouldContainExactly candidates

        state.onKeyboardVerdict(BisectVerdict.SKIP)
        actions.markedVerdicts shouldContainExactly listOf(BisectVerdict.SKIP, BisectVerdict.SKIP)
        state.bisectResult.shouldBeInstanceOf<BisectResult.Inconclusive>().candidates shouldContainExactly candidates
    }

    test("bisect 실패는 성공이나 빈 결과로 숨기지 않고 reset 경로는 계속 노출한다") {
        val state = state(FakeRecoveryActions(markFailure = UndineException.StateViolation("판정 실패")))

        state.markBisect(BisectVerdict.BAD)

        state.bisectFailure.shouldBeInstanceOf<UndineException.StateViolation>()
        state.bisectResult.shouldBeNull()
        state.resetVisible shouldBe true
    }

    test("저장된 bisect 세션 복원 실패는 세션 없음으로 위장하지 않는다") {
        val state = state(FakeRecoveryActions(restoreFailure = UndineException.GitOperationFailed("bisect.restore")))

        state.load()

        state.bisectSession.shouldBeNull()
        state.bisectFailure.shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "bisect.restore"
        state.resetVisible shouldBe true
    }
})

private fun state(actions: RecoveryActions): RecoveryState = RecoveryState(
    actions = actions,
    scope = CoroutineScope(Dispatchers.Unconfined),
)

private fun entry(target: CommitId): ReflogEntry = ReflogEntry(
    index = 0,
    from = null,
    to = target,
    action = "commit",
    who = FIXTURE_AUTHOR,
    at = FIXED_NOW,
)

private fun fileChange(path: String): FileChange = FileChange(path, null, ChangeType.MODIFIED, 1, 1, false)

@Suppress("LongParameterList") // 각 테스트가 필요한 외부 application 결과만 선택적으로 고정한다.
private class FakeRecoveryActions(
    private val listing: ReflogListing = ReflogListing(emptyList(), mayBeExpired = false),
    private val listingFailure: UndineException? = null,
    private val previews: Map<CommitId, ReflogCommitPreview> = emptyMap(),
    private val scanGate: CompletableDeferred<Unit>? = null,
    private val session: BisectSession? = null,
    private val restoreFailure: UndineException? = null,
    private val markResult: BisectResult? = null,
    private val markFailure: UndineException? = null,
) : RecoveryActions {
    var scanRequests: Int = 0
        private set
    val markedVerdicts = mutableListOf<BisectVerdict>()

    override suspend fun loadReflog(limit: Int): ReflogListing {
        listingFailure?.let { throw it }
        return listing
    }

    override suspend fun loadPreview(commit: CommitId): ReflogCommitPreview =
        previews.getValue(commit)

    override suspend fun scanUnreachable(limit: Int): UnreachableCommitScan {
        scanRequests += 1
        scanGate?.await()
        return UnreachableCommitScan.Scanned(emptyList())
    }

    override suspend fun recover(commit: CommitId, target: RecoveryTarget): RefName = when (target) {
        is RecoveryTarget.NewBranch -> target.name
        is RecoveryTarget.MoveExisting -> target.name
    }

    override suspend fun restoreBisect(): BisectSession? {
        restoreFailure?.let { throw it }
        return session
    }

    override suspend fun startBisect(good: CommitId, bad: CommitId): BisectResult =
        BisectResult.Testing(bad, 1, 0)

    override suspend fun markBisect(verdict: BisectVerdict): BisectResult {
        markedVerdicts += verdict
        markFailure?.let { throw it }
        return markResult ?: BisectResult.Testing(commitId(7), 2, 1)
    }

    override suspend fun resetBisect() = Unit
}
