package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "common"

/**
 * `common.*` 키 정의. 여러 화면이 공유하는 버튼·동작 문구만 둔다 —
 * 화면 고유 문구는 각 UI 티켓이 자기 네임스페이스(`graph.*`·`diff.*`)에 추가한다.
 */
object CommonKeys {
    val ok = StringKey("$NAMESPACE.ok")
    val cancel = StringKey("$NAMESPACE.cancel")
    val retry = StringKey("$NAMESPACE.retry")
    val close = StringKey("$NAMESPACE.close")
}

/**
 * 공통 문구 접근자. `strings.common.ok` 로 읽는다.
 *
 * 새 네임스페이스는 이 파일 구조를 따라간다 — 키 정의 object, 접근자 value class,
 * [Strings] 확장 프로퍼티, 로케일별 번역 맵을 **자기 파일에** 두고
 * [builtInTranslations] 에 한 줄 추가한다. 이 티켓의 파일은 건드리지 않는다.
 */
@JvmInline
value class CommonStrings internal constructor(private val strings: Strings) {
    val ok: String get() = strings.text(CommonKeys.ok)
    val cancel: String get() = strings.text(CommonKeys.cancel)
    val retry: String get() = strings.text(CommonKeys.retry)
    val close: String get() = strings.text(CommonKeys.close)
}

/** 공통 문구 네임스페이스 진입점. */
val Strings.common: CommonStrings get() = CommonStrings(this)

internal val commonTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        CommonKeys.ok to "확인",
        CommonKeys.cancel to "취소",
        CommonKeys.retry to "다시 시도",
        CommonKeys.close to "닫기",
    ),
    Locale.ENGLISH to mapOf(
        CommonKeys.ok to "OK",
        CommonKeys.cancel to "Cancel",
        CommonKeys.retry to "Retry",
        CommonKeys.close to "Close",
    ),
)
