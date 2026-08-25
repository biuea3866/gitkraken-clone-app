package dev.undine.application.reflog

import dev.undine.application.bisect.MarkBisectUseCase
import dev.undine.application.bisect.ResetBisectUseCase
import dev.undine.application.bisect.RestoreBisectSessionUseCase
import dev.undine.application.bisect.StartBisectUseCase
import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.FileChange
import dev.undine.domain.HistoryGateway
import dev.undine.domain.RefName
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectService
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.RefMoveConfirmation
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.domain.reflog.ReflogGateway
import dev.undine.domain.reflog.ReflogPage
import dev.undine.domain.reflog.UnreachableCommitScan
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStrategy
import dev.undine.testsupport.FIXED_NOW
import dev.undine.testsupport.FIXTURE_AUTHOR
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class RecoveryActionServiceSpec : BehaviorSpec({
    given("reflog 복구 화면의 application 경로") {
        `when`("reflog 항목을 읽고 하나를 선택하면") {
            then("기존 HistoryGateway.load 경로로 메시지와 변경 파일 요약을 조합한다") {
                val target = commitId(2)
                val reflog = mockk<ReflogGateway>()
                val history = mockk<HistoryGateway>()
                val diff = mockk<DiffGateway>()
                val actions = service(reflog, history, diff)
                coEvery { reflog.headReflog(50) } returns ReflogPage(listOf(entry(target)), mayBeExpired = false)
                coEvery { history.load(listOf(RefName(target.value)), 0, 1) } returns
                    listOf(commit(2, message = "되찾을 커밋"))
                coEvery { diff.changedFiles(target, 0) } returns listOf(
                    FileChange("lost.txt", null, ChangeType.ADDED, 3, 0, false),
                )

                actions.loadReflog(50).entries shouldContainExactly listOf(entry(target))
                val preview = actions.loadPreview(target)

                preview.commit.message shouldBe "되찾을 커밋"
                preview.changedFiles.map { it.path } shouldContainExactly listOf("lost.txt")
            }
        }

        `when`("도달 불가 커밋 탐색을 요청하지 않으면") {
            then("자동으로 느린 탐색을 시작하지 않는다") {
                val reflog = mockk<ReflogGateway>()
                val actions = service(reflog, mockk(), mockk())
                coEvery { reflog.headReflog(20) } returns ReflogPage(emptyList(), mayBeExpired = false)

                actions.loadReflog(20)

                coVerify(exactly = 0) { reflog.unreachableCommits(any()) }
            }
        }

        `when`("사용자가 도달 불가 커밋 탐색을 시작하면") {
            then("미지원 결과를 빈 성공으로 바꾸지 않고 그대로 전달한다") {
                val reflog = mockk<ReflogGateway>()
                val actions = service(reflog, mockk(), mockk())
                val unsupported = UnreachableCommitScan.NotSupported(
                    UnreachableCommitScan.NotSupported.Reason.NON_FILE_OBJECT_DATABASE,
                )
                coEvery { reflog.unreachableCommits(100) } returns unsupported

                actions.scanUnreachable(100) shouldBe unsupported
            }
        }
    }

    given("복구와 bisect 변경") {
        `when`("새 브랜치로 reflog 지점을 복구하면") {
            then("생성 브랜치를 지우는 되돌리기 전략으로 Undo에 기록한다") {
                val target = commitId(3)
                val reflog = mockk<ReflogGateway>()
                val recorder = mockk<OperationRecorder>(relaxed = true)
                val actions = service(reflog, mockk(), mockk(), recorder)
                val branch = RefName("refs/heads/recovered")
                coEvery { reflog.recover(target, RecoveryTarget.NewBranch(branch)) } returns branch

                actions.recover(target, RecoveryTarget.NewBranch(branch)) shouldBe branch

                coVerify { recorder.record(GitOperationKind.REFLOG_RESTORE, UndoStrategy.DeleteBranch(branch)) }
            }
        }

        `when`("기존 ref 이동으로 복구하면") {
            then("밀려난 커밋 위험 사유를 담아 비가역 Undo 항목으로 기록한다") {
                val target = commitId(3)
                val displaced = commitId(4)
                val reflog = mockk<ReflogGateway>()
                val recorder = mockk<OperationRecorder>(relaxed = true)
                val actions = service(reflog, mockk(), mockk(), recorder)
                val move = RecoveryTarget.MoveExisting(
                    RefName("refs/heads/main"),
                    RefMoveConfirmation.ofDisplacedCommit(displaced),
                )
                coEvery { reflog.recover(target, move) } returns move.name

                actions.recover(target, move)

                coVerify {
                    recorder.recordIrreversible(
                        GitOperationKind.REFLOG_RESTORE,
                        match { it.contains(displaced.value) },
                    )
                }
            }
        }

        `when`("bisect 판정을 적용하면") {
            then("세션 변경을 Undo 이력에 누락하지 않는다") {
                val recorder = mockk<OperationRecorder>(relaxed = true)
                val service = mockk<BisectService>()
                coEvery { service.mark(BisectVerdict.GOOD) } returns BisectResult.FirstBad(commitId(9))
                val actions = RecoveryActionService(
                    reflogGateway = mockk(),
                    historyGateway = mockk(),
                    diffGateway = mockk(),
                    bisect = bisectUseCases(service),
                    operationRecorder = recorder,
                )

                actions.markBisect(BisectVerdict.GOOD) shouldBe BisectResult.FirstBad(commitId(9))

                coVerify { recorder.recordIrreversible(GitOperationKind.BISECT_SESSION, any()) }
            }
        }
    }
})

private fun service(
    reflog: ReflogGateway,
    history: HistoryGateway,
    diff: DiffGateway,
    recorder: OperationRecorder = mockk(relaxed = true),
): RecoveryActionService = RecoveryActionService(
    reflogGateway = reflog,
    historyGateway = history,
    diffGateway = diff,
    bisect = RecoveryBisectUseCases(mockk(), mockk(), mockk(), mockk()),
    operationRecorder = recorder,
)

private fun bisectUseCases(service: BisectService): RecoveryBisectUseCases = RecoveryBisectUseCases(
    start = StartBisectUseCase(service),
    mark = MarkBisectUseCase(service),
    reset = ResetBisectUseCase(service),
    restore = RestoreBisectSessionUseCase(service),
)

private fun entry(target: CommitId): ReflogEntry = ReflogEntry(
    index = 0,
    from = null,
    to = target,
    action = "commit: recover me",
    who = FIXTURE_AUTHOR,
    at = FIXED_NOW,
)
