package dev.undine.presentation.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.undine.domain.AutomaticFetchSettings
import dev.undine.domain.PullStrategy
import dev.undine.domain.Settings
import dev.undine.domain.SettingsPreference
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PreferencesStrings

/**
 * 숫자로 읽히지 않는 주기 입력이 대신 넘기는 값.
 *
 * 탭이 "숫자가 아님" 을 따로 판정해 자기 오류를 만들지 않는다 — 양수가 아닌 값과 결과가 같으므로
 * 같은 경로(domain 거부 → [PreferencesSaveFailure.Rejected])로 보낸다. 검증을 탭에 흩지 않는다.
 */
private const val UNREADABLE_INTERVAL_MINUTES: Int = 0

/**
 * Git 탭 — 기본 브랜치명·pull 방식·자동 fetch(켬·끔과 분 단위 주기), 그리고 커밋 서명 실효값.
 *
 * **앱 설정값의 편집·표시까지만 한다.** 저장소 `git config` 의 실효값·출처 표시는 그 계약이 아직
 * 없어 후속 티켓(UND-75) 몫이고, 저장된 값을 실제 fetch 스케줄에 연결하는 소비 경로도 별도 티켓이다.
 *
 * **서명은 읽기 전용이다.** 앱이 서명 설정의 사본을 두면 사용자가 `git config` 로 바꾼 값과
 * 어긋나므로, 이 탭은 [signingPreferencesRows] 가 주는 실효값을 보여 주기만 하고 토글을 달지 않는다.
 *
 * **검증을 탭에 두지 않는다.** 빈 브랜치명·양수가 아닌 주기는 `Settings` 생성이 거부해 저장 자체가
 * 일어나지 않고, [PreferencesState] 가 그 거부를 사유와 함께 화면에 내보낸다. 그래서 편집기는
 * 화면이 들고 있는 값만 그리고 **입력을 낙관적으로 보여 주지 않는다** — 거부된 값은 화면에 남지 않는다.
 *
 * 시그니처 고정 이유는 [GeneralPreferencesContent] 와 같다. Git 탭은 추가 의존이 없다.
 */
@Composable
fun GitPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(GitPreferencesTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
    ) {
        // 빈 이름은 domain 이 거부하므로 탭은 입력을 그대로 넘긴다.
        PreferencesRowItem(defaultBranchNameRow(settings, texts), state::restoreDefault) {
            GitTextEditor(
                value = settings.defaultBranchName,
                label = texts.defaultBranchName,
                tag = GitPreferencesTags.DEFAULT_BRANCH_NAME,
                onValueChange = { name -> state.apply { it.copy(defaultBranchName = name) } },
            )
        }
        // 지금 고른 pull 방식은 행이 보여 주고, 두 버튼은 그 값을 바꾼다.
        PreferencesRowItem(pullStrategyRow(settings, texts), state::restoreDefault) {
            Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
                UndineToolbarButton(
                    label = texts.pullStrategyMerge,
                    onClick = { state.apply { it.copy(pullStrategy = PullStrategy.MERGE) } },
                    modifier = Modifier.testTag(GitPreferencesTags.PULL_STRATEGY_MERGE),
                )
                UndineToolbarButton(
                    label = texts.pullStrategyRebase,
                    onClick = { state.apply { it.copy(pullStrategy = PullStrategy.REBASE) } },
                    modifier = Modifier.testTag(GitPreferencesTags.PULL_STRATEGY_REBASE),
                )
            }
        }
        AutomaticFetchSection(state, texts)
        // 서명은 git 설정이 이기는 항목이라 편집기를 달지 않는다 — 행 자체가 복원 버튼도 내주지 않는다.
        signingPreferencesRows(state.signing, texts).forEach { row ->
            PreferencesRowItem(row, state::restoreDefault)
        }
    }
}

/**
 * 자동 fetch — 켬·끔과 분 단위 주기.
 *
 * 두 행을 한 자리에서 그린다. 저장 계약이 둘을 한 단위([AutomaticFetchSettings])로 묶고 항목별
 * 복원도 하나뿐이라, 나눠 두면 같은 항목이 두 곳에서 갱신되는 것처럼 읽힌다.
 *
 * 꺼도 주기 값은 그대로 둬 다시 켤 때 되찾을 값을 잃지 않고, 꺼져 있는 동안에는 주기 입력만 닫는다.
 */
@Composable
private fun AutomaticFetchSection(state: PreferencesState, texts: PreferencesStrings) {
    val settings = state.settings

    PreferencesRowItem(automaticFetchRow(settings, texts), state::restoreDefault) {
        Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
            UndineToolbarButton(
                label = texts.enabled,
                onClick = { state.apply { it.withAutomaticFetchEnabled(true) } },
                modifier = Modifier.testTag(GitPreferencesTags.AUTOMATIC_FETCH_ON),
            )
            UndineToolbarButton(
                label = texts.disabled,
                onClick = { state.apply { it.withAutomaticFetchEnabled(false) } },
                modifier = Modifier.testTag(GitPreferencesTags.AUTOMATIC_FETCH_OFF),
            )
        }
    }
    PreferencesRowItem(automaticFetchIntervalRow(settings, texts), state::restoreDefault) {
        GitTextEditor(
            value = settings.automaticFetch.intervalMinutes.toString(),
            label = texts.automaticFetchInterval,
            tag = GitPreferencesTags.AUTOMATIC_FETCH_INTERVAL,
            enabled = settings.acceptsFetchInterval,
            onValueChange = { minutes -> state.apply { it.withAutomaticFetchInterval(minutes) } },
        )
    }
}

/**
 * 한 줄 입력 편집기.
 *
 * 값을 [remember] 로 들고 있지 않다 — 화면 값은 저장·읽기 결과로만 갱신되므로 초안을 따로 두면
 * 저장되지 않은 값이 화면에 남는다. 라벨은 행이 이미 그리므로 여기서는 스크린 리더용으로만 붙인다.
 */
@Composable
private fun GitTextEditor(
    value: String,
    label: String,
    tag: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val spacing = UndineTokens.spacing

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = UndineTokens.typography.body.copy(
            color = if (enabled) colors.foregroundPrimary else colors.foregroundTertiary,
        ),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier
            .background(colors.background)
            .border(shape.borderThin, colors.border, RoundedCornerShape(shape.cornerSmall))
            .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
            .semantics { contentDescription = label }
            .testTag(tag),
    )
}

/** Git 탭 편집기를 가리키는 테스트 태그. 탭마다 공용 태그 목록을 늘리지 않으려고 여기 둔다. */
internal object GitPreferencesTags {
    const val ROOT: String = "preferences.git"
    const val DEFAULT_BRANCH_NAME: String = "preferences.git.defaultBranchName"
    const val PULL_STRATEGY_MERGE: String = "preferences.git.pullStrategy.merge"
    const val PULL_STRATEGY_REBASE: String = "preferences.git.pullStrategy.rebase"
    const val AUTOMATIC_FETCH_ON: String = "preferences.git.automaticFetch.on"
    const val AUTOMATIC_FETCH_OFF: String = "preferences.git.automaticFetch.off"
    const val AUTOMATIC_FETCH_INTERVAL: String = "preferences.git.automaticFetch.interval"
}

/** 새 저장소·클론에 쓸 기본 브랜치 이름 행. */
internal fun defaultBranchNameRow(settings: Settings, texts: PreferencesStrings): PreferencesRow =
    appPreferencesRow(
        label = texts.defaultBranchName,
        value = settings.defaultBranchName,
        preference = SettingsPreference.DEFAULT_BRANCH_NAME,
        texts = texts,
    )

/** pull 방식 행. `when` 이 전수라 방식이 늘면 컴파일러가 빠진 문구를 잡는다. */
internal fun pullStrategyRow(settings: Settings, texts: PreferencesStrings): PreferencesRow =
    appPreferencesRow(
        label = texts.pullStrategy,
        value = when (settings.pullStrategy) {
            PullStrategy.MERGE -> texts.pullStrategyMerge
            PullStrategy.REBASE -> texts.pullStrategyRebase
        },
        preference = SettingsPreference.PULL_STRATEGY,
        texts = texts,
    )

/** 자동 fetch 의 켬·끔 행. 주기는 [automaticFetchIntervalRow] 가 따로 보여 준다. */
internal fun automaticFetchRow(settings: Settings, texts: PreferencesStrings): PreferencesRow =
    appPreferencesRow(
        label = texts.automaticFetch,
        value = settings.automaticFetch.enabled.asOnOff(texts),
        preference = SettingsPreference.AUTOMATIC_FETCH,
        texts = texts,
    )

/**
 * 분 단위 fetch 주기 행.
 *
 * 켬·끔 행과 같은 [SettingsPreference.AUTOMATIC_FETCH] 를 되돌린다 — 저장 계약이 두 값을 한 단위로
 * 묶고 있으므로 한쪽만 되돌리는 표현이 없다.
 */
internal fun automaticFetchIntervalRow(settings: Settings, texts: PreferencesStrings): PreferencesRow =
    appPreferencesRow(
        label = texts.automaticFetchInterval,
        value = settings.automaticFetch.intervalMinutes.toString(),
        preference = SettingsPreference.AUTOMATIC_FETCH,
        texts = texts,
    )

/** 지금 주기 입력을 받는가. 자동 fetch 가 꺼져 있으면 값은 남기고 입력만 닫는다. */
internal val Settings.acceptsFetchInterval: Boolean
    get() = automaticFetch.enabled

/** 자동 fetch 켬·끔. 주기는 건드리지 않는다 — 다시 켤 때 되찾을 값이다. */
internal fun Settings.withAutomaticFetchEnabled(enabled: Boolean): Settings =
    copy(automaticFetch = automaticFetch.copy(enabled = enabled))

/**
 * 주기 입력을 저장 값으로 옮긴다.
 *
 * 숫자로 읽히지 않는 입력은 [UNREADABLE_INTERVAL_MINUTES] 로 넘겨 양수가 아닌 값과 같이
 * domain 이 거부하게 한다 — 탭이 자기 검증·자기 오류 문구를 만들지 않는다.
 */
internal fun Settings.withAutomaticFetchInterval(text: String): Settings = copy(
    automaticFetch = AutomaticFetchSettings(
        enabled = automaticFetch.enabled,
        intervalMinutes = text.trim().toIntOrNull() ?: UNREADABLE_INTERVAL_MINUTES,
    ),
)
