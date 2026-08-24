package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "graph"

/** `graph.*` 키 정의. 커밋 그래프 화면이 노출하는 문구만 둔다. */
object GraphKeys {
    val emptyTitle = StringKey("$NAMESPACE.empty.title")
    val emptyDescription = StringKey("$NAMESPACE.empty.description")
    val errorTitle = StringKey("$NAMESPACE.error.title")
    val errorDescription = StringKey("$NAMESPACE.error.description")
    val loading = StringKey("$NAMESPACE.loading")
    val head = StringKey("$NAMESPACE.head")
}

/**
 * 그래프 문구 접근자. `strings.graph.emptyTitle` 로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 여러 티켓이 한 줄씩 고치면 충돌하는
 * 공용 파일이라 등록을 UND-26 이 일괄로 한다 (wave 3 결정 A3).
 */
@JvmInline
value class GraphStrings internal constructor(private val strings: Strings) {
    val emptyTitle: String get() = strings.text(GraphKeys.emptyTitle)
    val emptyDescription: String get() = strings.text(GraphKeys.emptyDescription)
    val errorTitle: String get() = strings.text(GraphKeys.errorTitle)
    val errorDescription: String get() = strings.text(GraphKeys.errorDescription)
    val loading: String get() = strings.text(GraphKeys.loading)

    /** HEAD 칩 라벨. git 용어를 그대로 쓰지만 표기는 로케일이 정한다. */
    val head: String get() = strings.text(GraphKeys.head)
}

/** 그래프 문구 네임스페이스 진입점. */
val Strings.graph: GraphStrings get() = GraphStrings(this)

internal val graphTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        GraphKeys.emptyTitle to "표시할 커밋이 없습니다",
        GraphKeys.emptyDescription to "커밋을 만들면 여기에 이력이 나타납니다.",
        GraphKeys.errorTitle to "이력을 불러오지 못했습니다",
        GraphKeys.errorDescription to "저장소 상태를 확인한 뒤 다시 시도하세요.",
        GraphKeys.loading to "이력을 불러오는 중",
        GraphKeys.head to "HEAD",
    ),
    Locale.ENGLISH to mapOf(
        GraphKeys.emptyTitle to "No commits to show",
        GraphKeys.emptyDescription to "History appears here once you create a commit.",
        GraphKeys.errorTitle to "Could not load history",
        GraphKeys.errorDescription to "Check the repository state and try again.",
        GraphKeys.loading to "Loading history",
        GraphKeys.head to "HEAD",
    ),
)
