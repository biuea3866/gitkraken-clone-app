package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "rebase"

/** `rebase.*` 키 정의. */
object RebaseKeys {
    val planTitle = StringKey("$NAMESPACE.planTitle")
    val planHint = StringKey("$NAMESPACE.planHint")
    val emptyTitle = StringKey("$NAMESPACE.emptyTitle")
    val emptyDescription = StringKey("$NAMESPACE.emptyDescription")

    val actionPick = StringKey("$NAMESPACE.action.pick")
    val actionReword = StringKey("$NAMESPACE.action.reword")
    val actionEdit = StringKey("$NAMESPACE.action.edit")
    val actionSquash = StringKey("$NAMESPACE.action.squash")
    val actionFixup = StringKey("$NAMESPACE.action.fixup")
    val actionDrop = StringKey("$NAMESPACE.action.drop")

    val moveUp = StringKey("$NAMESPACE.moveUp")
    val moveDown = StringKey("$NAMESPACE.moveDown")
    val apply = StringKey("$NAMESPACE.apply")
    val discard = StringKey("$NAMESPACE.discard")

    val previewTitle = StringKey("$NAMESPACE.preview.title")
    val previewAbsorbed = StringKey("$NAMESPACE.preview.absorbed")
    val previewDropped = StringKey("$NAMESPACE.preview.dropped")

    val violationFirstAbsorb = StringKey("$NAMESPACE.violation.firstAbsorb")
    val violationAllDropped = StringKey("$NAMESPACE.violation.allDropped")

    val pushedWarning = StringKey("$NAMESPACE.pushedWarning")
    val pushedMark = StringKey("$NAMESPACE.pushedMark")
    val stopsWarning = StringKey("$NAMESPACE.stopsWarning")
    val rewordPlaceholder = StringKey("$NAMESPACE.rewordPlaceholder")

    val progress = StringKey("$NAMESPACE.progress")
    val outcomeCompleted = StringKey("$NAMESPACE.outcome.completed")
    val outcomeNothingToDo = StringKey("$NAMESPACE.outcome.nothingToDo")
    val outcomeConflicted = StringKey("$NAMESPACE.outcome.conflicted")
    val outcomeStoppedForEdit = StringKey("$NAMESPACE.outcome.stoppedForEdit")
}

/** 대화형 리베이스 문구 접근자. */
@Suppress("TooManyFunctions") // 계획 편집·검증·미리보기·결과가 한 화면에 있어 문구 수가 그만큼이다.
@JvmInline
value class RebaseStrings internal constructor(private val strings: Strings) {
    val planTitle: String get() = strings.text(RebaseKeys.planTitle)
    val planHint: String get() = strings.text(RebaseKeys.planHint)
    val emptyTitle: String get() = strings.text(RebaseKeys.emptyTitle)
    val emptyDescription: String get() = strings.text(RebaseKeys.emptyDescription)
    val actionPick: String get() = strings.text(RebaseKeys.actionPick)
    val actionReword: String get() = strings.text(RebaseKeys.actionReword)
    val actionEdit: String get() = strings.text(RebaseKeys.actionEdit)
    val actionSquash: String get() = strings.text(RebaseKeys.actionSquash)
    val actionFixup: String get() = strings.text(RebaseKeys.actionFixup)
    val actionDrop: String get() = strings.text(RebaseKeys.actionDrop)
    val moveUp: String get() = strings.text(RebaseKeys.moveUp)
    val moveDown: String get() = strings.text(RebaseKeys.moveDown)
    val apply: String get() = strings.text(RebaseKeys.apply)
    val discard: String get() = strings.text(RebaseKeys.discard)
    val previewTitle: String get() = strings.text(RebaseKeys.previewTitle)
    val previewDropped: String get() = strings.text(RebaseKeys.previewDropped)
    val violationFirstAbsorb: String get() = strings.text(RebaseKeys.violationFirstAbsorb)
    val violationAllDropped: String get() = strings.text(RebaseKeys.violationAllDropped)
    val pushedWarning: String get() = strings.text(RebaseKeys.pushedWarning)
    val pushedMark: String get() = strings.text(RebaseKeys.pushedMark)
    val stopsWarning: String get() = strings.text(RebaseKeys.stopsWarning)
    val rewordPlaceholder: String get() = strings.text(RebaseKeys.rewordPlaceholder)
    val outcomeCompleted: String get() = strings.text(RebaseKeys.outcomeCompleted)
    val outcomeNothingToDo: String get() = strings.text(RebaseKeys.outcomeNothingToDo)
    val outcomeStoppedForEdit: String get() = strings.text(RebaseKeys.outcomeStoppedForEdit)

    fun previewAbsorbed(count: Int): String = strings.text(RebaseKeys.previewAbsorbed, count.toString())

    fun progress(applied: Int, total: Int): String =
        strings.text(RebaseKeys.progress, applied.toString(), total.toString())

    fun outcomeConflicted(paths: String): String = strings.text(RebaseKeys.outcomeConflicted, paths)
}

/** 리베이스 문구 네임스페이스 진입점. */
val Strings.rebase: RebaseStrings get() = RebaseStrings(this)

internal val rebaseTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        RebaseKeys.planTitle to "리베이스 계획",
        RebaseKeys.planHint to "적용을 누를 때까지 저장소는 바뀌지 않습니다.",
        RebaseKeys.emptyTitle to "리베이스할 커밋이 없습니다",
        RebaseKeys.emptyDescription to "기준 브랜치 이후에 쌓인 커밋이 없습니다.",
        RebaseKeys.actionPick to "pick",
        RebaseKeys.actionReword to "reword",
        RebaseKeys.actionEdit to "edit",
        RebaseKeys.actionSquash to "squash",
        RebaseKeys.actionFixup to "fixup",
        RebaseKeys.actionDrop to "drop",
        RebaseKeys.moveUp to "위로",
        RebaseKeys.moveDown to "아래로",
        RebaseKeys.apply to "적용",
        RebaseKeys.discard to "계획 취소",
        RebaseKeys.previewTitle to "적용 결과 미리보기",
        RebaseKeys.previewAbsorbed to "커밋 {0}개가 합쳐집니다",
        RebaseKeys.previewDropped to "버려짐",
        RebaseKeys.violationFirstAbsorb to "첫 커밋은 앞에 합칠 대상이 없어 합치기를 지정할 수 없습니다.",
        RebaseKeys.violationAllDropped to "모든 커밋을 버리면 결과가 빈 리베이스입니다.",
        RebaseKeys.pushedWarning to "이미 원격에 올라간 커밋을 다시 씁니다 — 이력이 갈라집니다.",
        RebaseKeys.pushedMark to "원격에 있음",
        RebaseKeys.stopsWarning to "'멈추고 편집' 이 있어 실행 중 리베이스가 멈춥니다.",
        RebaseKeys.rewordPlaceholder to "새 커밋 메시지",
        RebaseKeys.progress to "{1}개 중 {0}개 적용",
        RebaseKeys.outcomeCompleted to "리베이스를 끝냈습니다.",
        RebaseKeys.outcomeNothingToDo to "적용할 커밋이 없었습니다.",
        RebaseKeys.outcomeConflicted to "충돌로 멈췄습니다 — {0}",
        RebaseKeys.outcomeStoppedForEdit to "편집을 위해 멈췄습니다. 고친 뒤 계속하세요.",
    ),
    Locale.ENGLISH to mapOf(
        RebaseKeys.planTitle to "Rebase plan",
        RebaseKeys.planHint to "The repository stays untouched until you apply.",
        RebaseKeys.emptyTitle to "Nothing to rebase",
        RebaseKeys.emptyDescription to "No commits sit on top of the base branch.",
        RebaseKeys.actionPick to "pick",
        RebaseKeys.actionReword to "reword",
        RebaseKeys.actionEdit to "edit",
        RebaseKeys.actionSquash to "squash",
        RebaseKeys.actionFixup to "fixup",
        RebaseKeys.actionDrop to "drop",
        RebaseKeys.moveUp to "Up",
        RebaseKeys.moveDown to "Down",
        RebaseKeys.apply to "Apply",
        RebaseKeys.discard to "Discard plan",
        RebaseKeys.previewTitle to "Result preview",
        RebaseKeys.previewAbsorbed to "{0} commits squashed in",
        RebaseKeys.previewDropped to "dropped",
        RebaseKeys.violationFirstAbsorb to "The first commit has nothing to squash into.",
        RebaseKeys.violationAllDropped to "Dropping every commit leaves an empty rebase.",
        RebaseKeys.pushedWarning to "This rewrites commits already pushed — history will diverge.",
        RebaseKeys.pushedMark to "pushed",
        RebaseKeys.stopsWarning to "\"Stop and edit\" will pause the rebase while it runs.",
        RebaseKeys.rewordPlaceholder to "New commit message",
        RebaseKeys.progress to "{0} of {1} applied",
        RebaseKeys.outcomeCompleted to "Rebase finished.",
        RebaseKeys.outcomeNothingToDo to "There was nothing to apply.",
        RebaseKeys.outcomeConflicted to "Stopped on a conflict — {0}",
        RebaseKeys.outcomeStoppedForEdit to "Stopped for editing. Continue when you are done.",
    ),
)
