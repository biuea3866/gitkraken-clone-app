package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `undo.*` 네임스페이스 — 되돌리기 버튼과 실행 이력 패널의 문구.
 *
 * UND-63 이 [builtInTranslations] 등록까지 해 두었고, 키 정의 object·접근자 value class·로케일별
 * 번역은 UND-43(Undo 버튼 · 실행 이력 패널)이 **이 파일 안에서만** 채운다. 공통 파일
 * (`BuiltInStrings.kt`)은 건드리지 않는다 — 같은 wave 의 화면 7건이 그 파일을 함께 고치면 충돌한다.
 *
 * 연산 종류의 사용자 노출 이름은 `GitOperationKind.label` 이 이미 갖고 있다 — 그 이름을 여기에
 * 다시 적지 않고 인자로 받는다.
 *
 * **domain 의 거부 사유 문장을 그대로 노출하지 않는다.** `UndoOutcome.Refused.reason` 은 진단용
 * 한국어 하드코딩이라 로케일을 따르지 않는다 — 화면 문구는 여기의 키로만 만든다.
 *
 * `MessageFormat` 패턴이라 작은따옴표를 쓰지 않는다 — 인용부호가 인자 치환을 통째로 막는다.
 */
internal const val UNDO_NAMESPACE: String = "undo"

/** `undo.*` 키 정의 — 버튼 레이블·툴팁, 불가 사유, 이력 패널. */
object UndoKeys {
    val idleLabel = StringKey("$UNDO_NAMESPACE.button.idle")
    val undoLabel = StringKey("$UNDO_NAMESPACE.button.undo")
    val undoTooltip = StringKey("$UNDO_NAMESPACE.button.tooltip")
    val running = StringKey("$UNDO_NAMESPACE.running")
    val undone = StringKey("$UNDO_NAMESPACE.result.undone")
    val nothingToUndo = StringKey("$UNDO_NAMESPACE.reason.nothingToUndo")
    val irreversible = StringKey("$UNDO_NAMESPACE.reason.irreversible")
    val externalChange = StringKey("$UNDO_NAMESPACE.reason.externalChange")
    val detachedHead = StringKey("$UNDO_NAMESPACE.reason.detachedHead")
    val uncommittedChanges = StringKey("$UNDO_NAMESPACE.reason.uncommittedChanges")
    val unmergedBranch = StringKey("$UNDO_NAMESPACE.reason.unmergedBranch")
    val historyTitle = StringKey("$UNDO_NAMESPACE.history.title")
    val historyEmpty = StringKey("$UNDO_NAMESPACE.history.empty")
    val historyReversible = StringKey("$UNDO_NAMESPACE.history.reversible")
    val historyIrreversible = StringKey("$UNDO_NAMESPACE.history.irreversible")

    /** 번역 누락 검사가 키를 하나씩 나열하지 않도록 전체 목록을 노출한다. */
    val all: List<StringKey> = listOf(
        idleLabel, undoLabel, undoTooltip, running, undone,
        nothingToUndo, irreversible, externalChange, detachedHead, uncommittedChanges, unmergedBranch,
        historyTitle, historyEmpty, historyReversible, historyIrreversible,
    )
}

/**
 * Undo 문구 접근자. `strings.undo.undoLabel(operation)` 로 읽는다.
 *
 * 버튼 레이블·툴팁이 **대상 동작 이름을 인자로 받는** 이유는 "실행 취소" 만 보고 누르면 무엇이
 * 취소될지 모르기 때문이다.
 */
@JvmInline
value class UndoStrings internal constructor(private val strings: Strings) {

    /** 되돌릴 것이 없을 때의 버튼 레이블. 대상이 없으니 동작 이름을 붙이지 않는다. */
    val idleLabel: String get() = strings.text(UndoKeys.idleLabel)

    val running: String get() = strings.text(UndoKeys.running)
    val nothingToUndo: String get() = strings.text(UndoKeys.nothingToUndo)
    val externalChange: String get() = strings.text(UndoKeys.externalChange)
    val detachedHead: String get() = strings.text(UndoKeys.detachedHead)
    val uncommittedChanges: String get() = strings.text(UndoKeys.uncommittedChanges)
    val unmergedBranch: String get() = strings.text(UndoKeys.unmergedBranch)
    val historyTitle: String get() = strings.text(UndoKeys.historyTitle)
    val historyEmpty: String get() = strings.text(UndoKeys.historyEmpty)
    val historyReversible: String get() = strings.text(UndoKeys.historyReversible)
    val historyIrreversible: String get() = strings.text(UndoKeys.historyIrreversible)

    /** `커밋 취소` — 무엇이 취소되는지를 레이블 자체가 말한다. */
    fun undoLabel(operation: String): String = strings.text(UndoKeys.undoLabel, operation)

    /** 레이블보다 한 줄 더 — 되돌릴 대상까지 말한다. */
    fun undoTooltip(operation: String, target: String): String =
        strings.text(UndoKeys.undoTooltip, operation, target)

    fun undone(operation: String): String = strings.text(UndoKeys.undone, operation)

    fun irreversible(operation: String): String = strings.text(UndoKeys.irreversible, operation)
}

/** Undo 문구 네임스페이스 진입점. */
val Strings.undo: UndoStrings get() = UndoStrings(this)

internal val undoTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        UndoKeys.idleLabel to "실행 취소",
        UndoKeys.undoLabel to "{0} 취소",
        UndoKeys.undoTooltip to "{0} 취소 — 대상: {1}",
        UndoKeys.running to "되돌리는 중…",
        UndoKeys.undone to "{0} 을(를) 되돌렸습니다.",
        UndoKeys.nothingToUndo to "되돌릴 작업이 없습니다",
        UndoKeys.irreversible to "{0} 는 되돌릴 수 없습니다",
        UndoKeys.externalChange to "저장소가 외부에서 변경되어 되돌릴 수 없습니다",
        UndoKeys.detachedHead to "브랜치를 체크아웃한 상태에서만 되돌릴 수 있습니다.",
        UndoKeys.uncommittedChanges to
            "커밋되지 않은 변경이 있어 되돌릴 수 없습니다. 먼저 커밋하거나 스태시하세요.",
        UndoKeys.unmergedBranch to "브랜치에 병합되지 않은 커밋이 있어 되돌리지 않았습니다.",
        UndoKeys.historyTitle to "실행 이력",
        UndoKeys.historyEmpty to "이 세션에서 실행한 연산이 없습니다.",
        UndoKeys.historyReversible to "되돌릴 수 있음",
        UndoKeys.historyIrreversible to "되돌릴 수 없음",
    ),
    Locale.ENGLISH to mapOf(
        UndoKeys.idleLabel to "Undo",
        UndoKeys.undoLabel to "Undo {0}",
        UndoKeys.undoTooltip to "Undo {0} — target: {1}",
        UndoKeys.running to "Undoing…",
        UndoKeys.undone to "Undid {0}.",
        UndoKeys.nothingToUndo to "There is nothing to undo.",
        UndoKeys.irreversible to "{0} cannot be undone.",
        UndoKeys.externalChange to "The repository changed outside the app, so this cannot be undone.",
        UndoKeys.detachedHead to "You can only undo while a branch is checked out.",
        UndoKeys.uncommittedChanges to
            "You have uncommitted changes, so this cannot be undone. Commit or stash them first.",
        UndoKeys.unmergedBranch to "The branch has unmerged commits, so it was not deleted.",
        UndoKeys.historyTitle to "Operation history",
        UndoKeys.historyEmpty to "No operations were run in this session.",
        UndoKeys.historyReversible to "Undoable",
        UndoKeys.historyIrreversible to "Not undoable",
    ),
)
