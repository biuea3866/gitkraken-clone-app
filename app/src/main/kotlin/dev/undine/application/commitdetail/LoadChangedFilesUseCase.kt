package dev.undine.application.commitdetail

import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.FileChange

/**
 * 선택한 커밋의 변경 파일 목록을 읽는다.
 *
 * **hunk 본문은 여기서 다루지 않는다** — 커밋을 고를 때마다 전체 diff 를 계산하면 대형 커밋에서
 * 화면이 멈춘다. hunk 는 파일을 고른 뒤 diff 뷰어가 따로 요청한다.
 *
 * 상세 패널이 표시할 커밋 메타(작성자·커미터·부모·메시지)는 조회하지 않는다 —
 * 그래프가 이미 읽어 둔 [dev.undine.domain.Commit] 을 선택 상태로 받는다 (wave 3 결정 A4).
 */
class LoadChangedFilesUseCase(private val diffGateway: DiffGateway) {

    /**
     * @param parentIndex 비교 기준이 될 부모. 병합 커밋에서 어느 부모와 비교할지 고르며,
     *   부모가 없는 최초 커밋도 0 이다 — 이때 Gateway 가 빈 트리와 비교해 전체 파일을 추가로 돌려준다.
     */
    suspend fun execute(commit: CommitId, parentIndex: Int): List<FileChange> =
        diffGateway.changedFiles(commit, parentIndex)
}
