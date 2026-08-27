package dev.undine.infrastructure.settings

import dev.undine.domain.AutomaticFetchSettings
import dev.undine.domain.PullStrategy

/** 상위 객체를 적는 [encodeSettings] 와 읽는 쪽이 함께 쓴다 — 키 문자열을 두 곳에 적지 않는다. */
internal const val KEY_DEFAULT_BRANCH_NAME = "defaultBranchName"
internal const val KEY_PULL_STRATEGY = "pullStrategy"
internal const val KEY_AUTOMATIC_FETCH = "automaticFetch"
internal const val KEY_TAB_WIDTH = "tabWidth"
internal const val KEY_MONOSPACE_FONT_FAMILY = "monospaceFontFamily"
internal const val KEY_LARGE_FILE_THRESHOLD_BYTES = "largeFileThresholdBytes"
internal const val KEY_COMMIT_PAGE_SIZE = "commitPageSize"

private const val KEY_AUTOMATIC_FETCH_ENABLED = "enabled"
private const val KEY_AUTOMATIC_FETCH_INTERVAL_MINUTES = "intervalMinutes"

/**
 * UND-74 가 넓힌 탭 값(Git·도구·고급)의 JSON 표현.
 *
 * [SettingsCodec] 에서 분리한 이유는 [SettingsPreferenceCodec] 과 같다 — 파일당 함수 상한(detekt
 * `TooManyFunctions`) 하나뿐이고, 스키마 규칙도 그쪽과 동일하다: 알 수 없는 키는 무시하고, 읽을 수
 * 없는 값은 **그 필드만** 기본값이 된다.
 *
 * **범위 밖의 값도 오류가 아니라 기본값이다.** `Settings` 는 범위 위반을 `require` 로 거부하므로,
 * 손으로 고친 파일의 0 이하 값을 그대로 넘기면 설정을 **아예 읽지 못하게** 된다. 거부는 화면이
 * 새 값을 저장할 때 걸려야 하는 것이지, 이미 디스크에 있는 파일을 못 읽게 만드는 일이 아니다 —
 * 업데이트 확인 주기([readUpdateCheck])가 세운 규약과 같다.
 *
 * **다만 그 보정은 손상된 파일에 대한 방어이지 앱이 만든 값을 정리하는 수단이 아니다.**
 * `Settings` 가 표현할 수 있는 값은 여기서 바꾸지 않는다 — 바꾸면 정상 왕복과 롤백 백업 복구에서
 * 사용자 값이 조용히 사라진다. 그래서 보정 대상은 `Settings` 가 애초에 거부하는 값뿐이다.
 */
internal fun encodeAutomaticFetch(automaticFetch: AutomaticFetchSettings): String =
    "{ \"$KEY_AUTOMATIC_FETCH_ENABLED\": ${automaticFetch.enabled}, " +
        "\"$KEY_AUTOMATIC_FETCH_INTERVAL_MINUTES\": ${automaticFetch.intervalMinutes} }"

/** 이름을 지을 수 없는 값(빈 문자열·공백·다른 타입)은 지정하지 않은 것으로 읽는다. */
internal fun readDefaultBranchName(value: Any?): String =
    (value as? String)?.takeIf(String::isNotBlank) ?: DEFAULT_SETTINGS.defaultBranchName

/** 우리가 모르는 방식 이름은 기본값으로 읽는다 — 테마([readTheme])와 같은 규약이다. */
internal fun readPullStrategy(value: Any?): PullStrategy =
    PullStrategy.entries.firstOrNull { it.name == value } ?: DEFAULT_SETTINGS.pullStrategy

/**
 * 주기가 뜻을 갖지 못하면(0 이하·타입 불일치) **둘 다** 기본값으로 되돌린다. 주기만 기본값으로
 * 바꾸면 사용자가 정하지 않은 주기로 원격을 두드리게 된다.
 *
 * 꺼진 상태의 주기도 같다 — [AutomaticFetchSettings] 는 [AutomaticFetchSettings.enabled] 와 무관하게
 * 주기가 양수임을 보장하므로, 0 이하는 앱이 만들 수 없는 값이고 되돌려도 잃을 사용자 값이 없다.
 */
internal fun readAutomaticFetch(value: Any?): AutomaticFetchSettings {
    val fields = value as? Map<*, *> ?: return DEFAULT_SETTINGS.automaticFetch
    val minutes = readPositiveInt(fields[KEY_AUTOMATIC_FETCH_INTERVAL_MINUTES])
    return minutes?.let {
        AutomaticFetchSettings(
            enabled = readBooleanOr(
                fields[KEY_AUTOMATIC_FETCH_ENABLED],
                DEFAULT_SETTINGS.automaticFetch.enabled,
            ),
            intervalMinutes = it,
        )
    } ?: DEFAULT_SETTINGS.automaticFetch
}

internal fun readTabWidth(value: Any?): Int = readPositiveInt(value) ?: DEFAULT_SETTINGS.tabWidth

/**
 * 서체 이름이 아닌 값(숫자·객체)만 지정하지 않은 것으로 읽는다 — 언어 태그([readLanguage])와 같은 규약이다.
 *
 * **빈 문자열·공백을 `null` 로 접지 않는다.** `Settings` 가 그 둘을 다른 뜻으로 구분하므로
 * (`null` 은 시스템 기본), 접으면 왕복과 롤백 백업 복구에서 사용자가 적은 값이 사라진다.
 */
internal fun readMonospaceFontFamily(value: Any?): String? = value as? String

internal fun readLargeFileThresholdBytes(value: Any?): Long =
    (value as? Long)?.takeIf { it > 0 } ?: DEFAULT_SETTINGS.largeFileThresholdBytes

internal fun readCommitPageSize(value: Any?): Int = readPositiveInt(value) ?: DEFAULT_SETTINGS.commitPageSize

/**
 * 양수 [Int] 로 읽을 수 있으면 그 값, 아니면 `null`.
 *
 * `Int` 범위 밖은 잘라 내지 않고 "값 없음" 으로 본다 — 잘라 내면 큰 양수가 음수로 둔갑한다.
 */
private fun readPositiveInt(value: Any?): Int? = (value as? Long)
    ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
    ?.toInt()
