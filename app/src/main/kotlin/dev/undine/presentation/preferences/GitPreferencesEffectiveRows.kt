package dev.undine.presentation.preferences

import dev.undine.domain.Settings
import dev.undine.domain.gitconfig.EffectiveValue
import dev.undine.domain.gitconfig.GitConfigKey
import dev.undine.domain.gitconfig.GitConfigSource
import dev.undine.presentation.i18n.PreferencesStrings

/**
 * Git 탭의 **앱 설정과 git 설정 결합** — 어느 값이 실효값인지 정하는 자리.
 *
 * `GitConfigGateway` 는 Git 만 알고 앱 `Settings` 를 모른다 (결정 G34 UND-75 1). 그래서 결합은
 * 소비자인 화면이 하고, 그 규칙을 Composable 밖 순수 함수로 둬 테스트가 화면을 띄우지 않고 판정한다.
 */

/**
 * git 설정 범위를 드러내는 출처 문구. 전역과 시스템을 뭉뚱그리지 않는다 — 어느 파일을 고쳐야
 * 값이 바뀌는지 말할 수 없으면 사용자는 행동할 수 없다 (결정 G35 UND-75 1).
 */
internal fun GitConfigSource.labelIn(texts: PreferencesStrings): String = when (this) {
    GitConfigSource.REPOSITORY -> texts.sourceGitRepository
    GitConfigSource.GLOBAL -> texts.sourceGitGlobal
    GitConfigSource.SYSTEM -> texts.sourceGitSystem
}

/**
 * 앱 설정 행 위에 git 실효값을 겹친다.
 *
 * [key] 의 값이 있으면 그 값이 실제로 적용되는 값이므로 값·출처를 git 쪽으로 바꾼다. 값이 없을 때
 * 앱 설정 행을 그대로 쓰는 것은 **[GitEffectiveConfig.Loaded] 뿐**이다 — git 세 범위를 실제로 읽어
 * 봤을 때만 "모두에 없음" 이 성립하기 때문이다 (결정 G39). 아직 안 읽었거나 읽는 중이면
 * [notYetKnown], 읽기에 실패했으면 [unverifiable] 로 간다.
 *
 * git 이 이기는 동안에는 항목별 기본값 복원을 내주지 않는다. 앱 값을 기본값으로 되돌려도 보이는
 * 값이 바뀌지 않아, 눌러도 아무 일이 없는 버튼이 된다.
 *
 * @param display git 값의 raw 문자열을 사람이 읽는 문구로 옮긴다. Git 철자 해석은 domain 의
 *   [EffectiveValue] 가 하고, 이 함수는 그 결과에 문자열 리소스를 붙일 뿐이다.
 */
internal fun PreferencesRow.overriddenBy(
    effective: GitEffectiveConfig,
    key: GitConfigKey,
    texts: PreferencesStrings,
    display: (EffectiveValue) -> String = EffectiveValue::raw,
): PreferencesRow {
    val value = effective[key] ?: return when (effective) {
        is GitEffectiveConfig.Loaded -> this
        GitEffectiveConfig.Unread, is GitEffectiveConfig.Loading -> notYetKnown(texts)
        is GitEffectiveConfig.Failed -> unverifiable(texts)
    }

    return copy(
        value = display(value),
        source = PreferenceValueSource.GIT_CONFIG,
        sourceLabel = value.source.labelIn(texts),
        restorablePreference = null,
    )
}

/**
 * 아직 git 설정을 읽어 보지 않은 동안의 행 — 값도 출처도 말하지 않는다 (결정 G39).
 *
 * **앱 설정 값으로 대신 채우지 않는다.** 최초 렌더와 저장소 전환 직후에 앱 값을 앱 출처로 스쳐
 * 보여 주면, 설정을 확인하러 온 사용자가 전역 설정이 적용되는 항목을 앱 설정으로 읽는다.
 * 값은 편집기가 계속 들고 있으므로 사용자가 고칠 수단을 잃지도 않는다.
 */
private fun PreferencesRow.notYetKnown(texts: PreferencesStrings): PreferencesRow = copy(
    value = "",
    source = PreferenceValueSource.PENDING,
    sourceLabel = texts.sourcePending,
)

/**
 * git 설정을 읽지 못한 행. 앱 설정 값은 계속 보여 주되 **출처를 앱이라고 말하지 않는다** —
 * 세 범위 중 어디에 값이 있는지 확인하지 못했으므로 이 값이 실효값이라는 근거가 없다. 읽기 실패를
 * 부재로 접으면 손상된 설정 파일이 사용자에게 "git 에 설정 없음" 으로 보인다 (결정 G35 UND-75 2).
 *
 * 되돌리기는 그대로 내준다 — 앱 값은 여전히 사용자의 것이고 되돌리면 보이는 값이 실제로 바뀐다.
 */
private fun PreferencesRow.unverifiable(texts: PreferencesStrings): PreferencesRow = copy(
    source = PreferenceValueSource.UNVERIFIED,
    sourceLabel = texts.sourceUnverified,
)

/** 기본 브랜치 이름 행 — `init.defaultBranch` 가 있으면 그 값이 실효값이다. */
internal fun effectiveDefaultBranchNameRow(
    settings: Settings,
    effective: GitEffectiveConfig,
    texts: PreferencesStrings,
): PreferencesRow = defaultBranchNameRow(settings, texts)
    .overriddenBy(effective, GitConfigKey.INIT_DEFAULT_BRANCH, texts)

/**
 * pull 방식 행 — `pull.rebase` 가 있으면 그 값이 실효값이다.
 *
 * 참·거짓 해석은 domain 의 [EffectiveValue.asBoolean] 이 한다. 해석할 수 없는 철자는 **판단 생략**이라
 * raw 값을 그대로 보여 준다 — 읽을 수 없는 값을 merge 로 단정하면 사용자가 반대로 안다.
 */
internal fun effectivePullStrategyRow(
    settings: Settings,
    effective: GitEffectiveConfig,
    texts: PreferencesStrings,
): PreferencesRow = pullStrategyRow(settings, texts)
    .overriddenBy(effective, GitConfigKey.PULL_REBASE, texts) { value ->
        when (value.asBoolean()) {
            true -> texts.pullStrategyRebase
            false -> texts.pullStrategyMerge
            null -> value.raw
        }
    }
