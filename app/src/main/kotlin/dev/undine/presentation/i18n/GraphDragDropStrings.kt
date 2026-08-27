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
object GraphDragDropKeys {
    val mergeOrRebase = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.mergeOrRebase")
    val merge = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.merge")
    val rebase = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.rebase")
    val cherryPick = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.cherryPick")
    val reset = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.reset")
    val moveTag = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.moveTag")
    val destructiveWarning = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.destructiveWarning")
    val unsupported = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.unsupported")
    val sameRef = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.sameRef")
    val sameCommit = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.sameCommit")
    val annotatedTag = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.annotatedTag")
    val conflict = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.conflict")
    val unavailableCommand = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.unavailableCommand")
    val currentBranch = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.currentBranch")
    val commandMerge = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.commandMerge")
    val commandRebase = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.commandRebase")
    val commandCherryPick = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.commandCherryPick")
    val commandReset = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.commandReset")
    val commandMoveTag = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.commandMoveTag")
    val confirm = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.confirm")
    val undoRecordFailed = StringKey("$GRAPH_DRAG_DROP_NAMESPACE.undoRecordFailed")
}

/** 그래프 드래그&드롭 문구 접근자. UND-42 가 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class GraphDragDropStrings internal constructor(private val strings: Strings) {
    fun mergeOrRebase(source: String, target: String): String =
        strings.text(GraphDragDropKeys.mergeOrRebase, source, target)
    fun merge(source: String, target: String): String = strings.text(GraphDragDropKeys.merge, source, target)
    fun rebase(branch: String, upstream: String): String = strings.text(GraphDragDropKeys.rebase, branch, upstream)
    fun cherryPick(commit: String, target: String): String = strings.text(GraphDragDropKeys.cherryPick, commit, target)
    fun reset(branch: String, target: String): String = strings.text(GraphDragDropKeys.reset, branch, target)
    fun moveTag(tag: String, target: String): String = strings.text(GraphDragDropKeys.moveTag, tag, target)
    val destructiveWarning: String get() = strings.text(GraphDragDropKeys.destructiveWarning)
    val unsupported: String get() = strings.text(GraphDragDropKeys.unsupported)
    val sameRef: String get() = strings.text(GraphDragDropKeys.sameRef)
    val sameCommit: String get() = strings.text(GraphDragDropKeys.sameCommit)
    val annotatedTag: String get() = strings.text(GraphDragDropKeys.annotatedTag)
    fun conflict(paths: String): String = strings.text(GraphDragDropKeys.conflict, paths)
    val unavailableCommand: String get() = strings.text(GraphDragDropKeys.unavailableCommand)
    val currentBranch: String get() = strings.text(GraphDragDropKeys.currentBranch)
    val commandMerge: String get() = strings.text(GraphDragDropKeys.commandMerge)
    val commandRebase: String get() = strings.text(GraphDragDropKeys.commandRebase)
    val commandCherryPick: String get() = strings.text(GraphDragDropKeys.commandCherryPick)
    val commandReset: String get() = strings.text(GraphDragDropKeys.commandReset)
    val commandMoveTag: String get() = strings.text(GraphDragDropKeys.commandMoveTag)
    val confirm: String get() = strings.text(GraphDragDropKeys.confirm)

    /** 저장소는 바뀌었는데 Undo 항목만 남지 않은 경우의 안내 — 앱이 되돌릴 수 없다는 사실과 대안 경로. */
    val undoRecordFailed: String get() = strings.text(GraphDragDropKeys.undoRecordFailed)
}

/** 그래프 드래그&드롭 문구 네임스페이스 진입점. */
val Strings.graphDragDrop: GraphDragDropStrings get() = GraphDragDropStrings(this)

internal val graphDragDropTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        GraphDragDropKeys.mergeOrRebase to "{0} 를 {1} 에 병합하거나 리베이스합니다",
        GraphDragDropKeys.merge to "{0} 를 {1} 에 병합합니다",
        GraphDragDropKeys.rebase to "{0} 를 {1} 위로 리베이스합니다",
        GraphDragDropKeys.cherryPick to "{0} 을(를) {1} 에 cherry-pick 합니다",
        GraphDragDropKeys.reset to "{0} 브랜치를 {1} 커밋으로 reset 합니다",
        GraphDragDropKeys.moveTag to "{0} 태그를 {1} 커밋으로 이동합니다",
        GraphDragDropKeys.destructiveWarning to "브랜치와 워킹 트리의 커밋하지 않은 변경이 사라질 수 있습니다",
        GraphDragDropKeys.unsupported to "이 조합은 그래프에서 실행할 수 없습니다",
        GraphDragDropKeys.sameRef to "같은 브랜치에는 놓을 수 없습니다",
        GraphDragDropKeys.sameCommit to "이미 같은 커밋을 가리키고 있습니다",
        GraphDragDropKeys.annotatedTag to "annotated 태그는 이동할 수 없습니다",
        GraphDragDropKeys.conflict to "충돌을 해결한 뒤 계속하거나 중단하세요: {0}",
        GraphDragDropKeys.unavailableCommand to "선택한 그래프 항목으로는 실행할 수 없습니다",
        GraphDragDropKeys.currentBranch to "현재 브랜치",
        GraphDragDropKeys.commandMerge to "병합",
        GraphDragDropKeys.commandRebase to "리베이스",
        GraphDragDropKeys.commandCherryPick to "Cherry-pick",
        GraphDragDropKeys.commandReset to "브랜치 reset",
        GraphDragDropKeys.commandMoveTag to "태그 이동",
        GraphDragDropKeys.confirm to "그래프 조작을 확인하세요",
        GraphDragDropKeys.undoRecordFailed to
            "조작은 적용됐지만 되돌리기(Undo) 기록에 실패했습니다. " +
            "이 변경은 Undo 목록에 없으니 reflog 화면에서 이전 지점을 찾으세요.",
    ),
    Locale.ENGLISH to mapOf(
        GraphDragDropKeys.mergeOrRebase to "Merge {0} into {1} or rebase it",
        GraphDragDropKeys.merge to "Merge {0} into {1}",
        GraphDragDropKeys.rebase to "Rebase {0} onto {1}",
        GraphDragDropKeys.cherryPick to "Cherry-pick {0} onto {1}",
        GraphDragDropKeys.reset to "Reset branch {0} to commit {1}",
        GraphDragDropKeys.moveTag to "Move tag {0} to commit {1}",
        GraphDragDropKeys.destructiveWarning to "Uncommitted changes in the branch and working tree may be lost",
        GraphDragDropKeys.unsupported to "This graph combination cannot be performed",
        GraphDragDropKeys.sameRef to "You cannot drop onto the same branch",
        GraphDragDropKeys.sameCommit to "It already points to that commit",
        GraphDragDropKeys.annotatedTag to "Annotated tags cannot be moved",
        GraphDragDropKeys.conflict to "Resolve the conflict, then continue or abort: {0}",
        GraphDragDropKeys.unavailableCommand to "The selected graph item cannot run this command",
        GraphDragDropKeys.currentBranch to "Current branch",
        GraphDragDropKeys.commandMerge to "Merge",
        GraphDragDropKeys.commandRebase to "Rebase",
        GraphDragDropKeys.commandCherryPick to "Cherry-pick",
        GraphDragDropKeys.commandReset to "Reset branch",
        GraphDragDropKeys.commandMoveTag to "Move tag",
        GraphDragDropKeys.confirm to "Confirm graph operation",
        GraphDragDropKeys.undoRecordFailed to
            "The operation was applied, but recording it for undo failed. " +
            "It is not in the undo list — find the previous point in the reflog screen.",
    ),
)
