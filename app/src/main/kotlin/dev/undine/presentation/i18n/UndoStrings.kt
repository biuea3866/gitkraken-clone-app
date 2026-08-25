package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `undo.*` 네임스페이스 — 되돌리기 버튼과 실행 이력 패널의 문구.
 *
 * **아직 비어 있다.** UND-63 이 [builtInTranslations] 등록까지만 해 두고, 키 정의 object·접근자
 * value class·로케일별 번역은 UND-43(Undo 버튼 · 실행 이력 패널)이 **이 파일 안에서만** 채운다.
 * 공통 파일(`BuiltInStrings.kt`)은 이미 등록돼 있으므로 건드리지 않는다 — 같은 wave 의 화면 7건이
 * 그 파일을 함께 고치면 머지 충돌이 난다.
 *
 * 연산 종류의 사용자 노출 이름은 `GitOperationKind.label` 이 이미 갖고 있다 — 그 이름을 여기에
 * 다시 적지 않는다.
 *
 * 채우는 모양은 [CommonStrings] 가 정본이다: [UNDO_NAMESPACE] 로 키를 만들고, 번역 맵을
 * 로케일별로 채우고, `Strings.undo` 확장 프로퍼티로 노출한다.
 */
internal const val UNDO_NAMESPACE: String = "undo"

/** Undo 화면이 추가할 `undo.*` 키의 자리. */
object UndoKeys

/** Undo 문구 접근자. UND-43 이 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class UndoStrings internal constructor(private val strings: Strings)

/** Undo 문구 네임스페이스 진입점. */
val Strings.undo: UndoStrings get() = UndoStrings(this)

internal val undoTranslations: Map<Locale, Map<StringKey, String>> = emptyMap()
