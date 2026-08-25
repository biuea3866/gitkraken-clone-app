package dev.undine.infrastructure.settings

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UpdateCheckSettings

/** 상위 객체를 적는 [encodeSettings] 와 읽는 쪽이 함께 쓴다 — 키 문자열을 두 곳에 적지 않는다. */
internal const val KEY_LANGUAGE = "language"
internal const val KEY_REOPEN_LAST_REPOSITORY = "reopenLastRepository"
internal const val KEY_CONFIRM_DESTRUCTIVE_ACTIONS = "confirmDestructiveActions"
internal const val KEY_OPEN_TABS = "openTabs"
internal const val KEY_ACTIVE_TAB_INDEX = "activeTabIndex"
internal const val KEY_UPDATE_CHECK = "updateCheck"

private const val KEY_UPDATE_CHECK_ENABLED = "enabled"
private const val KEY_UPDATE_CHECK_INTERVAL_HOURS = "intervalHours"

private const val NULL_JSON = "null"

/** 활성 탭을 가리킬 수 없을 때 돌아가는 자리. 탭이 없으면 이 값도 아무것도 가리키지 않는다. */
private const val FIRST_TAB_INDEX = 0

/**
 * UND-63 이 넓힌 wave 8 필드(언어·시작 동작·확인 대화상자·탭 세션·업데이트 확인)의 JSON 표현.
 *
 * [SettingsCodec] 에서 분리한 이유는 [SettingsProfileCodec] 과 같다 — 파일당 함수 상한(detekt
 * `TooManyFunctions`) 하나뿐이고, 스키마 규칙은 그쪽과 동일하다: 알 수 없는 키는 무시하고,
 * 읽을 수 없는 값은 **그 필드만** 기본값이 된다.
 *
 * 범위를 벗어난 값(활성 탭 인덱스·확인 주기)은 **오류가 아니라 클램프**다. 사용자가 손으로 고쳤거나
 * 탭이 줄어든 파일 하나 때문에 설정 전체를 손상으로 몰지 않는다.
 */
internal fun encodeOpenTabs(openTabs: List<RepositoryPath>): String =
    openTabs.joinToString(", ") { jsonString(it.value) }

internal fun encodeLanguage(language: String?): String = language?.let(::jsonString) ?: NULL_JSON

internal fun encodeUpdateCheck(updateCheck: UpdateCheckSettings): String =
    "{ \"$KEY_UPDATE_CHECK_ENABLED\": ${updateCheck.enabled}, " +
        "\"$KEY_UPDATE_CHECK_INTERVAL_HOURS\": ${updateCheck.intervalHours} }"

/** 언어 태그가 아닌 값(숫자·객체)은 지정하지 않은 것으로 읽어 시스템 로케일을 따르게 둔다. */
internal fun readLanguage(value: Any?): String? = value as? String

internal fun readBooleanOr(value: Any?, fallback: Boolean): Boolean = value as? Boolean ?: fallback

/**
 * **중복 제거도 상한 절단도 하지 않는다** — 같은 저장소를 두 탭으로 여는 것은 사용자의 선택이고,
 * 경로가 사라진 탭도 목록에서 지우지 않는다(UND-44 요구). 최근 저장소 목록과 다른 점이 여기다.
 */
internal fun readOpenTabs(value: Any?): List<RepositoryPath> =
    (value as? List<*>)
        ?.filterIsInstance<String>()
        ?.map(::RepositoryPath)
        ?: DEFAULT_SETTINGS.openTabs

/**
 * 탭 목록 밖을 가리키는 인덱스는 첫 탭으로 되돌린다 — 탭이 줄어든 파일을 읽을 때 정상적으로 생긴다.
 * 탭이 하나도 없으면 가리킬 곳이 없어 같은 값이 된다.
 */
internal fun readActiveTabIndex(value: Any?, openTabs: List<RepositoryPath>): Int {
    val requested = (value as? Long)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        ?: return FIRST_TAB_INDEX
    return if (requested in openTabs.indices) requested else FIRST_TAB_INDEX
}

internal fun readUpdateCheck(value: Any?): UpdateCheckSettings {
    val fields = value as? Map<*, *> ?: return DEFAULT_SETTINGS.updateCheck
    return UpdateCheckSettings(
        enabled = readBooleanOr(fields[KEY_UPDATE_CHECK_ENABLED], DEFAULT_SETTINGS.updateCheck.enabled),
        intervalHours = readIntervalHours(fields[KEY_UPDATE_CHECK_INTERVAL_HOURS]),
    )
}

/** 범위 밖·타입 불일치는 기본 주기로 읽는다. 확인 주기 하나 때문에 설정을 못 읽는 편이 더 나쁘다. */
private fun readIntervalHours(value: Any?): Int {
    val requested = (value as? Long)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    return requested?.takeIf { it in UpdateCheckSettings.INTERVAL_HOURS_RANGE }
        ?: DEFAULT_SETTINGS.updateCheck.intervalHours
}
