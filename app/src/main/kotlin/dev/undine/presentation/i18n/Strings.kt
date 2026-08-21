package dev.undine.presentation.i18n

import java.text.MessageFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 한 로케일에 고정된 문자열 조회 API. 화면 코드는 네임스페이스 접근자
 * (`strings.common.ok`·`strings.time.relative(...)`)로 읽고, [text] 를 직접 부르는 것은
 * 인자 있는 리소스를 다루는 접근자 구현 쪽이다.
 *
 * 인스턴스는 [StringCatalog.stringsFor] 로 만든다. 로케일이 바뀌면 새 인스턴스를 만들어 교체한다 —
 * 이 객체 자체는 불변이다.
 */
class Strings internal constructor(
    val locale: Locale,
    private val catalog: StringCatalog,
    private val devBuild: Boolean,
) {
    /** 인자 없는 문자열. 현재 로케일에 없으면 기본 로케일 값으로 폴백한다. */
    fun text(key: StringKey): String = catalog.lookup(locale, key, devBuild)

    /**
     * 인자 치환과 수량별 복수형을 [MessageFormat] 으로 처리한다 — **문자열 이어붙이기를 쓰지 않는다.**
     * 복수형 규칙은 코드가 아니라 언어별 리소스 패턴에 들어 있다
     * (영어는 `choice` 로 단수/복수, 한국어는 복수 구분이 없어 한 형태).
     */
    fun text(key: StringKey, vararg arguments: Any?): String =
        MessageFormat(text(key), locale).format(arguments)

    /** 현재 로케일의 숫자 형식. */
    fun number(value: Number): String = NumberFormat.getNumberInstance(locale).format(value)

    /** 현재 로케일의 날짜 형식(중간 길이). */
    fun date(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(instant.atZone(zone))
}
