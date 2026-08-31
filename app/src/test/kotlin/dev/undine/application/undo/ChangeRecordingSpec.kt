package dev.undine.application.undo

import dev.undine.application.staging.AmendOutcome
import dev.undine.domain.CheckoutResult
import dev.undine.domain.CommitResult
import dev.undine.domain.PushResult
import dev.undine.domain.RepositoryState
import dev.undine.domain.cherrypick.CherryPickStep
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStrategy
import dev.undine.testsupport.DETACHED_BASELINE
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * UND-38 이후 기록이 빠져 있던 여섯 경로가 **무엇을 어떤 전략으로** 남기는지 본다 (UND-79).
 *
 * 기준 상태·이전 지점은 전부 **Gateway 결과가 준 값**이어야 한다 (UND-73) — UseCase 가 변경 뒤에
 * 스스로 읽으면 그 사이의 다른 조작까지 반영된 상태가 기록돼, 되돌리기가 남의 변경 위에서 실행된다.
 */
class ChangeRecordingSpec : BehaviorSpec({

    given("스테이징 커밋") {
        `when`("커밋하면") {
            val harness = RecordingHarness()
            val outcome = harness.commitStaged.execute("메시지")

            then("변경 직전 HEAD 로 soft reset 하는 COMMIT 기록이 남는다") {
                outcome.undoRecordFailure shouldBe null
                val entry = harness.stack.history().single()
                entry.operation shouldBe GitOperationKind.COMMIT
                entry.strategy shouldBe UndoStrategy.SoftResetTo(BEFORE_CHANGE)
                // 기록에 실리는 기준 상태는 커밋 결과가 준 그 값이다.
                entry.baseline shouldBe BASELINE_AFTER
            }
        }

        `when`("첫 커밋이라 되돌릴 지점이 없으면") {
            val harness = RecordingHarness(
                staging = stagingGatewayCommitting(
                    result = CommitResult(AFTER_CHANGE, previousHead = null, baseline = BASELINE_AFTER),
                ),
            )
            harness.commitStaged.execute("첫 커밋")

            then("기록을 건너뛰지 않고 되돌릴 수 없다는 사유와 함께 남긴다") {
                val entry = harness.stack.history().single()
                entry.operation shouldBe GitOperationKind.COMMIT
                entry.irreversibleReason shouldBe entry.strategy
                    .shouldBeInstanceOf<UndoStrategy.Irreversible>().reason
            }
        }
    }

    given("amend") {
        `when`("확인이 필요 없어 곧바로 고치면") {
            val harness = RecordingHarness()
            val outcome = harness.amendCommit.request("고침")

            then("고치기 전 커밋으로 soft reset 하는 COMMIT 기록이 남는다") {
                outcome.shouldBeInstanceOf<AmendOutcome.Amended>()
                harness.stack.history().single().strategy shouldBe UndoStrategy.SoftResetTo(BEFORE_CHANGE)
            }
        }

        `when`("원격에 있어 확인만 요구하면") {
            val harness = RecordingHarness(staging = stagingGatewayCommitting(existsOnRemote = true))
            harness.amendCommit.request("고침")

            then("저장소를 바꾸지 않았으므로 기록도 남기지 않는다") {
                harness.stack.history().shouldBeEmpty()
            }
        }

        `when`("사용자 확인을 받아 고치면") {
            val harness = RecordingHarness(staging = stagingGatewayCommitting(existsOnRemote = true))
            harness.amendCommit.confirm("고침", BEFORE_CHANGE)

            then("확인 경로도 같은 COMMIT 기록을 남긴다") {
                harness.stack.history().single().operation shouldBe GitOperationKind.COMMIT
            }
        }
    }

    given("체크아웃") {
        `when`("브랜치에서 다른 브랜치로 옮기면") {
            val harness = RecordingHarness()
            val outcome = harness.checkoutBranch(RECORDED_TARGET)

            then("Gateway 가 준 이전 참조로 되돌아가는 CHECKOUT 기록이 남는다") {
                outcome.undoRecordFailure shouldBe null
                val entry = harness.stack.history().single()
                entry.operation shouldBe GitOperationKind.CHECKOUT
                entry.strategy shouldBe UndoStrategy.CheckoutRef(RECORDED_BRANCH)
                entry.baseline shouldBe BASELINE_AFTER
            }
        }

        `when`("옮기기 전이 브랜치가 아니었으면") {
            val harness = RecordingHarness(
                refs = refGatewayCheckingOut(CheckoutResult(previousRef = null, baseline = BASELINE_AFTER)),
            )
            harness.checkoutBranch(RECORDED_TARGET)

            then("다시 체크아웃할 이름이 없다는 사유와 함께 남긴다") {
                harness.stack.history().single().strategy.shouldBeInstanceOf<UndoStrategy.Irreversible>()
            }
        }
    }

    given("push") {
        `when`("원격이 수락하면") {
            val harness = RecordingHarness()
            val outcome = harness.pushRemote.execute(RECORDED_BRANCH, force = false) { }

            then("되돌릴 수 없다는 사유를 담은 PUSH 항목이 남는다") {
                outcome.result shouldBe PushResult.Accepted
                val entry = harness.stack.history().single()
                entry.operation shouldBe GitOperationKind.PUSH
                entry.irreversibleReason.shouldBeInstanceOf<String>()
            }
        }

        `when`("원격이 거절하면") {
            val rejected = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
            val harness = RecordingHarness(remote = remoteGatewayPushing(rejected))
            val outcome = harness.pushRemote.execute(RECORDED_BRANCH, force = false) { }

            then("원격이 바뀌지 않았으므로 이력에 남길 사건이 없다") {
                outcome.result shouldBe rejected
                harness.stack.history().shouldBeEmpty()
            }
        }
    }

    given("병합·리베이스·cherry-pick") {
        `when`("병합이 성공하면") {
            val harness = RecordingHarness()
            val outcome = harness.mergeBranch.execute(RECORDED_TARGET)

            then("결과가 준 브랜치·이전 위치·기대 위치로 MERGE 기록이 남는다") {
                outcome.undoRecordFailure shouldBe null
                val entry = harness.stack.history().single()
                entry.operation shouldBe GitOperationKind.MERGE
                entry.strategy shouldBe hardResetToBefore()
            }
        }

        `when`("빨리 감기로 병합이 끝나면") {
            val fastForward =
                MergeResult.Succeeded(AFTER_CHANGE, fastForward = true, BEFORE_CHANGE, BASELINE_AFTER)
            val harness = RecordingHarness(merge = mergeGatewayReturning(mergeResult = fastForward))
            harness.mergeBranch.execute(RECORDED_TARGET)

            then("병합 커밋이 없어도 되돌리기는 성립한다") {
                harness.stack.history().single().strategy shouldBe hardResetToBefore()
            }
        }

        `when`("병합이 충돌해 진행 중으로 남으면") {
            val conflicted = MergeResult.Conflicted(listOf("shared.txt"))
            val harness = RecordingHarness(merge = mergeGatewayReturning(mergeResult = conflicted))
            harness.mergeBranch.execute(RECORDED_TARGET)

            then("끝나지 않은 변경은 기록하지 않는다") {
                harness.stack.history().shouldBeEmpty()
            }
        }

        `when`("리베이스가 성공하면") {
            val harness = RecordingHarness()
            harness.rebaseBranch.execute(RECORDED_TARGET)

            then("시작 전 지점으로 되돌아가는 REBASE 기록이 남는다") {
                val entry = harness.stack.history().single()
                entry.operation shouldBe GitOperationKind.REBASE
                entry.strategy shouldBe hardResetToBefore()
            }
        }

        `when`("충돌을 해결하고 병합을 이어가면") {
            val harness = RecordingHarness(merge = mergeGatewayReturning(state = RepositoryState.MERGING))
            val outcome = harness.continueAfterResolve.execute(RepositoryState.MERGING)

            then("이어간 병합도 MERGE 로 남고 결과가 기록 실패 여부를 전달한다") {
                outcome.undoRecordFailure shouldBe null
                harness.stack.history().single().operation shouldBe GitOperationKind.MERGE
            }
        }

        `when`("충돌을 해결하고 리베이스를 이어가면") {
            val harness = RecordingHarness(merge = mergeGatewayReturning(state = RepositoryState.REBASING))
            harness.continueAfterResolve.execute(RepositoryState.REBASING)

            then("이어간 리베이스는 REBASE 로 남는다") {
                harness.stack.history().single().operation shouldBe GitOperationKind.REBASE
            }
        }

        `when`("cherry-pick 이 여러 커밋을 적용하면") {
            val harness = RecordingHarness()
            val outcome = harness.cherryPickCommits.execute(listOf(commitId(4), commitId(5)))

            then("묶음 전체를 한 항목으로 되돌리는 CHERRY_PICK 기록이 남는다") {
                outcome.undoRecordFailure shouldBe null
                val entry = harness.stack.history().single()
                entry.operation shouldBe GitOperationKind.CHERRY_PICK
                entry.strategy shouldBe hardResetToBefore()
            }
        }

        `when`("적용할 변경이 없어 cherry-pick 이 아무 커밋도 만들지 않으면") {
            val harness = RecordingHarness(cherryPick = cherryPickGatewayApplying(CherryPickStep.Empty))
            harness.cherryPickCommits.execute(listOf(commitId(4)))

            then("바뀐 것이 없으므로 기록도 없다") {
                harness.stack.history().shouldBeEmpty()
            }
        }

        `when`("대화형 리베이스 계획을 적용해 끝나면") {
            val harness = RecordingHarness()
            val outcome = harness.applyRebasePlan.execute(RECORDED_BRANCH, singleStepPlan())

            then("적용 직전 지점으로 되돌아가는 REBASE 기록이 남는다") {
                outcome.undoRecordFailure shouldBe null
                harness.stack.history().single().strategy shouldBe hardResetToBefore()
            }
        }

        `when`("대화형 리베이스가 편집으로 멈추면") {
            val stopped = InteractiveRebaseOutcome.StoppedForEdit(AFTER_CHANGE)
            val harness = RecordingHarness(interactiveRebase = interactiveRebaseGatewayApplying(stopped))
            harness.applyRebasePlan.execute(RECORDED_BRANCH, singleStepPlan())

            then("진행 중 상태는 되돌릴 대상이 아니므로 기록하지 않는다") {
                harness.stack.history().shouldBeEmpty()
            }
        }

        `when`("브랜치 위가 아니라 기대 위치를 확보하지 못하면") {
            val detached = RebaseResult.Succeeded(AFTER_CHANGE, BEFORE_CHANGE, DETACHED_BASELINE)
            val harness = RecordingHarness(merge = mergeGatewayReturning(rebaseResult = detached))
            harness.rebaseBranch.execute(RECORDED_TARGET)

            then("기본값으로 채우지 않고 되돌릴 수 없다는 사유로 남긴다") {
                harness.stack.history().single().strategy.shouldBeInstanceOf<UndoStrategy.Irreversible>()
            }
        }
    }

    given("여러 경로가 같은 세션 이력을 공유하는 상황") {
        `when`("커밋하고 체크아웃하고 push 하면") {
            val harness = RecordingHarness()
            harness.commitStaged.execute("메시지")
            harness.checkoutBranch(RECORDED_TARGET)
            harness.pushRemote.execute(RECORDED_BRANCH, force = false) { }

            then("세 항목이 모두 남는다 — 최신이 앞이다") {
                harness.stack.history().map { it.operation } shouldContainExactly listOf(
                    GitOperationKind.PUSH,
                    GitOperationKind.CHECKOUT,
                    GitOperationKind.COMMIT,
                )
            }
        }

        `when`("기록에 대상 이름이 실리면") {
            val harness = RecordingHarness()
            harness.checkoutBranch(RECORDED_TARGET)

            then("이력 패널이 보여줄 대상이 빈 칸이 아니다") {
                harness.stack.history().single().targetLabel shouldBe RECORDED_TARGET.value
                baselineOf(AFTER_CHANGE) shouldBe BASELINE_AFTER
            }
        }
    }
})

/** 이 스펙이 기대하는 되돌리기 — 결과가 준 브랜치·이전 위치·기대 위치를 그대로 쓴다 (결정 G5). */
private fun hardResetToBefore(): UndoStrategy.HardResetTo =
    UndoStrategy.HardResetTo(RECORDED_BRANCH, previous = BEFORE_CHANGE, expected = AFTER_CHANGE)
