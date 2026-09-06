package dev.undine.presentation.patch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.Strings
import dev.undine.presentation.i18n.patch
import dev.undine.presentation.i18n.systemStrings

/**
 * 패치를 만들고 적용하는 화면.
 *
 * 두 절반을 나란히 둔다 — 방금 만든 패치를 곧바로 적용해 보는 흐름이 자연스럽고, 세로로 쌓으면
 * 적용 조작이 화면 밖으로 밀려난다.
 *
 * 화면은 [PatchState] 만 만지고 Gateway·JGit·AWT 대화상자를 직접 부르지 않는다. 파일 선택·저장은
 * [PatchFiles] 경계 뒤에 있고, 배선이 실제 구현을 준다.
 */
@Composable
fun PatchScreen(
    state: PatchState,
    modifier: Modifier = Modifier,
    strings: Strings = systemStrings(),
) {
    val copy = strings.patch
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag(PatchTags.ROOT)
            // 화면 어디에 놓아도 받는다 — 좁은 영역에만 열면 사용자가 놓을 자리를 찾아야 한다.
            .patchFileDropTarget(state::acceptDrop)
            .padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        BasicText(
            copy.title,
            style = UndineTokens.typography.title.copy(color = colors.foregroundPrimary),
        )
        Row(
            modifier = Modifier.fillMaxSize().testTag(PatchTags.DROP_AREA),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            PatchCreateSection(
                state = state,
                copy = copy,
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            )
            PatchApplySection(
                state = state,
                copy = copy,
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            )
        }
    }
}
