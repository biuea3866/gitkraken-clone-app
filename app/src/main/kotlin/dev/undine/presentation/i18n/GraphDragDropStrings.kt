package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `graphdragdrop.*` 네임스페이스 — 그래프에서 끌어다 놓는 조작과 그 키보드 등가 경로의 문구.
 *
 * 기존 `graph.*` 네임스페이스([GraphKeys])와 **따로 둔다** — 그래프 렌더링은 UND-14 가 소유한
 * 파일이고, 드래그 조작 문구는 UND-42 가 자기 파일에서 채워야 두 티켓이 같은 파일을 쓰지 않는다.
 *
 * **아직 비어 있다.** UND-63 이 [builtInTranslations] 등록까지만 해 두고, 키 정의 object·접근자
 * value class·로케일별 번역은 UND-42(그래프 드래그&드롭 조작)가 **이 파일 안에서만** 채운다.
 *
 * 채우는 모양은 [CommonStrings] 가 정본이다: [GRAPH_DRAG_DROP_NAMESPACE] 로 키를 만들고, 번역 맵을
 * 로케일별로 채우고, `Strings.graphDragDrop` 확장 프로퍼티로 노출한다.
 *
 * 빈 맵은 병합에서 아무 키도 더하지 않으므로 등록만으로 카탈로그 동작이 달라지지 않는다.
 * 이 네임스페이스의 키를 지금 조회하면 다른 미등록 키와 똑같이 폴백한다.
 */
internal const val GRAPH_DRAG_DROP_NAMESPACE: String = "graphdragdrop"

/** 그래프 드래그&드롭 화면이 추가할 `graphdragdrop.*` 키의 자리. */
object GraphDragDropKeys

/** 그래프 드래그&드롭 문구 접근자. UND-42 가 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class GraphDragDropStrings internal constructor(private val strings: Strings)

/** 그래프 드래그&드롭 문구 네임스페이스 진입점. */
val Strings.graphDragDrop: GraphDragDropStrings get() = GraphDragDropStrings(this)

internal val graphDragDropTranslations: Map<Locale, Map<StringKey, String>> = emptyMap()
