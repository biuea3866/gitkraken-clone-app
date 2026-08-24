package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "palette"

/**
 * `palette.*` 키 정의. 커맨드 팔레트 자신이 노출하는 문구만 둔다 —
 * 명령 표시명과 실행 불가 사유는 명령을 등록하는 기능 티켓이 자기 네임스페이스에서 만든다.
 */
object PaletteKeys {
    val searchPlaceholder = StringKey("$NAMESPACE.searchPlaceholder")
    val noCommands = StringKey("$NAMESPACE.empty.noCommands")
    val noResults = StringKey("$NAMESPACE.empty.noResults")
}

/**
 * 팔레트 문구 접근자. `strings.palette.searchPlaceholder` 로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 여러 티켓이 한 줄씩 고치면 충돌하는
 * 공용 파일이라 등록을 UND-26 이 일괄로 한다 (wave 3 결정 A3).
 */
@JvmInline
value class PaletteStrings internal constructor(private val strings: Strings) {
    val searchPlaceholder: String get() = strings.text(PaletteKeys.searchPlaceholder)
    val noCommands: String get() = strings.text(PaletteKeys.noCommands)
    val noResults: String get() = strings.text(PaletteKeys.noResults)
}

/** 팔레트 문구 네임스페이스 진입점. */
val Strings.palette: PaletteStrings get() = PaletteStrings(this)

internal val paletteTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        PaletteKeys.searchPlaceholder to "명령 검색",
        PaletteKeys.noCommands to "등록된 명령이 없습니다",
        PaletteKeys.noResults to "일치하는 명령이 없습니다",
    ),
    Locale.ENGLISH to mapOf(
        PaletteKeys.searchPlaceholder to "Search commands",
        PaletteKeys.noCommands to "No commands registered",
        PaletteKeys.noResults to "No matching commands",
    ),
)
