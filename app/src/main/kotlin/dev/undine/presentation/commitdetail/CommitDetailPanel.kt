package dev.undine.presentation.commitdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.presentation.design.UndineTokens

/**
 * 선택한 커밋의 메타와 변경 파일 목록.
 *
 * **커밋 자체는 조회하지 않는다** — 그래프(UND-14)가 이미 읽어 둔 [Commit] 을 받는다
 * (wave 3 결정 A4). 패널이 스스로 읽는 것은 변경 파일 목록 하나뿐이고, 그 경로도
 * `application/commitdetail` 의 UseCase 를 거친다 — presentation 은 Gateway 를 알지 못한다.
 *
 * 셸 연결(선택 콜백을 `AppShellState` 에 잇는 일)은 UND-26 이 한다. 여기서는 콜백 파라미터로
 * 열어 두기만 한다 (compose-ui 규칙 1, 상태 끌어올리기).
 *
 * 시각은 사용자의 시스템 시간대로 보여준다 — 표시 시간대를 고르는 설정은 `Settings` 에 없다.
 *
 * @param onSelectFile 고른 파일 경로. diff 뷰어(UND-16)가 받을 선택 상태를 갱신한다.
 * @param onSelectParentCommit 부모 커밋 링크를 눌렀을 때 이동할 대상.
 */
@Composable
fun CommitDetailPanel(
    commit: Commit,
    state: CommitDetailState,
    modifier: Modifier = Modifier,
    onSelectFile: (String) -> Unit = {},
    onSelectParentCommit: (CommitId) -> Unit = {},
) {
    val baseParentIndex = state.baseParentIndexOf(commit)

    // 커밋이나 기준 부모가 바뀔 때만 다시 읽는다 — 재구성마다 읽으면 대형 커밋에서 화면이 멈춘다.
    LaunchedEffect(commit.id, baseParentIndex) {
        state.load(commit.id, baseParentIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UndineTokens.color.background)
            .testTag(CommitDetailTags.ROOT),
    ) {
        CommitMetaSection(
            commit = commit,
            state = state,
            modifier = Modifier.fillMaxWidth(),
            onSelectParentCommit = onSelectParentCommit,
        )
        ChangedFileList(
            uiState = state.changedFilesOf(commit.id, baseParentIndex),
            modifier = Modifier.fillMaxWidth().weight(1f),
            onSelectFile = onSelectFile,
        )
    }
}
