package dev.undine.presentation.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.undine.application.identity.IdentityUseCases
import dev.undine.domain.IdentityProfile
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PreferencesStrings
import dev.undine.presentation.i18n.commitDetail
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.strings

/**
 * 계정 탭 — 작성자 신원 프로필과 저장소별 매핑.
 *
 * 프로필 CRUD 는 설정 파일이 아니라 신원 서비스가 소유하므로 [identity] 를 추가로 받는다.
 * 화면 상태는 [AccountPreferencesState] 가 소유하고, 이 함수는 그것을 그리기만 한다.
 *
 * **[state] 는 이 탭이 읽지 않는다.** 계정 탭의 값은 전부 [IdentityUseCases] 경유이고, 공통 설정
 * 저장 경로를 쓰지 않는다. 인자를 지우면 골격이 고정한 여섯 탭의 시그니처가 깨진다.
 *
 * **사용 저장소 수 표시와 이메일 형식 검증은 이 탭의 몫이 아니다** — 세는 계약도 검증 계약도 없어
 * 화면이 만들면 소유 밖으로 나간다 (후속 티켓 UND-76).
 */
@Suppress("UnusedParameter")
@Composable
fun AccountPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    identity: IdentityUseCases,
    modifier: Modifier = Modifier,
) {
    AccountPreferencesContent(rememberAccountPreferencesState(identity), texts, modifier)
}

/**
 * 상태 홀더를 밖에서 받는 본체. 홀더를 만드는 자리와 그리는 자리를 나눠 두면 미리보기·검증이
 * 원하는 상태를 그대로 넣을 수 있다.
 */
@Composable
fun AccountPreferencesContent(
    account: AccountPreferencesState,
    texts: PreferencesStrings,
    modifier: Modifier = Modifier,
) {
    val spacing = UndineTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth().testTag(AccountPreferencesTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        SectionTitle(texts.identityProfiles)
        account.loadFailure?.let { AccountNotice(texts.loadFailed, AccountPreferencesTags.LOAD_FAILURE) }
        account.saveFailure?.let { AccountNotice(texts.saveFailed, AccountPreferencesTags.SAVE_FAILURE) }

        if (account.profiles.isEmpty()) {
            UndineEmptyState(
                message = texts.identityProfilesEmpty,
                modifier = Modifier.testTag(AccountPreferencesTags.PROFILE_EMPTY),
            )
        } else {
            // 프로필은 사용자가 손으로 만드는 몇 건이라 목록 가상화가 필요하지 않다.
            account.profiles.forEach { profile -> ProfileRow(profile, account, texts) }
        }

        AccountKeyboardActionButton(
            label = texts.profileAdd,
            onClick = account::startAdd,
            modifier = Modifier.testTag(AccountPreferencesTags.PROFILE_ADD),
        )

        account.pendingDeletion?.let { target -> DeleteConfirmation(target, account, texts) }
        account.editor?.let { editor -> ProfileEditor(editor, account, texts) }
        RepositoryMapping(account, texts)
    }
}

/** 프로필 한 건 — 이름·이메일과 서명 키, 그리고 이 저장소에 지정돼 있는지. */
@Composable
private fun ProfileRow(
    profile: IdentityProfile,
    account: AccountPreferencesState,
    texts: PreferencesStrings,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    val assigned = account.isAssigned(profile)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.small)
            .testTag(AccountPreferencesTags.PROFILE),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
            // 이름과 메일 주소를 잇는 서식은 로케일 리소스가 정한다 — 문자열을 이어붙이지 않는다.
            BasicText(
                text = strings.commitDetail.person(profile.name, profile.email),
                style = typography.body.copy(color = colors.foregroundPrimary),
            )
            BasicText(
                text = profile.signingKeyId ?: texts.signingKeyUnset,
                style = typography.caption.copy(color = colors.foregroundSecondary),
            )
            if (assigned) {
                BasicText(
                    text = texts.repositoryMapping,
                    style = typography.caption.copy(color = colors.accent),
                )
            }
        }
        AccountKeyboardActionButton(
            label = texts.profileEdit,
            onClick = { account.startEdit(profile) },
            modifier = Modifier.testTag(AccountPreferencesTags.PROFILE_EDIT),
        )
        AccountKeyboardActionButton(
            label = texts.profileDelete,
            onClick = { account.requestDelete(profile) },
            modifier = Modifier.testTag(AccountPreferencesTags.PROFILE_DELETE),
        )
        AccountKeyboardActionButton(
            label = texts.repositoryMapping,
            onClick = { account.assign(profile) },
            modifier = Modifier.testTag(AccountPreferencesTags.MAPPING_ASSIGN),
            enabled = account.canAssignProfile && !assigned,
        )
    }
}

/**
 * 삭제 확인. 되돌릴 수 없으므로 **무엇이 지워지는지 알리고 확인을 받은 뒤에만** 지운다 —
 * 확인 버튼 하나만이 [AccountPreferencesState.confirmDelete] 로 이어진다.
 */
@Composable
private fun DeleteConfirmation(
    target: IdentityProfile,
    account: AccountPreferencesState,
    texts: PreferencesStrings,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Column(
        modifier = Modifier.testTag(AccountPreferencesTags.DELETE_CONFIRMATION),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        BasicText(
            text = strings.commitDetail.person(target.name, target.email),
            style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        )
        BasicText(
            text = texts.profileDeleteConfirm,
            style = UndineTokens.typography.caption.copy(color = colors.warning),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            AccountKeyboardActionButton(
                label = texts.profileDelete,
                onClick = account::confirmDelete,
                modifier = Modifier.testTag(AccountPreferencesTags.DELETE_CONFIRM),
            )
            AccountKeyboardActionButton(
                label = strings.common.cancel,
                onClick = account::cancelDelete,
                modifier = Modifier.testTag(AccountPreferencesTags.DELETE_CANCEL),
            )
        }
    }
}

/**
 * 프로필 추가·수정 편집기.
 *
 * 이름과 이메일은 한 신원을 이루는 두 조각이라 **한 묶음**으로 보여 주고, 그 아래에 저장될 모양을
 * 그대로 미리 보인다 — 어느 칸이 무엇이 되는지 문구를 새로 만들지 않고 알린다.
 */
@Composable
private fun ProfileEditor(
    editor: AccountProfileEditor,
    account: AccountPreferencesState,
    texts: PreferencesStrings,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val identityLabel = strings.commitDetail.author

    Column(
        modifier = Modifier.testTag(AccountPreferencesTags.EDITOR),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        SectionTitle(editor.titleIn(texts))
        BasicText(
            text = identityLabel,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            AccountTextField(
                value = account.draftName,
                onValueChange = account::editName,
                label = identityLabel,
                tag = AccountPreferencesTags.EDITOR_NAME,
            )
            AccountTextField(
                value = account.draftEmail,
                onValueChange = account::editEmail,
                label = identityLabel,
                tag = AccountPreferencesTags.EDITOR_EMAIL,
            )
        }
        BasicText(
            text = strings.commitDetail.person(account.draftName, account.draftEmail),
            style = UndineTokens.typography.caption.copy(color = colors.foregroundTertiary),
        )
        BasicText(
            text = texts.signingKey,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        AccountTextField(
            value = account.draftSigningKeyId,
            onValueChange = account::editSigningKeyId,
            label = texts.signingKey,
            tag = AccountPreferencesTags.EDITOR_SIGNING_KEY,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            AccountKeyboardActionButton(
                label = strings.common.ok,
                onClick = account::submitEditor,
                modifier = Modifier.testTag(AccountPreferencesTags.EDITOR_SUBMIT),
                enabled = account.canSubmitEditor,
            )
            AccountKeyboardActionButton(
                label = strings.common.cancel,
                onClick = account::cancelEditor,
                modifier = Modifier.testTag(AccountPreferencesTags.EDITOR_CANCEL),
            )
        }
    }
}

/**
 * 현재 저장소에 지정된 프로필과 해제 경로.
 *
 * 저장소가 열려 있지 않으면 지정할 대상이 없으므로 이 자리를 만들지 않는다 — 눌러도 실패만 하는
 * 버튼을 내주지 않는다.
 */
@Composable
private fun RepositoryMapping(account: AccountPreferencesState, texts: PreferencesStrings) {
    if (!account.canAssignProfile) return
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Column(
        modifier = Modifier.testTag(AccountPreferencesTags.MAPPING),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        SectionTitle(texts.repositoryMapping)
        BasicText(
            text = account.assignedProfileName ?: texts.repositoryMappingUnset,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        AccountKeyboardActionButton(
            label = texts.repositoryMappingUnset,
            onClick = account::clearAssignment,
            modifier = Modifier.testTag(AccountPreferencesTags.MAPPING_CLEAR),
            enabled = account.assignedProfileName != null,
        )
    }
}

/**
 * 계정 탭의 주요 조작은 클릭과 Enter·Space가 같은 경로를 탄다. [UndineToolbarButton]의 클릭
 * 동작에만 기대면 공통 컴포넌트 구현이 바뀔 때 이 탭의 키보드 접근성이 조용히 사라질 수 있다.
 */
@Composable
private fun AccountKeyboardActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    UndineToolbarButton(
        label = label,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyUp) return@onKeyEvent false
            if (event.key != Key.Enter && event.key != Key.Spacebar) return@onKeyEvent false
            onClick()
            true
        },
    )
}

/**
 * 계정 탭의 한 줄 입력. `BasicTextField` 는 포커스를 받으면 키보드만으로 편집되므로 주요 동작에
 * 마우스 전용 경로가 생기지 않는다.
 */
@Composable
private fun AccountTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
) {
    val colors = UndineTokens.color

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .background(colors.background)
            .border(UndineTokens.shape.borderThin, colors.border)
            .padding(UndineTokens.spacing.small)
            .semantics { contentDescription = label }
            .testTag(tag),
        textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        cursorBrush = SolidColor(colors.foregroundPrimary),
        singleLine = true,
    )
}

@Composable
private fun SectionTitle(title: String) {
    BasicText(
        text = title,
        style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundPrimary),
    )
}

@Composable
private fun AccountNotice(message: String, tag: String) {
    BasicText(
        text = message,
        style = UndineTokens.typography.caption.copy(color = UndineTokens.color.warning),
        modifier = Modifier.testTag(tag),
    )
}
