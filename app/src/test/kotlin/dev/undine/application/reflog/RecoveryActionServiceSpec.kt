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
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectService
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.domain.reflog.RecoveredRef
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
import dev.undine.testsupport.spyRecorderOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException

/** 복구가 자기 임계 구역에서 캡처해 결과로 준 **복구 직후** 기준 상태 (UND-73). */
private val RECOVERED_BASELINE = RepositoryBaseline(branch = RefName("main"), head = commitId(3))

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
                val recorder = spyRecorderOf()
                val actions = service(reflog, mockk(), mockk(), recorder)
                val branch = RefName("refs/heads/recovered")
                coEvery { reflog.recover(target, RecoveryTarget.NewBranch(branch)) } returns
                    RecoveredRef(branch, RECOVERED_BASELINE)

                actions.recover(target, RecoveryTarget.NewBranch(branch)).value shouldBe branch

                // 복구 결과가 준 기준 상태를 그대로 넘긴다 — 기록이 사후에 다시 읽지 않는다 (UND-73).
                coVerify {
                    recorder.record(
                        GitOperationKind.REFLOG_RESTORE,
                        UndoStrategy.DeleteBranch(branch),
                        RECOVERED_BASELINE,
                        any(),
                    )
                }
            }
        }

        `when`("기존 ref 이동으로 복구하면") {
            then("밀려난 커밋 위험 사유를 담아 비가역 Undo 항목으로 기록한다") {
                val target = commitId(3)
                val displaced = commitId(4)
                val reflog = mockk<ReflogGateway>()
                val recorder = spyRecorderOf()
                val actions = service(reflog, mockk(), mockk(), recorder)
                val move = RecoveryTarget.MoveExisting(
                    RefName("refs/heads/main"),
                    RefMoveConfirmation.ofDisplacedCommit(displaced),
                )
                coEvery { reflog.recover(target, move) } returns RecoveredRef(move.name, RECOVERED_BASELINE)

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
                val recorder = spyRecorderOf()
                val service = mockk<BisectService>()
                coEvery { service.mark(BisectVerdict.GOOD) } returns BisectResult.FirstBad(commitId(9))
                val actions = bisectActions(service, recorder)

                actions.markBisect(BisectVerdict.GOOD).value shouldBe BisectResult.FirstBad(commitId(9))

                coVerify { recorder.recordIrreversible(GitOperationKind.BISECT_SESSION, any(), any()) }
            }
        }

        `when`("bisect 세션을 시작하면") {
            then("시작도 세션 변경으로 Undo 이력에 남긴다") {
                val recorder = spyRecorderOf()
                val service = mockk<BisectService>()
                val started = BisectResult.Testing(commitId(6), 4, 2)
                coEvery { service.start(commitId(1), commitId(8)) } returns started
                val actions = bisectActions(service, recorder)

                actions.startBisect(commitId(1), commitId(8)).value shouldBe started

                coVerify { recorder.recordIrreversible(GitOperationKind.BISECT_SESSION, any(), any()) }
            }
        }

        `when`("bisect 세션을 reset 하면") {
            then("reset 도 세션 변경으로 Undo 이력에 남긴다") {
                val recorder = spyRecorderOf()
                val service = mockk<BisectService>()
                coEvery { service.reset() } returns Unit
                val actions = bisectActions(service, recorder)

                actions.resetBisect()

                coVerify { recorder.recordIrreversible(GitOperationKind.BISECT_SESSION, any(), any()) }
            }
        }
    }

    given("Undo 기록이 실패하는 상황") {
        `when`("복구가 이미 적용된 뒤 기록이 실패하면") {
            then("복구 자체를 실패로 만들지 않되 기록 실패 사유를 결과에 실어 화면에 알린다") {
                val target = commitId(3)
                val branch = RefName("refs/heads/recovered")
                val reflog = mockk<ReflogGateway>()
                val recorder = spyRecorderOf()
                coEvery { reflog.recover(target, RecoveryTarget.NewBranch(branch)) } returns
                    RecoveredRef(branch, RECOVERED_BASELINE)
                coEvery { recorder.record(any(), any(), any(), any()) } throws
                    UndineException.GitOperationFailed("undo.record")
                val actions = service(reflog, mockk(), mockk(), recorder)

                val outcome = actions.recover(target, RecoveryTarget.NewBranch(branch))

                outcome.value shouldBe branch
                // 로그로만 남기면 Undo 항목이 사라진 사실이 화면에 닿지 않는다.
                outcome.undoRecordFailure
                    .shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "undo.record"
            }
        }

        `when`("기존 ref 이동이 이미 적용된 뒤 비가역 기록이 실패하면") {
            then("이동 결과를 유지하면서 기록 실패 사유를 함께 돌려준다") {
                val target = commitId(3)
                val reflog = mockk<ReflogGateway>()
                val recorder = spyRecorderOf()
                val move = RecoveryTarget.MoveExisting(
                    RefName("refs/heads/main"),
                    RefMoveConfirmation.ofDisplacedCommit(commitId(4)),
                )
                coEvery { reflog.recover(target, move) } returns RecoveredRef(move.name, RECOVERED_BASELINE)
                coEvery { recorder.recordIrreversible(any(), any(), any()) } throws
                    UndineException.GitOperationFailed("undo.record")
                val actions = service(reflog, mockk(), mockk(), recorder)

                val outcome = actions.recover(target, move)

                outcome.value shouldBe move.name
                outcome.undoRecordFailure.shouldNotBeNull()
            }
        }

        `when`("bisect 판정이 이미 적용된 뒤 기록이 실패하면") {
            then("판정 결과를 삼키지 않고 기록 실패 사유와 함께 돌려준다") {
                val recorder = spyRecorderOf()
                val service = mockk<BisectService>()
                val marked = BisectResult.Testing(commitId(5), 2, 1)
                coEvery { service.mark(BisectVerdict.BAD) } returns marked
                coEvery { recorder.recordIrreversible(any(), any(), any()) } throws
                    UndineException.GitOperationFailed("undo.record")
                val actions = bisectActions(service, recorder)

                val outcome = actions.markBisect(BisectVerdict.BAD)

                outcome.value shouldBe marked
                outcome.undoRecordFailure.shouldNotBeNull()
            }
        }

        `when`("bisect 시작·reset 의 기록이 실패하면") {
            then("시작과 reset 도 기록 실패 사유를 결과에 싣는다") {
                val recorder = spyRecorderOf()
                val service = mockk<BisectService>()
                coEvery { service.start(commitId(1), commitId(8)) } returns BisectResult.Testing(commitId(4), 3, 2)
                coEvery { service.reset() } returns Unit
                coEvery { recorder.recordIrreversible(any(), any(), any()) } throws
                    UndineException.GitOperationFailed("undo.record")
                val actions = bisectActions(service, recorder)

                actions.startBisect(commitId(1), commitId(8)).undoRecordFailure.shouldNotBeNull()
                actions.resetBisect().undoRecordFailure.shouldNotBeNull()
            }
        }

        `when`("기록까지 성공하면") {
            then("숨길 실패가 없다는 뜻으로 사유를 비운다") {
                val target = commitId(3)
                val branch = RefName("refs/heads/recovered")
                val reflog = mockk<ReflogGateway>()
                val actions = service(reflog, mockk(), mockk())
                coEvery { reflog.recover(target, RecoveryTarget.NewBranch(branch)) } returns
                    RecoveredRef(branch, RECOVERED_BASELINE)

                actions.recover(target, RecoveryTarget.NewBranch(branch)).undoRecordFailure.shouldBeNull()
            }
        }
    }

    given("Undo 기록 중 취소가 오는 상황") {
        `when`("새 브랜치 복구의 기록이 취소되면") {
            then("취소를 기록 실패로 강등하지 않고 호출자에게 전파한다") {
                val target = commitId(3)
                val branch = RefName("refs/heads/recovered")
                val reflog = mockk<ReflogGateway>()
                val recorder = spyRecorderOf()
                coEvery { reflog.recover(target, RecoveryTarget.NewBranch(branch)) } returns
                    RecoveredRef(branch, RECOVERED_BASELINE)
                coEvery { recorder.record(any(), any(), any(), any()) } throws CancellationException("기록 취소")
                val actions = service(reflog, mockk(), mockk(), recorder)

                shouldThrow<CancellationException> {
                    actions.recover(target, RecoveryTarget.NewBranch(branch))
                }
            }
        }

        `when`("기존 ref 이동 복구의 비가역 기록이 취소되면") {
            then("취소를 삼키지 않고 그대로 전파한다") {
                val target = commitId(3)
                val reflog = mockk<ReflogGateway>()
                val recorder = spyRecorderOf()
                val move = RecoveryTarget.MoveExisting(
                    RefName("refs/heads/main"),
                    RefMoveConfirmation.ofDisplacedCommit(commitId(4)),
                )
                coEvery { reflog.recover(target, move) } returns RecoveredRef(move.name, RECOVERED_BASELINE)
                coEvery { recorder.recordIrreversible(any(), any(), any()) } throws CancellationException("기록 취소")
                val actions = service(reflog, mockk(), mockk(), recorder)

                shouldThrow<CancellationException> { actions.recover(target, move) }
            }
        }

        `when`("bisect 세션 변경의 기록이 취소되면") {
            then("판정 결과로 접지 않고 취소를 전파한다") {
                val recorder = spyRecorderOf()
                val service = mockk<BisectService>()
                coEvery { service.mark(BisectVerdict.BAD) } returns BisectResult.Testing(commitId(5), 2, 1)
                coEvery { recorder.recordIrreversible(any(), any(), any()) } throws CancellationException("기록 취소")
                val actions = bisectActions(service, recorder)

                shouldThrow<CancellationException> { actions.markBisect(BisectVerdict.BAD) }
            }
        }
    }
})

private fun bisectActions(service: BisectService, recorder: OperationRecorder): RecoveryActionService =
    RecoveryActionService(
        reflogGateway = mockk(),
        historyGateway = mockk(),
        diffGateway = mockk(),
        bisect = bisectUseCases(service),
        operationRecorder = recorder,
    )

private fun service(
    reflog: ReflogGateway,
    history: HistoryGateway,
    diff: DiffGateway,
    recorder: OperationRecorder = spyRecorderOf(),
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
