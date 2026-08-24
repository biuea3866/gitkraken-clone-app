package dev.undine.presentation.commitdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import dev.undine.domain.ChangeType
import dev.undine.domain.FileChange
import dev.undine.presentation.design.ColorTokens
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.i18n.commitDetail
import dev.undine.presentation.i18n.strings

/**
 * 커밋이 바꾼 파일 목록.
 *
 * 행은 경로·변경 종류·증감 줄 수만 보여준다. **본문 diff 는 여기서 읽지 않는다** — 커밋을 고를 때마다
 * 전체 diff 를 계산하면 대형 커밋에서 화면이 멈춘다. 본문은 파일을 고른 뒤 diff 뷰어가 따로 읽는다.
 *
 * 목록은 수천 행이 될 수 있어 [LazyColumn] 에 경로를 key 로 준다 (compose-ui 규칙 3).
 */
@Composable
internal fun ChangedFileList(
    uiState: ChangedFilesUiState,
    modifier: Modifier = Modifier,
    onSelectFile: (String) -> Unit = {},
) {
    val texts = strings.commitDetail
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val spacing = UndineTokens.spacing

    Column(modifier = modifier) {
        BasicText(
            text = texts.changedFiles,
            style = typography.caption.copy(color = colors.foregroundTertiary),
            modifier = Modifier.padding(horizontal = spacing.medium, vertical = spacing.extraSmall),
        )
        when (uiState) {
            ChangedFilesUiState.Loading -> UndineEmptyState(
                message = texts.loading,
                modifier = Modifier.fillMaxWidth().mergedTestTag(CommitDetailTags.FILE_LOADING),
            )

            is ChangedFilesUiState.Failed -> UndineEmptyState(
                message = texts.loadFailed,
                modifier = Modifier.fillMaxWidth().mergedTestTag(CommitDetailTags.FILE_FAILED),
            )

            is ChangedFilesUiState.Loaded -> LoadedFiles(
                files = uiState.files,
                onSelectFile = onSelectFile,
            )
        }
    }
}

/** 빈 커밋도 정상 상태다 — 파일을 바꾸지 않은 커밋이 실패처럼 보이면 안 된다. */
@Composable
private fun LoadedFiles(files: List<FileChange>, onSelectFile: (String) -> Unit) {
    val texts = strings.commitDetail

    if (files.isEmpty()) {
        UndineEmptyState(
            message = texts.noChanges,
            modifier = Modifier.fillMaxWidth().mergedTestTag(CommitDetailTags.FILE_EMPTY),
            description = texts.noChangesDescription,
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().testTag(CommitDetailTags.FILE_LIST)) {
        items(items = files, key = { file -> file.path }) { file ->
            ChangedFileRow(file = file, onSelect = { onSelectFile(file.path) })
        }
    }
}

/**
 * 파일 한 행. 클릭 또는 포커스 후 Enter 로 고른다 — 선택은 diff 뷰어(UND-16)가 받을 상태를 갱신한다.
 *
 * 변경 종류는 색과 **글자**로 함께 표시한다: 색만으로 구분하면 색각 이상 사용자가 읽을 수 없다.
 */
@Composable
private fun ChangedFileRow(file: FileChange, onSelect: () -> Unit) {
    val texts = strings.commitDetail
    val colors = UndineTokens.color
    val typography = UndineTokens.typography

    UndineListRow(
        onClick = onSelect,
        modifier = Modifier.testTag(CommitDetailTags.fileRow(file.path)),
    ) {
        BasicText(
            text = texts.changeType(file.changeType),
            style = typography.caption.copy(color = changeTypeColorOf(file.changeType, colors)),
        )
        BasicText(
            text = file.path,
            style = typography.body.copy(color = colors.foregroundPrimary),
        )
        if (file.previousPath != null) {
            BasicText(
                text = texts.renamedFrom(file.previousPath),
                style = typography.caption.copy(color = colors.foregroundTertiary),
            )
        }
        BasicText(
            text = if (file.isBinary) texts.binary else texts.lineStats(file.addedLines, file.deletedLines),
            style = typography.mono.copy(color = colors.foregroundSecondary),
        )
    }
}

/**
 * 태그가 붙은 노드 하나로 자식 텍스트를 합친다 — 안내문은 제목과 설명이 나뉘어 있어도
 * 사용자에게도 테스트에게도 한 덩어리로 읽혀야 한다.
 */
private fun Modifier.mergedTestTag(tag: String): Modifier =
    testTag(tag).semantics(mergeDescendants = true) {
        // 자식 텍스트를 합치는 것 외에 더할 속성이 없다.
    }

/** 변경 종류의 강조색. 추가·삭제만 전용 색을 쓰고 나머지는 본문 색을 따른다. */
private fun changeTypeColorOf(changeType: ChangeType, colors: ColorTokens): Color = when (changeType) {
    ChangeType.ADDED -> colors.addition
    ChangeType.DELETED -> colors.deletion
    ChangeType.MODIFIED, ChangeType.RENAMED, ChangeType.COPIED -> colors.foregroundSecondary
}
