package dev.undine.application.diff

import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.DiffResult

/** 병합 커밋에서 기본으로 비교하는 부모 — 그래프 정렬과 같은 축인 첫 부모다. */
const val FIRST_PARENT_INDEX: Int = 0

/**
 * 커밋 안의 파일 하나에 대한 diff 조회.
 *
 * presentation 이 `DiffGateway` 를 직접 주입받지 않도록 두는 얇은 경유 지점이다
 * (architecture-layers 규칙 3·4). 판단은 하지 않는다 — `NotComputed` 사유도, 조회 실패 예외도
 * 그대로 올려 화면이 구분해 처리한다.
 */
class LoadFileDiffUseCase(private val diffGateway: DiffGateway) {

    suspend fun execute(
        commit: CommitId,
        path: String,
        parentIndex: Int = FIRST_PARENT_INDEX,
    ): DiffResult = diffGateway.hunksOf(commit, path, parentIndex)
}
