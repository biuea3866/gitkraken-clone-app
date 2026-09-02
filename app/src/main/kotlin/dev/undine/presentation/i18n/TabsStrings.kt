package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `tabs.*` 네임스페이스 — 저장소 탭 막대와 탭 세션 복원의 문구.
 *
 * **아직 비어 있다.** UND-63 이 [builtInTranslations] 등록까지만 해 두고, 키 정의 object·접근자
 * value class·로케일별 번역은 UND-44(다중 저장소 탭)가 **이 파일 안에서만** 채운다. 공통 파일
 * (`BuiltInStrings.kt`)은 이미 등록돼 있으므로 건드리지 않는다 — 같은 wave 의 화면 7건이 그 파일을
 * 함께 고치면 머지 충돌이 난다.
 *
 * 채우는 모양은 [CommonStrings] 가 정본이다: [TABS_NAMESPACE] 로 키를 만들고, 번역 맵을
 * 로케일별로 채우고, `Strings.tabs` 확장 프로퍼티로 노출한다.
 *
 * 빈 맵은 병합에서 아무 키도 더하지 않으므로 등록만으로 카탈로그 동작이 달라지지 않는다.
 * 이 네임스페이스의 키를 지금 조회하면 다른 미등록 키와 똑같이 폴백한다.
 */
internal const val TABS_NAMESPACE: String = "tabs"

/** `tabs.*` 키 정의. */
object TabsKeys {
    val closeTab = StringKey("$TABS_NAMESPACE.closeTab")
    val closeTabConfirmation = StringKey("$TABS_NAMESPACE.closeTabConfirmation")
    val missingPath = StringKey("$TABS_NAMESPACE.missingPath")

    /**
     * 경로를 잃은 탭에서 저장소를 바꾸는 명령이 막힐 때의 사유 (결정 G43).
     *
     * **무엇이 왜 막혔는지와 다음 행동을 함께** 담는다 — "저장소를 찾을 수 없습니다" 로 끝내면
     * 사용자가 할 수 있는 일이 없고, 할 수 있는 일이 없는 안내는 안내가 아니다.
     */
    val unavailableRepository = StringKey("$TABS_NAMESPACE.unavailableRepository")
}

/** 탭 문구 접근자. UND-44 가 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class TabsStrings internal constructor(private val strings: Strings) {
    val closeTab: String get() = strings.text(TabsKeys.closeTab)
    val closeTabConfirmation: String get() = strings.text(TabsKeys.closeTabConfirmation)
    val missingPath: String get() = strings.text(TabsKeys.missingPath)
    val unavailableRepository: String get() = strings.text(TabsKeys.unavailableRepository)
}

/** 탭 문구 네임스페이스 진입점. */
val Strings.tabs: TabsStrings get() = TabsStrings(this)

internal val tabsTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        TabsKeys.closeTab to "탭 닫기",
        TabsKeys.closeTabConfirmation to "진행 중인 원격 작업이 있습니다. 탭을 닫을까요?",
        TabsKeys.missingPath to "경로를 찾을 수 없음",
        TabsKeys.unavailableRepository to
            "이 탭의 저장소 경로를 열 수 없습니다. 폴더가 옮겨졌거나 삭제되었을 수 있습니다. " +
            "탭을 닫거나, 저장소를 옮긴 위치에서 다시 여세요.",
    ),
    Locale.ENGLISH to mapOf(
        TabsKeys.closeTab to "Close tab",
        TabsKeys.closeTabConfirmation to "A remote operation is in progress. Close this tab?",
        TabsKeys.missingPath to "Path not found",
        TabsKeys.unavailableRepository to
            "This tab's repository path can't be opened. The folder may have been moved or deleted. " +
            "Close the tab, or open the repository again from its new location.",
    ),
)
