package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "shell"

/**
 * `shell.*` 키 정의. 앱 셸이 노출하는 문구는 분할선 조작 설명뿐이다 —
 * 영역 안의 문구는 각 화면 티켓이 자기 네임스페이스에 둔다.
 */
object ShellKeys {
    val sidebarSplitter = StringKey("$NAMESPACE.sidebarSplitter")
    val bottomSplitter = StringKey("$NAMESPACE.bottomSplitter")
}

/**
 * 셸 문구 접근자. `strings.shell.sidebarSplitter` 로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 여러 티켓이 한 줄씩 고치면 충돌하는
 * 공용 파일이라 등록을 UND-26 이 일괄로 한다 (wave 3 결정 A3). 그때까지 앱에서는 이 키가
 * 폴백(키 이름)으로 표시되며, 표시 대상이 화면 텍스트가 아니라 접근성 설명이라 감수 가능한 상태다.
 */
@JvmInline
value class ShellStrings internal constructor(private val strings: Strings) {
    val sidebarSplitter: String get() = strings.text(ShellKeys.sidebarSplitter)
    val bottomSplitter: String get() = strings.text(ShellKeys.bottomSplitter)
}

/** 셸 문구 네임스페이스 진입점. */
val Strings.shell: ShellStrings get() = ShellStrings(this)

internal val shellTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        ShellKeys.sidebarSplitter to "사이드바 크기 조절",
        ShellKeys.bottomSplitter to "하단 영역 크기 조절",
    ),
    Locale.ENGLISH to mapOf(
        ShellKeys.sidebarSplitter to "Resize sidebar",
        ShellKeys.bottomSplitter to "Resize bottom panel",
    ),
)
