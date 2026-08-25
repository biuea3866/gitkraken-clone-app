package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `blame.*` 네임스페이스 — 라인별 작성자 뷰와 파일 변경 이력 화면의 문구.
 *
 * **아직 비어 있다.** UND-63 이 [builtInTranslations] 등록까지만 해 두고, 키 정의 object·접근자
 * value class·로케일별 번역은 UND-41(Blame · 파일 이력 화면)이 **이 파일 안에서만** 채운다.
 * 공통 파일(`BuiltInStrings.kt`)은 이미 등록돼 있으므로 건드리지 않는다 — 같은 wave 의 화면 7건이
 * 그 파일을 함께 고치면 머지 충돌이 난다.
 *
 * 채우는 모양은 [CommonStrings] 가 정본이다: [BLAME_NAMESPACE] 로 키를 만들고, 번역 맵을
 * 로케일별로 채우고, `Strings.blame` 확장 프로퍼티로 노출한다.
 *
 * 빈 맵은 병합에서 아무 키도 더하지 않으므로 등록만으로 카탈로그 동작이 달라지지 않는다.
 * 이 네임스페이스의 키를 지금 조회하면 다른 미등록 키와 똑같이 폴백한다.
 */
internal const val BLAME_NAMESPACE: String = "blame"

/** Blame 화면이 추가할 `blame.*` 키의 자리. */
object BlameKeys

/** Blame 문구 접근자. UND-41 이 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class BlameStrings internal constructor(private val strings: Strings)

/** Blame 문구 네임스페이스 진입점. */
val Strings.blame: BlameStrings get() = BlameStrings(this)

internal val blameTranslations: Map<Locale, Map<StringKey, String>> = emptyMap()
