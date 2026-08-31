package dev.undine.testsupport

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.ChangeRecordingOrder
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk

/** 기록 검증에 쓰는 기본 브랜치. 실제 Git 동작은 각 `*ImplSpec` 이 임시 저장소로 본다. */
val FIXTURE_BRANCH: RefName = RefName("main")

/**
 * 변경 Gateway 가 **변경과 같은 임계 구역에서** 캡처해 결과에 실어 주는 기준 상태 자리 (UND-73).
 * 단위 테스트의 Gateway 는 대역이라 실제로 캡처할 곳이 없으므로 고정값을 흘려보낸다.
 */
fun baselineOf(head: CommitId, branch: RefName? = FIXTURE_BRANCH): RepositoryBaseline =
    RepositoryBaseline(branch = branch, head = head)

/** 브랜치 위가 아닌 기준 상태 — 되돌릴 지점을 확보하지 못하는 경계를 재현한다. */
val DETACHED_BASELINE: RepositoryBaseline = RepositoryBaseline(branch = null, head = null)

/**
 * 기록만 검증하는 테스트용 [OperationRecorder].
 *
 * `RefGateway` 는 복구 불가 기록이 읽는 기준 상태만 답하면 되므로 대역이다 — 되돌릴 수 있는 기록은
 * 호출자가 넘긴 baseline 을 그대로 쓰므로 이 대역에 닿지 않는다 (결정 G9).
 */
fun recorderOf(
    stack: UndoStack,
    head: CommitId = commitId(1),
    changeRecordingOrder: ChangeRecordingOrder? = null,
): OperationRecorder {
    val refGateway = mockk<RefGateway>()
    coEvery { refGateway.listBranches() } returns listOf(
        Branch(
            name = FIXTURE_BRANCH,
            target = head,
            isCurrent = true,
            isRemote = false,
            upstream = null,
            ahead = 0,
            behind = 0,
        ),
    )
    return OperationRecorder(refGateway, stack, changeRecordingOrder = changeRecordingOrder)
}

/**
 * 기록 호출을 검증·차단할 수 있는 대역.
 *
 * **통짜 mock 이 아니라 spy 다.** [OperationRecorder.recordQuietly] 는 "기록 실패를 변경 실패로
 * 승격하지 않는다" 는 계약 자체이므로 실제 구현이 돌아야 한다 — mock 으로 덮으면 그 안의
 * `record`·`recordIrreversible` 호출이 사라져 검증이 통과해도 아무것도 증명하지 못한다.
 */
fun spyRecorderOf(stack: UndoStack = UndoStack()): OperationRecorder = spyk(recorderOf(stack))
