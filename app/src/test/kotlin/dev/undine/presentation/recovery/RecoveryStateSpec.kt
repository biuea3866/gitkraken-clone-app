package dev.undine.presentation.recovery

import dev.undine.application.reflog.RecoveryActions
import dev.undine.application.reflog.RecoveryOutcome
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
import dev.undine.testsupport.commitWithId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
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

    test("기존 ref 이동은 확인 전에는 복구를 실행할 수 없는 상태로만 열린다") {
        val state = state(FakeRecoveryActions())

        state.requestRefMove()

        state.recoveryMode shouldBe RecoveryMode.MoveExisting
        state.requiresRefMoveWarning shouldBe true
        state.refMoveConfirmed shouldBe false

        state.confirmRefMove()
        state.refMoveConfirmed shouldBe true

        state.cancelRefMove()
        state.recoveryMode shouldBe RecoveryMode.NewBranch
        state.refMoveConfirmed shouldBe false
    }

    test("모드를 다시 고르면 앞선 이동 확인은 남지 않는다") {
        val state = state(FakeRecoveryActions())

        state.requestRefMove()
        state.confirmRefMove()
        state.requestRefMove()

        state.refMoveConfirmed shouldBe false
    }

    test("good·bad 경계를 모두 지정해야 bisect 를 시작한다") {
        val actions = FakeRecoveryActions(startResult = BisectResult.Testing(commitId(6), 5, 3))
        val state = state(actions)

        state.canStartBisect shouldBe false
        state.startSelectedBisect()
        actions.startedBoundaries shouldBe emptyList()

        state.selectBisectGood(commitId(1))
        state.canStartBisect shouldBe false
        state.selectBisectBad(commitId(8))
        state.canStartBisect shouldBe true

        state.startSelectedBisect()

        actions.startedBoundaries shouldContainExactly listOf(commitId(1) to commitId(8))
        state.bisectResult.shouldBeInstanceOf<BisectResult.Testing>().commit shouldBe commitId(6)
    }

    test("Testing 결과의 후보 수와 예상 검사 횟수를 요약에 보존한다") {
        val actions = FakeRecoveryActions(markResult = BisectResult.Testing(commitId(6), 5, 3))
        val state = state(actions)

        state.markBisect(BisectVerdict.GOOD)

        val summary = state.bisectSummary.shouldNotBeNull()
        summary.target shouldBe commitId(6)
        summary.remainingCandidates shouldBe 5
        summary.expectedRemainingChecks shouldBe 3
    }

    test("FirstBad 로 확정한 커밋은 결과 상태에 그대로 남는다") {
        val state = state(FakeRecoveryActions(markResult = BisectResult.FirstBad(commitId(9))))

        state.markBisect(BisectVerdict.BAD)

        state.bisectResult.shouldBeInstanceOf<BisectResult.FirstBad>().commit shouldBe commitId(9)
        state.bisectFailure.shouldBeNull()
    }

    test("복원한 세션은 현재 대상을 보이되 후보 수를 지어내지 않는다") {
        val state = state(FakeRecoveryActions(session = restoredSession()))

        state.load()

        val summary = state.bisectSummary.shouldNotBeNull()
        summary.target shouldBe commitId(4)
        summary.remainingCandidates.shouldBeNull()
        summary.expectedRemainingChecks.shouldBeNull()
    }

    test("세션도 결과도 없으면 요약을 만들지 않는다") {
        val state = state(FakeRecoveryActions())

        state.load()

        state.bisectSummary.shouldBeNull()
    }

    test("미리보기 조회 실패는 빈 미리보기로 위장하지 않는다") {
        val state = state(FakeRecoveryActions(previewFailure = UndineException.GitOperationFailed("preview")))

        state.selectReflog(entry(commitId(2)))

        state.preview.shouldBeInstanceOf<PreviewUiState.Failed>().failure
            .shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "preview"
        state.selectedPreview.shouldBeNull()
    }

    test("복구 실패는 성공한 ref 로 위장하지 않는다") {
        val state = state(FakeRecoveryActions(recoverFailure = UndineException.StateViolation("이미 있는 브랜치")))

        state.selectReflog(entry(commitId(2)))
        state.recoverSelected(RecoveryTarget.NewBranch(RefName("refs/heads/recovered")))

        state.recoveryFailure.shouldBeInstanceOf<UndineException.StateViolation>()
        state.recoveredRef.shouldBeNull()
    }

    test("도달 불가 탐색 실패는 빈 탐색 결과로 위장하지 않는다") {
        val state = state(FakeRecoveryActions(scanFailure = UndineException.GitOperationFailed("scan")))

        state.startUnreachableScan()

        state.unreachable.shouldBeInstanceOf<UnreachableUiState.Failed>().failure
            .shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "scan"
    }

    test("reset 실패는 세션이 정리된 것처럼 보이지 않게 남긴다") {
        val actions = FakeRecoveryActions(
            session = restoredSession(),
            resetFailure = UndineException.GitOperationFailed("bisect.reset"),
        )
        val state = state(actions)

        state.resetBisect()

        actions.resetRequests shouldBe 1
        state.bisectFailure.shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "bisect.reset"
        // 저장소에는 세션이 그대로 남아 있으므로 화면도 그것을 계속 보여 준다.
        state.bisectSession shouldBe restoredSession()
        state.resetVisible shouldBe true
    }

    test("늦게 끝난 앞선 미리보기 요청은 나중 선택의 미리보기를 덮어쓰지 않는다") {
        val first = commitId(2)
        val second = commitId(3)
        val firstGate = CompletableDeferred<Unit>()
        val actions = FakeRecoveryActions(
            previews = mapOf(
                first to ReflogCommitPreview(commit(2, message = "먼저 고른 커밋"), emptyList()),
                second to ReflogCommitPreview(commit(3, message = "나중 고른 커밋"), emptyList()),
            ),
            previewGates = mapOf(first to firstGate),
        )
        val state = state(actions)

        state.selectReflog(entry(first))
        state.selectReflog(entry(second))
        state.selectedPreview.shouldNotBeNull().commit.message shouldBe "나중 고른 커밋"

        firstGate.complete(Unit)

        state.selectedEntry.shouldNotBeNull().to shouldBe second
        state.selectedPreview.shouldNotBeNull().commit.message shouldBe "나중 고른 커밋"
    }

    test("앞선 미리보기의 늦은 실패는 나중 선택의 성공한 미리보기를 지우지 않는다") {
        val failing = commitId(2)
        val succeeding = commitId(3)
        val failingGate = CompletableDeferred<Unit>()
        val actions = FakeRecoveryActions(
            previews = mapOf(succeeding to ReflogCommitPreview(commit(3, message = "나중 고른 커밋"), emptyList())),
            previewFailures = mapOf(failing to UndineException.GitOperationFailed("preview")),
            previewGates = mapOf(failing to failingGate),
        )
        val state = state(actions)

        state.selectReflog(entry(failing))
        state.selectReflog(entry(succeeding))
        failingGate.complete(Unit)

        state.preview.shouldBeInstanceOf<PreviewUiState.Loaded>().preview.commit.message shouldBe "나중 고른 커밋"
    }

    test("reset 성공 뒤 세션 재조회가 실패하면 끝난 세션을 계속 보여 주지 않는다") {
        val actions = FakeRecoveryActions(
            session = restoredSession(),
            restoreFailure = UndineException.GitOperationFailed("bisect.restore"),
            restoreFailAfter = 1,
        )
        val state = state(actions)

        state.load()
        state.bisectSession shouldBe restoredSession()

        state.resetBisect()

        state.bisectSession.shouldBeNull()
        state.bisectFailure.shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "bisect.restore"
        state.resetVisible shouldBe true
    }

    test("판정 실패 뒤 세션 재조회도 실패하면 세션을 비우고 판정 실패를 먼저 알린다") {
        val actions = FakeRecoveryActions(
            session = restoredSession(),
            restoreFailure = UndineException.GitOperationFailed("bisect.restore"),
            restoreFailAfter = 1,
            markFailure = UndineException.StateViolation("판정 실패"),
        )
        val state = state(actions)

        state.load()
        state.markBisect(BisectVerdict.BAD)

        state.bisectSession.shouldBeNull()
        state.bisectResult.shouldBeNull()
        state.bisectFailure.shouldBeInstanceOf<UndineException.StateViolation>()
    }

    test("기존 세션이 있어도 다시 읽기가 실패하면 그 세션을 유지하지 않는다") {
        val actions = FakeRecoveryActions(
            session = restoredSession(),
            restoreFailure = UndineException.GitOperationFailed("bisect.restore"),
            restoreFailAfter = 1,
        )
        val state = state(actions)

        state.load()
        state.load()

        state.bisectSession.shouldBeNull()
        state.bisectFailure.shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "bisect.restore"
    }

    test("다시 읽기는 앞선 걸음의 Testing 요약을 저장소 상태로 갈아 끼운다") {
        val actions = FakeRecoveryActions(
            markResult = BisectResult.Testing(commitId(6), 5, 3),
            restoreFailure = UndineException.GitOperationFailed("bisect.restore"),
            restoreFailAfter = 1,
        )
        val state = state(actions)

        state.markBisect(BisectVerdict.GOOD)
        state.bisectSummary.shouldNotBeNull().target shouldBe commitId(6)

        state.load()

        // 저장소를 다시 읽지 못했으면 앞선 Testing 대상을 계속 보여 줄 근거가 없다.
        state.bisectResult.shouldBeNull()
        state.bisectSummary.shouldBeNull()
        state.bisectFailure.shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "bisect.restore"
    }

    test("다시 읽기는 앞선 판정 실패를 저장소에서 읽은 세션으로 갈아 끼운다") {
        val actions = FakeRecoveryActions(
            session = restoredSession(),
            markFailure = UndineException.StateViolation("판정 실패"),
        )
        val state = state(actions)

        state.markBisect(BisectVerdict.BAD)
        state.bisectFailure.shouldBeInstanceOf<UndineException.StateViolation>()

        state.load()

        state.bisectFailure.shouldBeNull()
        state.bisectSession shouldBe restoredSession()
    }

    test("늦게 끝난 앞선 세션 재조회는 나중 reset이 읽은 상태를 덮지 않는다") {
        val staleGate = CompletableDeferred<Unit>()
        val actions = FakeRecoveryActions(
            restoreSessions = listOf(restoredSession(), null),
            restoreGates = mapOf(1 to staleGate),
        )
        val state = state(actions)

        state.load()
        state.resetBisect()
        state.bisectSession.shouldBeNull()

        staleGate.complete(Unit)

        // 앞선 재조회가 이제야 끝났지만, 그 값은 reset 이전의 저장소 상태다.
        state.bisectSession.shouldBeNull()
    }

    test("늦게 끝난 앞선 판정 결과는 나중 reset이 정리한 상태를 되살리지 않는다") {
        val markGate = CompletableDeferred<Unit>()
        val actions = FakeRecoveryActions(
            markResult = BisectResult.Testing(commitId(6), 5, 3),
            markGate = markGate,
        )
        val state = state(actions)

        state.markBisect(BisectVerdict.BAD)
        state.resetBisect()
        state.bisectResult.shouldBeNull()

        markGate.complete(Unit)

        state.bisectResult.shouldBeNull()
        state.bisectSummary.shouldBeNull()
    }

    test("복구가 적용된 뒤 Undo 기록이 실패하면 성공으로 보이지 않고 화면에 사유가 남는다") {
        val recordFailure = UndineException.GitOperationFailed("undo.record")
        val state = state(FakeRecoveryActions(undoRecordFailure = recordFailure))
        state.selectReflog(entry(commitId(2)))

        state.recoverSelected(RecoveryTarget.NewBranch(RefName("refs/heads/recovered")))

        // 변경 자체는 적용됐으므로 복구 실패로 접지 않는다.
        state.recoveredRef shouldBe RefName("refs/heads/recovered")
        state.recoveryFailure.shouldBeNull()
        // 그러나 Undo 항목이 없어진 사실은 화면에 닿아야 한다.
        state.recoveryUndoRecordFailure
            .shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "undo.record"
    }

    test("bisect 시작·판정·reset 의 Undo 기록 실패도 세션 변경을 성공으로만 보이게 두지 않는다") {
        val recordFailure = UndineException.GitOperationFailed("undo.record")

        val started = state(FakeRecoveryActions(undoRecordFailure = recordFailure))
        started.startBisect(commitId(1), commitId(8))
        started.bisectFailure.shouldBeNull()
        started.bisectUndoRecordFailure.shouldNotBeNull()

        val marked = state(FakeRecoveryActions(undoRecordFailure = recordFailure))
        marked.markBisect(BisectVerdict.GOOD)
        marked.bisectUndoRecordFailure.shouldNotBeNull()

        val reset = state(FakeRecoveryActions(session = restoredSession(), undoRecordFailure = recordFailure))
        reset.load()
        reset.resetBisect()
        reset.bisectUndoRecordFailure.shouldNotBeNull()
    }

    test("기록까지 성공한 변경은 기록 실패 표시를 남기지 않고 다음 요청이 앞선 표시를 지운다") {
        val clean = state(FakeRecoveryActions())
        clean.selectReflog(entry(commitId(2)))
        clean.recoverSelected(RecoveryTarget.NewBranch(RefName("refs/heads/recovered")))
        clean.recoveryUndoRecordFailure.shouldBeNull()

        // 앞선 기록 실패가 다음 판정까지 남으면 사용자는 이미 기록된 변경도 누락됐다고 읽는다.
        val actions = FakeRecoveryActions(undoRecordFailure = UndineException.GitOperationFailed("undo.record"))
        val stale = state(actions)
        stale.markBisect(BisectVerdict.GOOD)
        stale.bisectUndoRecordFailure.shouldNotBeNull()

        val recovered = state(FakeRecoveryActions())
        recovered.markBisect(BisectVerdict.GOOD)
        recovered.bisectUndoRecordFailure.shouldBeNull()
    }

    test("미리보기·복구·탐색의 취소는 실패나 빈 결과로 접히지 않는다") {
        val previewCancelled = state(FakeRecoveryActions(cancelSteps = setOf(RecoveryStep.PREVIEW)))
        previewCancelled.selectReflog(entry(commitId(2)))
        previewCancelled.preview.shouldBeInstanceOf<PreviewUiState.Loading>()
        previewCancelled.selectedPreview.shouldBeNull()

        val recoverCancelled = state(FakeRecoveryActions(cancelSteps = setOf(RecoveryStep.RECOVER)))
        recoverCancelled.selectReflog(entry(commitId(2)))
        recoverCancelled.recoverSelected(RecoveryTarget.NewBranch(RefName("refs/heads/recovered")))
        recoverCancelled.recoveredRef.shouldBeNull()
        recoverCancelled.recoveryFailure.shouldBeNull()

        val scanCancelled = state(FakeRecoveryActions(cancelSteps = setOf(RecoveryStep.SCAN)))
        scanCancelled.startUnreachableScan()
        scanCancelled.unreachable.shouldBeInstanceOf<UnreachableUiState.Scanning>()
    }

    test("bisect 시작·판정·reset·세션 복원의 취소는 성공이나 빈 상태로 기록되지 않는다") {
        val startCancelled = state(FakeRecoveryActions(cancelSteps = setOf(RecoveryStep.START)))
        startCancelled.startBisect(commitId(1), commitId(8))
        startCancelled.bisectResult.shouldBeNull()
        startCancelled.bisectFailure.shouldBeNull()

        val markCancelled = state(FakeRecoveryActions(cancelSteps = setOf(RecoveryStep.MARK)))
        markCancelled.markBisect(BisectVerdict.GOOD)
        markCancelled.bisectResult.shouldBeNull()
        markCancelled.bisectFailure.shouldBeNull()

        val resetActions = FakeRecoveryActions(session = restoredSession(), cancelSteps = setOf(RecoveryStep.RESET))
        val resetCancelled = state(resetActions)
        resetCancelled.load()
        resetCancelled.resetBisect()
        resetActions.resetRequests shouldBe 1
        resetCancelled.bisectFailure.shouldBeNull()
        // 취소는 "정리됐다"도 "실패했다"도 아니다 — 저장소에서 마지막으로 읽은 세션이 그대로 남는다.
        resetCancelled.bisectSession shouldBe restoredSession()

        val restoreCancelled = state(FakeRecoveryActions(cancelSteps = setOf(RecoveryStep.RESTORE)))
        restoreCancelled.load()
        restoreCancelled.bisectSession.shouldBeNull()
        restoreCancelled.bisectFailure.shouldBeNull()
        restoreCancelled.resetVisible shouldBe true
    }
})

private fun restoredSession(): BisectSession = BisectSession(
    startPoint = BisectStartPoint.Branch(RefName("refs/heads/main")),
    good = listOf(commitId(1)),
    bad = commitId(8),
    skipped = emptyList(),
    testing = commitId(4),
)

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

/** 취소를 주입할 비동기 전이. 각 전이가 CancellationException을 삼키지 않는지 확인하는 데 쓴다. */
private enum class RecoveryStep { PREVIEW, SCAN, RECOVER, START, MARK, RESET, RESTORE }

@Suppress("LongParameterList") // 각 테스트가 필요한 외부 application 결과만 선택적으로 고정한다.
private class FakeRecoveryActions(
    private val listing: ReflogListing = ReflogListing(emptyList(), mayBeExpired = false),
    private val listingFailure: UndineException? = null,
    private val previews: Map<CommitId, ReflogCommitPreview> = emptyMap(),
    private val previewFailure: UndineException? = null,
    private val previewFailures: Map<CommitId, UndineException> = emptyMap(),
    private val previewGates: Map<CommitId, CompletableDeferred<Unit>> = emptyMap(),
    private val scanGate: CompletableDeferred<Unit>? = null,
    private val scanFailure: UndineException? = null,
    private val recoverFailure: UndineException? = null,
    /** 변경은 적용됐는데 Undo 기록만 실패한 상황. null이면 기록까지 성공한다. */
    private val undoRecordFailure: UndineException? = null,
    private val session: BisectSession? = null,
    /** 재조회 순번(1부터)별로 돌려줄 세션. 목록을 다 쓰면 [session]으로 떨어진다. */
    private val restoreSessions: List<BisectSession?> = emptyList(),
    /** 재조회 순번(1부터) → 그 호출을 붙잡아 둘 게이트. 완료 순서 역전을 재현하는 데 쓴다. */
    private val restoreGates: Map<Int, CompletableDeferred<Unit>> = emptyMap(),
    private val restoreFailure: UndineException? = null,
    /** 이 횟수만큼 성공한 뒤부터 [restoreFailure]를 던진다. 기존 세션이 있는 상태의 재조회 실패용. */
    private val restoreFailAfter: Int = 0,
    private val startResult: BisectResult? = null,
    private val markResult: BisectResult? = null,
    private val markGate: CompletableDeferred<Unit>? = null,
    private val markFailure: UndineException? = null,
    private val resetFailure: UndineException? = null,
    private val cancelSteps: Set<RecoveryStep> = emptySet(),
) : RecoveryActions {
    var scanRequests: Int = 0
        private set
    var resetRequests: Int = 0
        private set
    var restoreRequests: Int = 0
        private set
    val markedVerdicts = mutableListOf<BisectVerdict>()
    val startedBoundaries = mutableListOf<Pair<CommitId, CommitId>>()

    private fun cancelIf(step: RecoveryStep) {
        if (step in cancelSteps) throw CancellationException("$step 취소")
    }

    override suspend fun loadReflog(limit: Int): ReflogListing {
        listingFailure?.let { throw it }
        return listing
    }

    override suspend fun loadPreview(commit: CommitId): ReflogCommitPreview {
        previewGates[commit]?.await()
        cancelIf(RecoveryStep.PREVIEW)
        (previewFailures[commit] ?: previewFailure)?.let { throw it }
        // 미리보기를 고정하지 않은 테스트도 선택 자체는 성공해야 한다 — 없는 키로 터지면 그 예외가
        // 코루틴 밖으로 새어 다른 테스트를 오염시킨다.
        return previews[commit] ?: ReflogCommitPreview(commitWithId(commit, ""), emptyList())
    }

    override suspend fun scanUnreachable(limit: Int): UnreachableCommitScan {
        scanRequests += 1
        scanGate?.await()
        cancelIf(RecoveryStep.SCAN)
        scanFailure?.let { throw it }
        return UnreachableCommitScan.Scanned(emptyList())
    }

    override suspend fun recover(commit: CommitId, target: RecoveryTarget): RecoveryOutcome<RefName> {
        cancelIf(RecoveryStep.RECOVER)
        recoverFailure?.let { throw it }
        val recovered = when (target) {
            is RecoveryTarget.NewBranch -> target.name
            is RecoveryTarget.MoveExisting -> target.name
        }
        return RecoveryOutcome(recovered, undoRecordFailure)
    }

    override suspend fun restoreBisect(): BisectSession? {
        cancelIf(RecoveryStep.RESTORE)
        restoreRequests += 1
        val sequence = restoreRequests
        restoreGates[sequence]?.await()
        restoreFailure?.let { if (sequence > restoreFailAfter) throw it }
        return restoreSessions.getOrElse(sequence - 1) { session }
    }

    override suspend fun startBisect(good: CommitId, bad: CommitId): RecoveryOutcome<BisectResult> {
        startedBoundaries += good to bad
        cancelIf(RecoveryStep.START)
        return RecoveryOutcome(startResult ?: BisectResult.Testing(bad, 1, 0), undoRecordFailure)
    }

    override suspend fun markBisect(verdict: BisectVerdict): RecoveryOutcome<BisectResult> {
        markedVerdicts += verdict
        markGate?.await()
        cancelIf(RecoveryStep.MARK)
        markFailure?.let { throw it }
        return RecoveryOutcome(markResult ?: BisectResult.Testing(commitId(7), 2, 1), undoRecordFailure)
    }

    override suspend fun resetBisect(): RecoveryOutcome<Unit> {
        resetRequests += 1
        cancelIf(RecoveryStep.RESET)
        resetFailure?.let { throw it }
        return RecoveryOutcome(Unit, undoRecordFailure)
    }
}
